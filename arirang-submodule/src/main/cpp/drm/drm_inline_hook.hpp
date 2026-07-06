// drm_inline_hook.hpp
// Instruction-level inline hook fallback API.

#pragma once

namespace drm_inline {

// Install inline hooks in the given library as a fallback when vtable
// hooking fails. Logs a downgrade warning.
// Returns true if at least one function was successfully patched.
bool install_hook_in_library(const char *library_path);

} // namespace drm_inline
