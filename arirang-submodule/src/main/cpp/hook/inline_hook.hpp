#pragma once

#include <cstddef>

namespace arirang {

// Overwrites the first 16 bytes of `target` with an absolute jump to `handler`
// and returns, via `out_trampoline`, a callable copy of the original prologue
// that tail-jumps back into the function. The prologue is rejected (returns
// false) if any of its first four instructions is PC-relative, since relocating
// those is unsupported.
//
// Lifetime: the hook is permanent for the life of the process. There is no
// uninstall — the RWX/RX trampoline page is intentionally never unmapped and
// the original prologue is never restored. (vtable_hook does support uninstall;
// the asymmetry is deliberate because code patches here are install-once.)
//
// The trampoline replays the original prologue and then jumps via x16/x17 (the
// procedure-call scratch registers); a hooked function whose first instructions
// leave a live value in x16 at the patch boundary would not round-trip, but a
// well-formed prologue never does.
bool inline_hook_install(void *target, void *handler, void **out_trampoline);

// Patches a single B instruction at `target` to branch to a nearby
// trampoline stub that loads the full `handler` address and jumps.
// Suitable for functions too small for the standard 16-byte inline hook.
// The hook must replicate the original function's return value.
bool inline_hook_branch(void *target, void *handler);

} // namespace arirang
