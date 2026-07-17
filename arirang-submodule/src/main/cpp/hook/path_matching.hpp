#pragma once

#include <cstring>

namespace arirang {

// Callers usually pass a full vendor path, but the /proc/linker side
// sometimes only keeps the basename. Match the full path first, then fall
// back to basename-only comparison.
inline bool path_matches(const char *full, const char *needle) {
    if (full == nullptr || needle == nullptr) return false;
    if (std::strcmp(full, needle) == 0) return true;

    const char *full_base = std::strrchr(full, '/');
    const char *needle_base = std::strrchr(needle, '/');
    full_base = full_base ? full_base + 1 : full;
    needle_base = needle_base ? needle_base + 1 : needle;
    return std::strcmp(full_base, needle_base) == 0;
}

} // namespace arirang
