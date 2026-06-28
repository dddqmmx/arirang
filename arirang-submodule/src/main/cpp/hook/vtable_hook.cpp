#include "vtable_hook.hpp"

#include "logging.hpp"
#include "symbol_resolver.hpp"

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/mman.h>
#include <unistd.h>
#include <vector>

namespace arirang {
namespace {

// vtable hook 的目标是改“数据指针”，不改“代码指令”。本文件把 maps 解析、
// 候选 slot 判断、绝对/相对 slot patch、卸载恢复拆成独立 helper，让每个
// 函数的控制流嵌套保持在三层以内，方便审计误 patch 风险。

constexpr size_t kPointerSize = sizeof(void *);

struct MapEntry {
    uintptr_t start = 0;
    uintptr_t end = 0;
    bool readable = false;
    bool writable = false;
    bool executable = false;
    std::string path;
};

struct ScanStats {
    size_t scanned = 0;
    size_t patched = 0;
};

bool path_matches(const char *full, const char *needle) {
    if (full == nullptr || needle == nullptr) return false;
    if (std::strcmp(full, needle) == 0) return true;

    const char *full_base = std::strrchr(full, '/');
    const char *needle_base = std::strrchr(needle, '/');
    full_base = full_base ? full_base + 1 : full;
    needle_base = needle_base ? needle_base + 1 : needle;
    return std::strcmp(full_base, needle_base) == 0;
}

const char *trim_leading_space(const char *text) {
    while (*text == ' ' || *text == '\t') {
        ++text;
    }

    return text;
}

bool parse_maps_line(const char *line, MapEntry *out) {
    unsigned long long start = 0;
    unsigned long long end = 0;
    unsigned long long offset = 0;
    unsigned long long inode = 0;
    char perms[8] = {};
    char dev[32] = {};
    char path_buf[512] = {};
    const int matched = std::sscanf(
        line, "%llx-%llx %7s %llx %31s %llu %511[^\n]",
        &start, &end, perms, &offset, dev, &inode, path_buf);
    if (matched < 3) return false;

    MapEntry entry;
    entry.start = static_cast<uintptr_t>(start);
    entry.end = static_cast<uintptr_t>(end);
    entry.readable = perms[0] == 'r';
    entry.writable = perms[1] == 'w';
    entry.executable = perms[2] == 'x';
    if (matched >= 7) {
        entry.path = trim_leading_space(path_buf);
    }

    *out = entry;
    return true;
}

std::vector<MapEntry> read_self_maps() {
    std::vector<MapEntry> out;
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        log_warn(std::string("vtable_hook: cannot open /proc/self/maps: ") +
                 std::strerror(errno));
        return out;
    }

    FILE *file = fdopen(fd, "r");
    if (file == nullptr) {
        log_warn(std::string("vtable_hook: fdopen /proc/self/maps failed: ") +
                 std::strerror(errno));
        close(fd);
        return out;
    }

    char line[1024];
    while (std::fgets(line, sizeof(line), file) != nullptr) {
        MapEntry entry;
        if (parse_maps_line(line, &entry)) out.push_back(entry);
    }

    std::fclose(file);
    return out;
}

bool set_page_writable(uintptr_t addr, bool writable) {
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return false;

    const uintptr_t aligned = addr & ~static_cast<uintptr_t>(page_size - 1);
    const int prot = writable ? (PROT_READ | PROT_WRITE) : PROT_READ;
    return mprotect(reinterpret_cast<void *>(aligned), static_cast<size_t>(page_size), prot) == 0;
}

bool address_range_fits(uintptr_t addr, size_t size) {
    return addr <= UINTPTR_MAX - size;
}

bool address_range_in_map(uintptr_t addr, size_t size, const MapEntry &entry) {
    if (!address_range_fits(addr, size)) return false;

    return addr >= entry.start && addr + size <= entry.end;
}

bool address_range_readable(uintptr_t addr, size_t size, const std::vector<MapEntry> &maps) {
    for (const auto &entry : maps) {
        if (entry.readable && address_range_in_map(addr, size, entry)) return true;
    }

    return false;
}

bool address_in_executable_region(uintptr_t addr, const std::vector<MapEntry> &maps) {
    for (const auto &entry : maps) {
        if (entry.executable && addr >= entry.start && addr < entry.end) return true;
    }

    return false;
}

bool is_library_data_map(const MapEntry &entry, const char *library_path) {
    if (!path_matches(entry.path.c_str(), library_path)) return false;

    // vtable / method table 通常在 .data.rel.ro 或 .data：可读、不可执行。
    // 可写与否不作为过滤条件，因为 .data.rel.ro 会在重定位后变只读。
    return entry.readable && !entry.executable;
}

uintptr_t align_up(uintptr_t value, size_t alignment) {
    const uintptr_t mask = static_cast<uintptr_t>(alignment - 1);
    if (value > UINTPTR_MAX - mask) return UINTPTR_MAX;

    return (value + mask) & ~mask;
}

bool address_with_delta(uintptr_t base, intptr_t delta, size_t min_addr, uintptr_t *out) {
    if (delta < 0) {
        const auto distance = static_cast<uintptr_t>(-delta);
        if (base < distance) return false;

        *out = base - distance;
        return *out >= min_addr;
    }

    if (UINTPTR_MAX - base < static_cast<uintptr_t>(delta)) return false;

    *out = base + static_cast<uintptr_t>(delta);
    return *out >= min_addr;
}

uintptr_t relative_slot_destination(uintptr_t slot) {
    const int32_t rel = *reinterpret_cast<int32_t *>(slot);
    return static_cast<uintptr_t>(static_cast<intptr_t>(slot) + rel);
}

bool looks_like_relative_vtable_slot(uintptr_t slot, const std::vector<MapEntry> &maps) {
    const intptr_t deltas[] = {-4, 4, -8, 8};

    // relative vtable 的每个 slot 是“slot 地址 + int32 偏移”。单个 rel32
    // 命中很容易误撞 .eh_frame / CFI 数据，所以要求邻近 slot 也能解析到
    // 可执行内存，证明它更像连续的方法表。
    for (intptr_t delta : deltas) {
        uintptr_t neighbour = 0;
        if (!address_with_delta(slot, delta, sizeof(int32_t), &neighbour)) continue;
        if (!address_range_readable(neighbour, sizeof(int32_t), maps)) continue;
        if (address_in_executable_region(relative_slot_destination(neighbour), maps)) return true;
    }

    return false;
}

uintptr_t absolute_slot_value(uintptr_t slot) {
    return reinterpret_cast<uintptr_t>(*reinterpret_cast<void **>(slot));
}

bool looks_like_absolute_vtable_slot(uintptr_t slot, const std::vector<MapEntry> &maps) {
    const intptr_t deltas[] = {
        -static_cast<intptr_t>(kPointerSize),
        static_cast<intptr_t>(kPointerSize),
    };

    // 真实 vtable 通常是连续函数指针数组。要求左右至少一个邻居也指向
    // 可执行段，可以过滤掉“孤立全局函数指针刚好等于目标函数”的误 patch。
    for (intptr_t delta : deltas) {
        uintptr_t neighbour = 0;
        if (!address_with_delta(slot, delta, kPointerSize, &neighbour)) continue;
        if (!address_range_readable(neighbour, kPointerSize, maps)) continue;
        if (address_in_executable_region(absolute_slot_value(neighbour), maps)) return true;
    }

    return false;
}

bool make_slot_writable_if_needed(const MapEntry &entry, uintptr_t addr, const char *kind) {
    if (entry.writable) return true;
    if (set_page_writable(addr, true)) return true;

    log_warn(std::string("vtable_hook: mprotect failed for ") + kind + " @ " +
             std::to_string(addr));
    return false;
}

void restore_slot_protection_if_needed(const MapEntry &entry, uintptr_t addr) {
    if (entry.writable) return;

    set_page_writable(addr, false);
}

VtablePatch make_absolute_patch(void *slot, void *original) {
    VtablePatch patch;
    patch.slot_address = slot;
    patch.original_function = original;
    patch.slot_type = VtableSlotType::kAbsolute64;
    patch.slot_size = kPointerSize;
    return patch;
}

bool patch_absolute_slot(uintptr_t addr,
                         const MapEntry &entry,
                         const ResolvedSymbol &symbol,
                         void *hook,
                         const std::vector<MapEntry> &maps,
                         std::vector<VtablePatch> *out_patches) {
    auto *slot = reinterpret_cast<void **>(addr);
    void *const value = *slot;
    if (value != symbol.address) return false;

    if (!looks_like_absolute_vtable_slot(addr, maps)) {
        log_info(std::string("vtable_hook: skipping isolated pointer @ ") +
                 std::to_string(addr) + " (no executable neighbour)");
        return false;
    }

    if (!make_slot_writable_if_needed(entry, addr, "slot")) return false;

    out_patches->push_back(make_absolute_patch(slot, value));
    *slot = hook;
    restore_slot_protection_if_needed(entry, addr);

    log_info(std::string("vtable_hook: patched absolute slot @ ") +
             std::to_string(addr) + " for " + symbol.name);
    return true;
}

ScanStats scan_absolute_slots_in_entry(const MapEntry &entry,
                                       const ResolvedSymbol &symbol,
                                       void *hook,
                                       const std::vector<MapEntry> &maps,
                                       std::vector<VtablePatch> *out_patches) {
    ScanStats stats;
    const uintptr_t scan_start = align_up(entry.start, kPointerSize);
    if (scan_start >= entry.end) return stats;

    stats.scanned = entry.end - scan_start;
    for (uintptr_t addr = scan_start; addr + kPointerSize <= entry.end; addr += kPointerSize) {
        if (patch_absolute_slot(addr, entry, symbol, hook, maps, out_patches)) ++stats.patched;
    }

    return stats;
}

bool relative_slot_matches(uintptr_t addr,
                           uintptr_t target,
                           const std::vector<MapEntry> &maps) {
    if (relative_slot_destination(addr) != target) return false;

    return looks_like_relative_vtable_slot(addr, maps);
}

bool relative_offset_to_hook(uintptr_t addr, uintptr_t hook_addr, int32_t *out) {
    const auto new_rel = static_cast<intptr_t>(hook_addr) - static_cast<intptr_t>(addr);
    if (new_rel < INT32_MIN || new_rel > INT32_MAX) return false;

    *out = static_cast<int32_t>(new_rel);
    return true;
}

VtablePatch make_relative_patch(uintptr_t addr, uintptr_t target) {
    VtablePatch patch;
    patch.slot_address = reinterpret_cast<void *>(addr);
    patch.original_function = reinterpret_cast<void *>(target);
    patch.slot_type = VtableSlotType::kRelative32;
    patch.slot_size = sizeof(int32_t);
    return patch;
}

bool patch_relative_slot(uintptr_t addr,
                         const MapEntry &entry,
                         const ResolvedSymbol &symbol,
                         uintptr_t hook_addr,
                         const std::vector<MapEntry> &maps,
                         std::vector<VtablePatch> *out_patches) {
    const uintptr_t target = reinterpret_cast<uintptr_t>(symbol.address);
    if (!relative_slot_matches(addr, target, maps)) return false;

    int32_t new_rel = 0;
    if (!relative_offset_to_hook(addr, hook_addr, &new_rel)) {
        log_warn(std::string("vtable_hook: hook too far from relative slot @ ") +
                 std::to_string(addr));
        return false;
    }

    if (!make_slot_writable_if_needed(entry, addr, "rel slot")) return false;

    out_patches->push_back(make_relative_patch(addr, target));
    *reinterpret_cast<int32_t *>(addr) = new_rel;
    restore_slot_protection_if_needed(entry, addr);

    log_info(std::string("vtable_hook: patched relative slot @ ") +
             std::to_string(addr) + " for " + symbol.name);
    return true;
}

size_t scan_relative_slots_in_entry(const MapEntry &entry,
                                    const ResolvedSymbol &symbol,
                                    uintptr_t hook_addr,
                                    const std::vector<MapEntry> &maps,
                                    std::vector<VtablePatch> *out_patches) {
    size_t patched = 0;
    const uintptr_t scan_start = align_up(entry.start, sizeof(int32_t));
    if (scan_start >= entry.end) return patched;

    for (uintptr_t addr = scan_start; addr + sizeof(int32_t) <= entry.end; addr += sizeof(int32_t)) {
        if (patch_relative_slot(addr, entry, symbol, hook_addr, maps, out_patches)) ++patched;
    }

    return patched;
}

ScanStats scan_absolute_for_symbol(const ResolvedSymbol &symbol,
                                   const char *library_path,
                                   void *hook,
                                   const std::vector<MapEntry> &maps,
                                   std::vector<VtablePatch> *out_patches) {
    ScanStats total;
    for (const auto &entry : maps) {
        if (!is_library_data_map(entry, library_path)) continue;

        const ScanStats current =
            scan_absolute_slots_in_entry(entry, symbol, hook, maps, out_patches);
        total.scanned += current.scanned;
        total.patched += current.patched;
    }

    return total;
}

size_t scan_relative_for_symbol(const ResolvedSymbol &symbol,
                                const char *library_path,
                                uintptr_t hook_addr,
                                const std::vector<MapEntry> &maps,
                                std::vector<VtablePatch> *out_patches) {
    size_t patched = 0;
    for (const auto &entry : maps) {
        if (!is_library_data_map(entry, library_path)) continue;

        patched += scan_relative_slots_in_entry(entry, symbol, hook_addr, maps, out_patches);
    }

    return patched;
}

void log_candidate_symbols(const std::vector<ResolvedSymbol> &symbols) {
    for (const auto &symbol : symbols) {
        log_info(std::string("vtable_hook: candidate symbol ") + symbol.name + " @ " +
                 std::to_string(reinterpret_cast<uintptr_t>(symbol.address)));
    }
}

void restore_patch_value(const VtablePatch &patch, uintptr_t addr) {
    if (patch.slot_type == VtableSlotType::kRelative32) {
        const uintptr_t orig_addr = reinterpret_cast<uintptr_t>(patch.original_function);
        const int32_t old_rel =
            static_cast<int32_t>(static_cast<intptr_t>(orig_addr) - static_cast<intptr_t>(addr));
        *reinterpret_cast<int32_t *>(patch.slot_address) = old_rel;
        return;
    }

    *reinterpret_cast<void **>(patch.slot_address) = patch.original_function;
}

bool restore_patch(const VtablePatch &patch) {
    if (patch.slot_address == nullptr) return true;

    const uintptr_t addr = reinterpret_cast<uintptr_t>(patch.slot_address);
    if (!set_page_writable(addr, true)) return false;

    restore_patch_value(patch, addr);
    set_page_writable(addr, false);
    return true;
}

} // namespace

bool vtable_hook_install(const char *library_path,
                         const char *symbol_substring,
                         void *hook,
                         std::vector<VtablePatch> *out_patches,
                         bool try_relative) {
    if (library_path == nullptr || symbol_substring == nullptr ||
        hook == nullptr || out_patches == nullptr) {
        return false;
    }

    const auto symbols = resolve_symbols_by_substring(library_path, symbol_substring);
    if (symbols.empty()) {
        log_warn(std::string("vtable_hook: no symbols matching '") + symbol_substring +
                 "' in " + library_path);
        return false;
    }
    log_candidate_symbols(symbols);

    const auto maps = read_self_maps();
    if (maps.empty()) {
        log_warn("vtable_hook: failed to read /proc/self/maps");
        return false;
    }

    const uintptr_t hook_addr = reinterpret_cast<uintptr_t>(hook);
    size_t total_scanned = 0;
    size_t total_patched = 0;

    for (const auto &symbol : symbols) {
        const ScanStats absolute =
            scan_absolute_for_symbol(symbol, library_path, hook, maps, out_patches);
        total_scanned += absolute.scanned;
        total_patched += absolute.patched;
        if (absolute.patched != 0 || !try_relative) continue;

        total_patched += scan_relative_for_symbol(symbol, library_path, hook_addr, maps,
                                                  out_patches);
    }

    log_info(std::string("vtable_hook: scanned ") + std::to_string(total_scanned) +
             " bytes, patched " + std::to_string(total_patched) + " slots");
    return total_patched > 0;
}

bool vtable_hook_uninstall(const std::vector<VtablePatch> &patches) {
    bool ok = true;
    for (const auto &patch : patches) {
        if (restore_patch(patch)) continue;

        ok = false;
    }

    return ok;
}

} // namespace arirang
