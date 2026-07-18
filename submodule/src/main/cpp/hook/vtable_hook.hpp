#pragma once

#include <cstddef>
#include <vector>

namespace arirang {

enum class VtableSlotType {
    kAbsolute64,   // 8-byte absolute function pointer.
    kRelative32,   // 4-byte signed offset from the slot to the function.
};

struct VtablePatch {
    // Address of the vtable / method-table slot that was rewritten.
    void *slot_address = nullptr;
    // Original function pointer stored in that slot (already resolved to an
    // absolute address for relative slots).
    void *original_function = nullptr;
    // Hook destination installed by this patch. Uninstall verifies the slot
    // still resolves here before restoring, so a later hook is never clobbered.
    void *replacement_function = nullptr;
    // Size and encoding of the slot.
    VtableSlotType slot_type = VtableSlotType::kAbsolute64;
    size_t slot_size = sizeof(void *);
    // Exact protection bits observed before installation.
    int original_protection = 0;
};

// Install a hook by rewriting function pointers in data tables (vtables,
// GOT/PLT, HIDL method tables, etc.) instead of patching machine code.
//
// For every symbol in `library_path` whose mangled name contains
// `symbol_substring`, the implementation scans all readable, non-executable
// pages of that library for aligned 8-byte slots whose value equals the
// symbol's address. If no absolute slot is found and `try_relative` is true,
// the scanner also looks for 4-byte signed relative offsets (Itanium relative
// vtables), filtering out matches that look like CFI data in .eh_frame.
//
// No hardcoded vtable names or instruction-specific special cases are
// required.
//
// Returns true if at least one slot was rewritten. On success the caller
// receives the list of patches so it can call the original function(s) or
// restore them later.
bool vtable_hook_install(const char *library_path,
                          const char *symbol_substring,
                          void *hook,
                          std::vector<VtablePatch> *out_patches,
                          bool try_relative = true);

// Restore the slots recorded in `patches` to their original values.
bool vtable_hook_uninstall(const std::vector<VtablePatch> &patches);

} // namespace arirang
