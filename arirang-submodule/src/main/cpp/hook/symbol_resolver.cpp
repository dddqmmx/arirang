#include "symbol_resolver.hpp"

#include "logging.hpp"

#include <cstring>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>

namespace arirang {
namespace {

constexpr size_t kMaxSymbolsScanned = 200000;

const Elf64_Dyn *find_dynamic_entry(const Elf64_Dyn *dyn, Elf64_Sxword tag) {
    for (; dyn->d_tag != DT_NULL; ++dyn) {
        if (dyn->d_tag == tag) return dyn;
    }
    return nullptr;
}

bool is_function_symbol(unsigned char info) {
    const unsigned char type = ELF64_ST_TYPE(info);
    return type == STT_FUNC || type == STT_GNU_IFUNC;
}

bool path_matches(const char *full, const char *needle) {
    if (full == nullptr || needle == nullptr) return false;
    if (std::strcmp(full, needle) == 0) return true;
    // Also match by basename.
    const char *full_base = std::strrchr(full, '/');
    const char *needle_base = std::strrchr(needle, '/');
    full_base = full_base ? full_base + 1 : full;
    needle_base = needle_base ? needle_base + 1 : needle;
    return std::strcmp(full_base, needle_base) == 0;
}

struct IterContext {
    const char *target_path;
    const char *substring;
    std::vector<ResolvedSymbol> *results;
};

int phdr_callback(struct dl_phdr_info *info, size_t /*size*/, void *data) {
    auto *ctx = static_cast<IterContext *>(data);
    if (!path_matches(info->dlpi_name, ctx->target_path)) return 0;

    const auto load_base = static_cast<uintptr_t>(info->dlpi_addr);
    const Elf64_Dyn *dyn = nullptr;
    for (uint16_t i = 0; i < info->dlpi_phnum; ++i) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<const Elf64_Dyn *>(load_base + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (dyn == nullptr) return 0;

    const Elf64_Dyn *sym_entry = find_dynamic_entry(dyn, DT_SYMTAB);
    const Elf64_Dyn *str_entry = find_dynamic_entry(dyn, DT_STRTAB);
    if (sym_entry == nullptr || str_entry == nullptr) {
        log_warn("symbol_resolver: missing DT_SYMTAB/DT_STRTAB");
        return 1; // stop iteration
    }

    auto rebase = [&](Elf64_Addr addr) -> uintptr_t {
        const auto raw = static_cast<uintptr_t>(addr);
        return raw < load_base ? load_base + raw : raw;
    };

    const auto *symtab = reinterpret_cast<const Elf64_Sym *>(rebase(sym_entry->d_un.d_ptr));
    const auto *strtab = reinterpret_cast<const char *>(rebase(str_entry->d_un.d_ptr));

    size_t symbol_count = 0;
    if (const Elf64_Dyn *gnu_hash = find_dynamic_entry(dyn, DT_GNU_HASH); gnu_hash != nullptr) {
        const auto *hash = reinterpret_cast<const uint32_t *>(rebase(gnu_hash->d_un.d_ptr));
        const uint32_t nbuckets = hash[0];
        const uint32_t symoffset = hash[1];
        const uint32_t bloom_size = hash[2];
        const auto *buckets = reinterpret_cast<const uint32_t *>(
            reinterpret_cast<uintptr_t>(hash) + 16 + 8 * bloom_size);
        const auto *chain = buckets + nbuckets;

        uint32_t last_sym = 0;
        for (uint32_t i = 0; i < nbuckets; ++i) {
            if (buckets[i] > last_sym) last_sym = buckets[i];
        }
        if (last_sym >= symoffset) {
            for (;;) {
                if (chain[last_sym - symoffset] & 1u) break;
                ++last_sym;
                if (last_sym - symoffset > kMaxSymbolsScanned) break;
            }
            symbol_count = last_sym + 1;
        }
    }

    if (symbol_count == 0) {
        if (const Elf64_Dyn *hash = find_dynamic_entry(dyn, DT_HASH); hash != nullptr) {
            const auto *hdr = reinterpret_cast<const uint32_t *>(rebase(hash->d_un.d_ptr));
            symbol_count = hdr[1];
        }
    }

    if (symbol_count == 0 || symbol_count > kMaxSymbolsScanned) {
        log_warn(std::string("symbol_resolver: unexpected symbol count=") +
                 std::to_string(symbol_count));
        return 1;
    }

    for (size_t i = 0; i < symbol_count; ++i) {
        const Elf64_Sym &sym = symtab[i];
        if (sym.st_name == 0 || sym.st_value == 0) continue;
        if (!is_function_symbol(sym.st_info)) continue;
        const char *name = strtab + sym.st_name;
        if (std::strstr(name, ctx->substring) == nullptr) continue;

        ResolvedSymbol entry;
        entry.name = name;
        entry.address = reinterpret_cast<void *>(load_base + sym.st_value);
        entry.library_base = reinterpret_cast<void *>(load_base);
        ctx->results->push_back(std::move(entry));
    }
    return 1; // we found the target library; stop iteration.
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
