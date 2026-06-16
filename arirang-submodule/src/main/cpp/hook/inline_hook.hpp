#pragma once

#include <cstddef>

namespace arirang {

bool inline_hook_install(void *target, void *handler, void **out_trampoline);

// Patches a single B instruction at `target` to branch to a nearby
// trampoline stub that loads the full `handler` address and jumps.
// Suitable for functions too small for the standard 16-byte inline hook.
// The hook must replicate the original function's return value.
bool inline_hook_branch(void *target, void *handler);

} // namespace arirang
