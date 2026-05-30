#include "inline_hook.hpp"

#include "logging.hpp"

#include <cstdint>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>

namespace arirang {
namespace {

constexpr size_t kHookSize = 16; // 4 instructions: LDR x16,#8 ; BR x16 ; <8-byte addr>

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

void write_absolute_jump(uint32_t *dst, void *destination) {
    // LDR x16, [pc, #8] ; BR x16 ; <abs addr>
    dst[0] = 0x58000050u;
    dst[1] = 0xd61f0200u;
    std::memcpy(&dst[2], &destination, sizeof(destination));
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

    void *tramp = mmap(nullptr, kHookSize + 16, PROT_READ | PROT_WRITE | PROT_EXEC,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (tramp == MAP_FAILED) {
        log_warn("inline_hook: mmap failed for trampoline");
        return false;
    }

    std::memcpy(tramp, target, kHookSize);
    auto *tail = reinterpret_cast<uint32_t *>(reinterpret_cast<uint8_t *>(tramp) + kHookSize);
    write_absolute_jump(tail, reinterpret_cast<uint8_t *>(target) + kHookSize);
    __builtin___clear_cache(static_cast<char *>(tramp),
                            static_cast<char *>(tramp) + kHookSize + 16);

    if (!unprotect_page(target, kHookSize)) {
        log_warn("inline_hook: mprotect rwx failed");
        munmap(tramp, kHookSize + 16);
        return false;
    }
    write_absolute_jump(target_words, handler);
    __builtin___clear_cache(static_cast<char *>(target),
                            static_cast<char *>(target) + kHookSize);
    reprotect_page(target, kHookSize);

    *out_trampoline = tramp;
    return true;
}

} // namespace arirang
