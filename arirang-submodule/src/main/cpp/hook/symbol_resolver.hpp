#pragma once

#include <string>
#include <vector>

namespace arirang {

struct ResolvedSymbol {
    std::string name;
    void *address = nullptr;
    void *library_base = nullptr;
};

// Walk the already-loaded shared objects via dl_iterate_phdr, find the one
// matching `library_path` (by full path or basename), iterate its dynamic
// symbol table, and return every STT_FUNC/STT_GNU_IFUNC symbol whose mangled
// name contains `substring`.
//
// NOTE: this does NOT dlopen the library — it only resolves objects already
// mapped into the process. Callers must dlopen (e.g. RTLD_NOLOAD to probe)
// beforehand; an unloaded library yields an empty result.
std::vector<ResolvedSymbol> resolve_symbols_by_substring(const char *library_path,
                                                         const char *substring);

} // namespace arirang
