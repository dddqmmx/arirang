#include "vtable_hook.hpp"

#include "logging.hpp"
#include "symbol_resolver.hpp"

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>
#include <vector>

namespace arirang {
namespace {

constexpr size_t kPointerSize = sizeof(void *);

struct MapEntry {
    uintptr_t start = 0;
    uintptr_t end = 0;
    bool readable = false;
    bool writable = false;
    bool executable = false;
    std::string path;
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

std::vector<MapEntry> read_self_maps() {
    std::vector<MapEntry> out;
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        log_warn(std::string("vtable_hook: cannot open /proc/self/maps: ") +
                 std::strerror(errno));
        return out;
    }

    char line[1024];
    std::string leftover;
    while (true) {
        ssize_t n = read(fd, line, sizeof(line) - 1);
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (n == 0) break;
        line[n] = '\0';
        leftover += line;
        size_t pos = 0;
        while (true) {
            size_t newline = leftover.find('\n', pos);
            if (newline == std::string::npos) break;
            const std::string cur = leftover.substr(pos, newline - pos);
            pos = newline + 1;

            unsigned long long start = 0, end = 0;
            unsigned long long offset = 0, inode = 0;
            char perms[8] = {};
            char dev[32] = {};
            char path_buf[512] = {};
            const int matched = std::sscanf(
                cur.c_str(), "%llx-%llx %7s %llx %31s %llu %511[^\n]",
                &start, &end, perms, &offset, dev, &inode, path_buf);
            if (matched < 3) continue;

            MapEntry e;
            e.start = static_cast<uintptr_t>(start);
            e.end = static_cast<uintptr_t>(end);
            e.readable = perms[0] == 'r';
            e.writable = perms[1] == 'w';
            e.executable = perms[2] == 'x';
            if (matched >= 7) {
                const char *p = path_buf;
                while (*p == ' ' || *p == '\t') ++p;
                e.path = p;
            }
            out.push_back(std::move(e));
        }
        leftover = leftover.substr(pos);
    }
    close(fd);
    return out;
}

bool set_page_writable(uintptr_t addr, bool writable) {
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return false;
    const uintptr_t aligned = addr & ~static_cast<uintptr_t>(page_size - 1);
    const int prot = writable ? (PROT_READ | PROT_WRITE) : PROT_READ;
    return mprotect(reinterpret_cast<void *>(aligned), static_cast<size_t>(page_size), prot) == 0;
}

bool address_in_executable_region(uintptr_t addr, const std::vector<MapEntry> &maps) {
    for (const auto &e : maps) {
        if (e.executable && addr >= e.start && addr < e.end) return true;
    }
    return false;
}

// For a 4-byte relative vtable slot, verify that at least one neighbouring slot
// (treated as a signed 32-bit offset) points into an executable mapping. This
// filters out CFI / .eh_frame false positives where a random rel32 value
// happens to point at the target function but nearby values do not point at
// code.
bool looks_like_relative_vtable_slot(uintptr_t slot, const std::vector<MapEntry> &maps) {
    for (intptr_t delta : { -4, 4, -8, 8 }) {
        const uintptr_t neighbour =
            static_cast<uintptr_t>(static_cast<intptr_t>(slot) + delta);
        if (static_cast<intptr_t>(neighbour) < static_cast<intptr_t>(sizeof(int32_t))) continue;
        bool readable = false;
        for (const auto &e : maps) {
            if (e.readable && neighbour >= e.start && neighbour + sizeof(int32_t) <= e.end) {
                readable = true;
                break;
            }
        }
        if (!readable) continue;
        const int32_t rel = *reinterpret_cast<int32_t *>(neighbour);
        const uintptr_t dest =
            static_cast<uintptr_t>(static_cast<intptr_t>(neighbour) + rel);
        if (address_in_executable_region(dest, maps)) return true;
    }
    return false;
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

    for (const auto &sym : symbols) {
        log_info(std::string("vtable_hook: candidate symbol ") + sym.name + " @ " +
                 std::to_string(reinterpret_cast<uintptr_t>(sym.address)));
    }

    const auto maps = read_self_maps();
    if (maps.empty()) {
        log_warn("vtable_hook: failed to read /proc/self/maps");
        return false;
    }

    const uintptr_t hook_addr = reinterpret_cast<uintptr_t>(hook);
    size_t total_scanned = 0;
    size_t total_patched = 0;

    for (const auto &sym : symbols) {
        const uintptr_t target = reinterpret_cast<uintptr_t>(sym.address);
        bool found_absolute = false;

        for (const auto &entry : maps) {
            if (!path_matches(entry.path.c_str(), library_path)) continue;
            if (!entry.readable || entry.executable) continue;

            const uintptr_t scan_start =
                (entry.start + kPointerSize - 1) & ~(kPointerSize - 1);
            if (scan_start >= entry.end) continue;
            const size_t scan_len = entry.end - scan_start;
            total_scanned += scan_len;

            // 1) Absolute 64-bit function pointers.
            for (uintptr_t addr = scan_start; addr + kPointerSize <= entry.end;
                 addr += kPointerSize) {
                void *const slot = reinterpret_cast<void *>(addr);
                void *const value = *reinterpret_cast<void **>(slot);
                if (reinterpret_cast<uintptr_t>(value) != target) continue;

                const bool already_writable = entry.writable;
                if (!already_writable && !set_page_writable(addr, true)) {
                    log_warn(std::string("vtable_hook: mprotect failed for slot @ ") +
                             std::to_string(addr));
                    continue;
                }

                VtablePatch patch;
                patch.slot_address = slot;
                patch.original_function = value;
                patch.slot_type = VtableSlotType::kAbsolute64;
                patch.slot_size = kPointerSize;
                *reinterpret_cast<void **>(slot) = hook;
                out_patches->push_back(patch);
                ++total_patched;
                found_absolute = true;

                if (!already_writable) {
                    set_page_writable(addr, false);
                }

                log_info(std::string("vtable_hook: patched absolute slot @ ") +
                         std::to_string(addr) + " for " + sym.name);
            }
        }

        // 2) Itanium relative vtables: 4-byte signed offsets. Only run if no
        // absolute slot was found, and only inside the same readable
        // non-executable pages. Use an executable-neighbour heuristic to avoid
        // patching CFI data in .eh_frame.
        if (!found_absolute && try_relative) {
            for (const auto &entry : maps) {
                if (!path_matches(entry.path.c_str(), library_path)) continue;
                if (!entry.readable || entry.executable) continue;

                const uintptr_t scan_start = (entry.start + 3) & ~3ULL;
                for (uintptr_t addr = scan_start; addr + sizeof(int32_t) <= entry.end;
                     addr += sizeof(int32_t)) {
                    const int32_t rel = *reinterpret_cast<int32_t *>(addr);
                    if (static_cast<uintptr_t>(static_cast<intptr_t>(addr) + rel) != target)
                        continue;
                    if (!looks_like_relative_vtable_slot(addr, maps)) continue;

                    const intptr_t new_rel_signed =
                        static_cast<intptr_t>(hook_addr) - static_cast<intptr_t>(addr);
                    if (new_rel_signed < INT32_MIN || new_rel_signed > INT32_MAX) {
                        log_warn(std::string("vtable_hook: hook too far from relative slot @ ") +
                                 std::to_string(addr));
                        continue;
                    }

                    const bool already_writable = entry.writable;
                    if (!already_writable && !set_page_writable(addr, true)) {
                        log_warn(std::string("vtable_hook: mprotect failed for rel slot @ ") +
                                 std::to_string(addr));
                        continue;
                    }

                    VtablePatch patch;
                    patch.slot_address = reinterpret_cast<void *>(addr);
                    patch.original_function = reinterpret_cast<void *>(target);
                    patch.slot_type = VtableSlotType::kRelative32;
                    patch.slot_size = sizeof(int32_t);
                    *reinterpret_cast<int32_t *>(addr) = static_cast<int32_t>(new_rel_signed);
                    out_patches->push_back(patch);
                    ++total_patched;

                    if (!already_writable) {
                        set_page_writable(addr, false);
                    }

                    log_info(std::string("vtable_hook: patched relative slot @ ") +
                             std::to_string(addr) + " for " + sym.name);
                }
            }
        }
    }

    log_info(std::string("vtable_hook: scanned ") + std::to_string(total_scanned) +
             " bytes, patched " + std::to_string(total_patched) + " slots");
    return total_patched > 0;
}

bool vtable_hook_uninstall(const std::vector<VtablePatch> &patches) {
    bool ok = true;
    for (const auto &patch : patches) {
        if (patch.slot_address == nullptr) continue;
        const uintptr_t addr = reinterpret_cast<uintptr_t>(patch.slot_address);
        if (!set_page_writable(addr, true)) {
            ok = false;
            continue;
        }
        if (patch.slot_type == VtableSlotType::kRelative32) {
            const uintptr_t orig_addr = reinterpret_cast<uintptr_t>(patch.original_function);
            const int32_t old_rel =
                static_cast<int32_t>(static_cast<intptr_t>(orig_addr) - static_cast<intptr_t>(addr));
            *reinterpret_cast<int32_t *>(patch.slot_address) = old_rel;
        } else {
            *reinterpret_cast<void **>(patch.slot_address) = patch.original_function;
        }
        set_page_writable(addr, false);
    }
    return ok;
}

} // namespace arirang
