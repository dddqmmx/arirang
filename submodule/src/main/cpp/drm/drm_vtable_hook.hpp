// drm_vtable_hook.hpp
// Vtable-based DRM hook installation API.

#pragma once

namespace drm_vtable {

// Install vtable-based hooks in the given library.
// Returns true if at least one slot was successfully patched.
bool install_hook_in_library(const char *library_path);

// Poll known candidate libraries for appearance, then install hooks.
// Blocks until a hook is installed or 120 attempts (60s) have elapsed.
// Returns true only when a hook was installed. Callers use this result to
// decide whether the riskier inline fallback is necessary.
bool poll_libraries();

} // namespace drm_vtable
