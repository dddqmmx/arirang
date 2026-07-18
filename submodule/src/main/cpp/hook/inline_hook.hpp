#pragma once

#include <cstddef>

namespace arirang {

// Atomically overwrites one instruction at `target` with a branch to a nearby
// relay and returns, via `out_trampoline`, a callable replay. A leading BTI
// and/or PACIASP/PACIBSP landing sequence is preserved; the relay authenticates
// a preserved PAC before entering the independently PAC-protected handler.
// PC-relative displaced instructions are rejected because relocating them is
// unsupported.
//
// Lifetime: the hook is permanent for the life of the process. There is no
// uninstall — the RWX/RX trampoline page is intentionally never unmapped and
// the original prologue is never restored. (vtable_hook does support uninstall;
// the asymmetry is deliberate because code patches here are install-once.)
//
// The trampoline jumps via x16, a procedure-call scratch register. A
// well-formed function entry cannot rely on an incoming x16 value.
bool inline_hook_install(void *target, void *handler, void **out_trampoline);

// Legacy 16-byte absolute-jump implementation. It preserves compatibility for
// callers that cannot allocate a nearby relay, but the live multi-instruction
// patch cannot be made atomic. Do not use this in multithreaded daemons.
bool inline_hook_install_absolute(void *target, void *handler, void **out_trampoline);

// Patches a single B instruction at `target` to branch to a nearby
// trampoline stub that loads the full `handler` address and jumps.
// Suitable for functions too small for the standard 16-byte inline hook.
// The hook must replicate the original function's return value.
bool inline_hook_branch(void *target, void *handler);

// Branch-hook variant that also returns a callable original trampoline.
bool inline_hook_branch(void *target, void *handler, void **out_trampoline);

} // namespace arirang
