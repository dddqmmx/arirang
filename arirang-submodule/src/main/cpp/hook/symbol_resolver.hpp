#pragma once

#include <string>
#include <vector>

namespace arirang {

struct ResolvedSymbol {
    std::string name;
    void *address = nullptr;
    void *library_base = nullptr;
};

// Open `library_path` with dlopen, iterate its dynamic symbol table, and return
// every symbol whose mangled name contains `substring`. Returns empty if the
// library can't be loaded or no symbols match.
std::vector<ResolvedSymbol> resolve_symbols_by_substring(const char *library_path,
                                                         const char *substring);

} // namespace arirang
