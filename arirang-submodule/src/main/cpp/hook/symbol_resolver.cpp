#include "symbol_resolver.hpp"

#include "logging.hpp"

#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <string>

namespace arirang {
namespace {

// 符号表来自目标进程已经加载的 ELF。这里不 dlopen 新库，只遍历
// dl_iterate_phdr 暴露的对象，避免在 DRM HAL 进程里引入额外加载副作用。
// 本文件也按“最多三层控制流嵌套”组织：ELF 动态段解析、hash 表计数和
// 符号过滤都拆成独立 helper，回调函数只负责串联流程。

constexpr size_t kMaxSymbolsScanned = 200000;

struct IterContext {
    const char *target_path = nullptr;
    const char *substring = nullptr;
    std::vector<ResolvedSymbol> *results = nullptr;
};

struct SymbolTables {
    const Elf64_Sym *symbols = nullptr;
    const char *strings = nullptr;
    size_t count = 0;
};

const Elf64_Dyn *find_dynamic_entry(const Elf64_Dyn *dyn, Elf64_Sxword tag) {
    for (; dyn->d_tag != DT_NULL; ++dyn) {
        if (dyn->d_tag == tag) return dyn;
    }

    return nullptr;
}

uintptr_t rebase_dynamic_pointer(uintptr_t load_base, Elf64_Addr addr) {
    // Android linker 可能把 DT_* 指针写成已重定位绝对地址，也可能保留为
    // 相对 load_base 的虚拟地址。小于 load_base 时按相对地址处理，保持
    // 对两种形式的兼容。
    const auto raw = static_cast<uintptr_t>(addr);
    return raw < load_base ? load_base + raw : raw;
}

bool is_function_symbol(unsigned char info) {
    const unsigned char type = ELF64_ST_TYPE(info);
    return type == STT_FUNC || type == STT_GNU_IFUNC;
}

bool path_matches(const char *full, const char *needle) {
    if (full == nullptr || needle == nullptr) return false;
    if (std::strcmp(full, needle) == 0) return true;

    // 调用方通常传完整 vendor 路径，但 /proc/linker 侧有时只保留 basename。
    // 因此先精确匹配，失败后再按文件名匹配。
    const char *full_base = std::strrchr(full, '/');
    const char *needle_base = std::strrchr(needle, '/');
    full_base = full_base ? full_base + 1 : full;
    needle_base = needle_base ? needle_base + 1 : needle;
    return std::strcmp(full_base, needle_base) == 0;
}

const Elf64_Dyn *find_dynamic_segment(const dl_phdr_info *info, uintptr_t load_base) {
    for (uint16_t i = 0; i < info->dlpi_phnum; ++i) {
        if (info->dlpi_phdr[i].p_type != PT_DYNAMIC) continue;

        return reinterpret_cast<const Elf64_Dyn *>(load_base + info->dlpi_phdr[i].p_vaddr);
    }

    return nullptr;
}

uint32_t largest_gnu_hash_bucket(const uint32_t *buckets, uint32_t nbuckets) {
    uint32_t last_symbol = 0;
    for (uint32_t i = 0; i < nbuckets; ++i) {
        if (buckets[i] > last_symbol) last_symbol = buckets[i];
    }

    return last_symbol;
}

size_t count_gnu_hash_chain_symbols(const uint32_t *chain,
                                    uint32_t symoffset,
                                    uint32_t last_symbol) {
    for (size_t scanned = 0; scanned <= kMaxSymbolsScanned; ++scanned) {
        if (chain[last_symbol - symoffset] & 1u) return static_cast<size_t>(last_symbol) + 1;

        ++last_symbol;
    }

    return 0;
}

size_t symbol_count_from_gnu_hash(const Elf64_Dyn *dyn, uintptr_t load_base) {
    const Elf64_Dyn *gnu_hash = find_dynamic_entry(dyn, DT_GNU_HASH);
    if (gnu_hash == nullptr) return 0;

    // GNU hash 没有直接保存符号总数，只能从 bucket 最大符号下标继续沿
    // chain 走到最低 bit 为 1 的结尾项。这里最多扫描 kMaxSymbolsScanned
    // 个 chain 项，防止损坏 ELF 让我们越走越远。
    const auto *hash = reinterpret_cast<const uint32_t *>(
        rebase_dynamic_pointer(load_base, gnu_hash->d_un.d_ptr));
    const uint32_t nbuckets = hash[0];
    const uint32_t symoffset = hash[1];
    const uint32_t bloom_size = hash[2];
    if (nbuckets == 0) return 0;

    const auto *buckets = reinterpret_cast<const uint32_t *>(
        reinterpret_cast<uintptr_t>(hash) + 16 + 8 * bloom_size);
    const auto *chain = buckets + nbuckets;
    const uint32_t last_symbol = largest_gnu_hash_bucket(buckets, nbuckets);
    if (last_symbol < symoffset) return 0;

    return count_gnu_hash_chain_symbols(chain, symoffset, last_symbol);
}

size_t symbol_count_from_sysv_hash(const Elf64_Dyn *dyn, uintptr_t load_base) {
    const Elf64_Dyn *hash = find_dynamic_entry(dyn, DT_HASH);
    if (hash == nullptr) return 0;

    // SysV hash 头部第二个字段 nchain 就是 dynsym 符号数。
    const auto *hdr = reinterpret_cast<const uint32_t *>(
        rebase_dynamic_pointer(load_base, hash->d_un.d_ptr));
    return hdr[1];
}

size_t resolve_symbol_count(const Elf64_Dyn *dyn, uintptr_t load_base) {
    const size_t gnu_count = symbol_count_from_gnu_hash(dyn, load_base);
    if (gnu_count != 0) return gnu_count;

    return symbol_count_from_sysv_hash(dyn, load_base);
}

bool load_symbol_tables(const Elf64_Dyn *dyn, uintptr_t load_base, SymbolTables *out) {
    const Elf64_Dyn *sym_entry = find_dynamic_entry(dyn, DT_SYMTAB);
    const Elf64_Dyn *str_entry = find_dynamic_entry(dyn, DT_STRTAB);
    if (sym_entry == nullptr || str_entry == nullptr) {
        log_warn("symbol_resolver: missing DT_SYMTAB/DT_STRTAB");
        return false;
    }

    out->symbols = reinterpret_cast<const Elf64_Sym *>(
        rebase_dynamic_pointer(load_base, sym_entry->d_un.d_ptr));
    out->strings = reinterpret_cast<const char *>(
        rebase_dynamic_pointer(load_base, str_entry->d_un.d_ptr));
    out->count = resolve_symbol_count(dyn, load_base);
    if (out->count != 0 && out->count <= kMaxSymbolsScanned) return true;

    log_warn(std::string("symbol_resolver: unexpected symbol count=") +
             std::to_string(out->count));
    return false;
}

bool symbol_name_matches(const Elf64_Sym &sym, const char *strtab, const char *substring) {
    if (sym.st_name == 0 || sym.st_value == 0) return false;
    if (!is_function_symbol(sym.st_info)) return false;

    const char *name = strtab + sym.st_name;
    return std::strstr(name, substring) != nullptr;
}

ResolvedSymbol make_resolved_symbol(const Elf64_Sym &sym,
                                    const char *name,
                                    uintptr_t load_base) {
    ResolvedSymbol entry;
    entry.name = name;
    entry.address = reinterpret_cast<void *>(load_base + sym.st_value);
    entry.library_base = reinterpret_cast<void *>(load_base);
    return entry;
}

void collect_matching_symbols(const SymbolTables &tables,
                              uintptr_t load_base,
                              const char *substring,
                              std::vector<ResolvedSymbol> *results) {
    for (size_t i = 0; i < tables.count; ++i) {
        const Elf64_Sym &sym = tables.symbols[i];
        if (!symbol_name_matches(sym, tables.strings, substring)) continue;

        const char *name = tables.strings + sym.st_name;
        results->push_back(make_resolved_symbol(sym, name, load_base));
    }
}

int phdr_callback(dl_phdr_info *info, size_t /*size*/, void *data) {
    auto *ctx = static_cast<IterContext *>(data);
    if (!path_matches(info->dlpi_name, ctx->target_path)) return 0;

    const auto load_base = static_cast<uintptr_t>(info->dlpi_addr);
    const Elf64_Dyn *dyn = find_dynamic_segment(info, load_base);
    if (dyn == nullptr) return 0;

    SymbolTables tables;
    if (!load_symbol_tables(dyn, load_base, &tables)) return 1;

    collect_matching_symbols(tables, load_base, ctx->substring, ctx->results);
    return 1; // 已处理目标库，停止继续遍历其它 so。
}

} // namespace

std::vector<ResolvedSymbol> resolve_symbols_by_substring(const char *library_path,
                                                         const char *substring) {
    std::vector<ResolvedSymbol> matches;
    if (library_path == nullptr || substring == nullptr) return matches;

    IterContext ctx{library_path, substring, &matches};
    dl_iterate_phdr(phdr_callback, &ctx);
    return matches;
}

} // namespace arirang
