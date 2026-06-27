#include "inline_hook.hpp"

#include "logging.hpp"

#include <cstdint>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>

#ifndef MAP_FIXED_NOREPLACE
// Linux 4.17+. On older kernels the bit is ignored and mmap falls back to
// treating the address as a hint, which is exactly the behaviour we want.
#define MAP_FIXED_NOREPLACE 0x100000
#endif

namespace arirang {
namespace {

constexpr size_t kHookSize = 16; // 4 instructions: LDR x16,#8 ; BR x16 ; <8-byte addr>
constexpr size_t kTrampolineSize = kHookSize + 16; // saved prologue + jump back

constexpr uint32_t kLdrX16Pc8 = 0x58000050u; // LDR x16, [pc, #8]
constexpr uint32_t kBrX16 = 0xd61f0200u; // BR x16

bool instruction_is_pc_relative(uint32_t instr) {
    // ADR  : 0_immlo[2]_10000_immhi[19]_Rd
    // ADRP : 1_immlo[2]_10000_immhi[19]_Rd
    if ((instr & 0x1F000000u) == 0x10000000u) return true;
    // B            : 000101_imm26
    // BL           : 100101_imm26
    if ((instr & 0x7C000000u) == 0x14000000u) return true;
    // B.cond       : 01010100_imm19_0_cond
    if ((instr & 0xFF000010u) == 0x54000000u) return true;
    // CBZ/CBNZ     : sf_011010_op_imm19_Rt
    if ((instr & 0x7E000000u) == 0x34000000u) return true;
    // TBZ/TBNZ     : b5_011011_op_b40_imm14_Rt
    if ((instr & 0x7E000000u) == 0x36000000u) return true;
    // LDR (literal): opc_011_V_00_imm19_Rt  (V=0, opc in {00,01,10})
    if ((instr & 0x3B000000u) == 0x18000000u) return true;
    return false;
}

bool unprotect_page(void *addr, size_t length) {
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return false;
    auto base = reinterpret_cast<uintptr_t>(addr);
    uintptr_t aligned = base & ~static_cast<uintptr_t>(page_size - 1);
    size_t span = (base + length) - aligned;
    return mprotect(reinterpret_cast<void *>(aligned), span,
                    PROT_READ | PROT_WRITE | PROT_EXEC) == 0;
}

bool reprotect_page(void *addr, size_t length) {
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return false;
    auto base = reinterpret_cast<uintptr_t>(addr);
    uintptr_t aligned = base & ~static_cast<uintptr_t>(page_size - 1);
    size_t span = (base + length) - aligned;
    return mprotect(reinterpret_cast<void *>(aligned), span,
                    PROT_READ | PROT_EXEC) == 0;
}

// Build "LDR x16,[pc,#8] ; BR x16 ; <abs addr>" into freshly allocated, not yet
// reachable memory. Store order is irrelevant because nothing can branch here
// until the caller publishes the stub.
void build_absolute_jump(uint32_t *dst, void *destination) {
    dst[0] = kLdrX16Pc8;
    dst[1] = kBrX16;
    std::memcpy(&dst[2], &destination, sizeof(destination));
}

// Patch the same 16-byte absolute jump over a LIVE function prologue, ordering
// the stores so the entry instruction (word 0) becomes visible LAST. A thread
// that *enters* the function during patching therefore observes either the
// original first instruction or the finished jump — never a half-written LDR
// pointing at an as-yet-unwritten address word.
//
// A thread already suspended *inside* the 16-byte prologue when the patch lands
// cannot be protected without stopping every thread; that residual race is
// inherent to multi-instruction inline hooking and is accepted here. Callers
// that cannot tolerate it should use inline_hook_branch (single-instruction).
void publish_live_absolute_jump(uint32_t *dst, void *destination) {
    const uintptr_t addr = reinterpret_cast<uintptr_t>(destination);
    auto *words = reinterpret_cast<volatile uint32_t *>(dst);
    words[2] = static_cast<uint32_t>(addr);        // address low  (unreachable
    words[3] = static_cast<uint32_t>(addr >> 32);  // address high   until word0 runs)
    words[1] = kBrX16;
    // Publish words 1..3 before the entry word so no thread can observe a new
    // LDR over a stale address.
    __atomic_thread_fence(__ATOMIC_RELEASE);
    __atomic_store_n(&dst[0], kLdrX16Pc8, __ATOMIC_RELEASE);
}

} // namespace

bool inline_hook_install(void *target, void *handler, void **out_trampoline) {
    if (target == nullptr || handler == nullptr || out_trampoline == nullptr) {
        return false;
    }

    auto *target_words = reinterpret_cast<uint32_t *>(target);
    for (int i = 0; i < 4; ++i) {
        if (instruction_is_pc_relative(target_words[i])) {
            log_warn("inline_hook: refusing to hook; PC-relative instruction in prologue");
            return false;
        }
    }

    // Map the trampoline RW, fill it, then flip to RX. Never hold an RWX
    // mapping, which W^X / SELinux execmem policies routinely reject.
    void *tramp = mmap(nullptr, kTrampolineSize, PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (tramp == MAP_FAILED) {
        log_warn("inline_hook: mmap failed for trampoline");
        return false;
    }

    std::memcpy(tramp, target, kHookSize);
    auto *tail = reinterpret_cast<uint32_t *>(reinterpret_cast<uint8_t *>(tramp) + kHookSize);
    build_absolute_jump(tail, reinterpret_cast<uint8_t *>(target) + kHookSize);

    if (mprotect(tramp, kTrampolineSize, PROT_READ | PROT_EXEC) != 0) {
        log_warn("inline_hook: mprotect RX failed for trampoline");
        munmap(tramp, kTrampolineSize);
        return false;
    }
    __builtin___clear_cache(static_cast<char *>(tramp),
                            static_cast<char *>(tramp) + kTrampolineSize);

    // The target stays R+X (executable) while we add write permission: dropping
    // EXEC on a page other threads may be executing would fault them.
    if (!unprotect_page(target, kHookSize)) {
        log_warn("inline_hook: mprotect rwx failed");
        munmap(tramp, kTrampolineSize);
        return false;
    }
    publish_live_absolute_jump(target_words, handler);
    __builtin___clear_cache(static_cast<char *>(target),
                            static_cast<char *>(target) + kHookSize);
    if (!reprotect_page(target, kHookSize)) {
        log_warn("inline_hook: failed to restore R+X on target page; left writable");
    }

    *out_trampoline = tramp;
    return true;
}

bool inline_hook_branch(void *target, void *handler) {
    if (target == nullptr || handler == nullptr) return false;

    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return false;

    const uintptr_t target_addr = reinterpret_cast<uintptr_t>(target);

    // Allocate an executable page within ±128 MB of target for the trampoline.
    void *tramp = nullptr;
    for (uintptr_t offset = static_cast<uintptr_t>(page_size);
         offset < 120u * 1024u * 1024u;
         offset += static_cast<uintptr_t>(page_size)) {
        for (int dir : {1, -1}) {
            const uintptr_t hint = (target_addr + dir * offset) &
                                   ~static_cast<uintptr_t>(page_size - 1);
            // MAP_FIXED_NOREPLACE places the page exactly at `hint` (or fails)
            // on modern kernels, so we usually succeed on the first in-range
            // hint instead of repeatedly mapping and unmapping far addresses.
            void *result = mmap(reinterpret_cast<void *>(hint), static_cast<size_t>(page_size),
                                PROT_READ | PROT_WRITE,
                                MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
            if (result == MAP_FAILED) continue;
            const intptr_t diff = reinterpret_cast<intptr_t>(result) -
                                  static_cast<intptr_t>(target_addr);
            if (diff >= -128LL * 1024 * 1024 && diff <= 128LL * 1024 * 1024 - 4) {
                tramp = result;
                break;
            }
            munmap(result, static_cast<size_t>(page_size));
        }
        if (tramp != nullptr) break;
    }
    if (tramp == nullptr) {
        log_warn("inline_hook_branch: failed to allocate nearby trampoline");
        return false;
    }

    // Trampoline: LDR x16, [pc, #8] ; BR x16 ; <8-byte handler address>.
    // Filled while RW, then flipped to RX (never RWX).
    auto *tramp_words = reinterpret_cast<uint32_t *>(tramp);
    build_absolute_jump(tramp_words, handler);
    if (mprotect(tramp, static_cast<size_t>(page_size), PROT_READ | PROT_EXEC) != 0) {
        log_warn("inline_hook_branch: mprotect RX failed for trampoline");
        munmap(tramp, static_cast<size_t>(page_size));
        return false;
    }
    __builtin___clear_cache(static_cast<char *>(tramp),
                            static_cast<char *>(tramp) + 16);

    // Patch target: B trampoline (signed 26-bit offset in words)
    const intptr_t branch_offset = reinterpret_cast<intptr_t>(tramp) -
                                   static_cast<intptr_t>(target_addr);
    const int32_t imm26 = static_cast<int32_t>(branch_offset / 4);
    if (imm26 < -33554432 || imm26 > 33554431) {
        log_warn("inline_hook_branch: trampoline out of B range");
        munmap(tramp, static_cast<size_t>(page_size));
        return false;
    }

    const uint32_t b_instr = 0x14000000u | (static_cast<uint32_t>(imm26) & 0x03FFFFFFu);

    if (!unprotect_page(target, 4)) {
        log_warn("inline_hook_branch: mprotect failed");
        munmap(tramp, static_cast<size_t>(page_size));
        return false;
    }
    // Single aligned 32-bit store: a thread fetching the entry word sees either
    // the original instruction or the complete branch, never a torn value.
    __atomic_store_n(reinterpret_cast<uint32_t *>(target), b_instr, __ATOMIC_RELEASE);
    __builtin___clear_cache(static_cast<char *>(target),
                            static_cast<char *>(target) + 4);
    if (!reprotect_page(target, 4)) {
        log_warn("inline_hook_branch: failed to restore R+X on target page; left writable");
    }

    log_info(std::string("inline_hook_branch: patched target @ ") +
             std::to_string(target_addr) + " -> trampoline @ " +
             std::to_string(reinterpret_cast<uintptr_t>(tramp)));
    return true;
}

} // namespace arirang
