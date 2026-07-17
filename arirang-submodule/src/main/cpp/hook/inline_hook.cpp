#include "inline_hook.hpp"

#include "logging.hpp"

#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <sys/mman.h>
#include <unistd.h>

#ifndef MAP_FIXED_NOREPLACE
// Linux 4.17 之后提供 MAP_FIXED_NOREPLACE。旧内核会忽略这个 bit，
// mmap 会把传入地址当作普通 hint；后续代码会再次校验返回地址是否在
// AArch64 B 指令可达范围内，因此旧内核上也能安全降级。
#define MAP_FIXED_NOREPLACE 0x100000
#endif

namespace arirang {
namespace {

// 本文件有意把每个步骤拆成小函数，并通过早返回处理错误路径，避免单个
// 函数内出现超过三层的控制流嵌套。这样修改底层机器码时更容易审计。

constexpr size_t kHookSize = 16; // 4 条指令空间：LDR x16,#8 ; BR x16 ; 8 字节地址。
constexpr size_t kTrampolineSize = kHookSize + 16; // 原始序言 + 跳回原函数的绝对跳转。

constexpr uint32_t kLdrX16Pc8 = 0x58000050u; // LDR x16, [pc, #8]
constexpr uint32_t kBrX16 = 0xd61f0200u; // BR x16
constexpr uint32_t kPaciasp = 0xd503233fu;
constexpr uint32_t kPacibsp = 0xd503237fu;
constexpr uint32_t kAutiasp = 0xd50323bfu;
constexpr uint32_t kAutibsp = 0xd50323ffu;
constexpr intptr_t kBranchRange = 128LL * 1024 * 1024; // AArch64 B imm26 的字节范围。
constexpr size_t kBranchDispatchSize = 32;
constexpr size_t kBranchRelaySize = 64;

std::mutex g_install_mutex;

bool instruction_is_pc_relative(uint32_t instr) {
    // ADR / ADRP 会根据当前 PC 计算地址。复制到 trampoline 后 PC 改变，
    // 立即数不再指向原位置，因此当前实现拒绝搬运这类指令。
    if ((instr & 0x1F000000u) == 0x10000000u) return true;

    // B / BL、条件跳转、CBZ/CBNZ、TBZ/TBNZ 都携带 PC 相对偏移。
    // 如果它们出现在被覆盖的 16 字节序言内，直接复制会改变控制流。
    if ((instr & 0x7C000000u) == 0x14000000u) return true;
    if ((instr & 0xFF000010u) == 0x54000000u) return true;
    if ((instr & 0x7E000000u) == 0x34000000u) return true;
    if ((instr & 0x7E000000u) == 0x36000000u) return true;

    // LDR literal 从 PC 附近读取常量池。搬到 trampoline 后常量池地址也会错。
    if ((instr & 0x3B000000u) == 0x18000000u) return true;

    return false;
}

bool instruction_is_bti(uint32_t instr) {
    // BTI, BTI c, BTI j and BTI jc differ only in the guarded branch type.
    return (instr & 0xffffff1fu) == 0xd503241fu;
}

bool instruction_is_pac_sp(uint32_t instr) {
    return instr == kPaciasp || instr == kPacibsp;
}

struct BranchPatchSite {
    size_t offset = 0;
    uint32_t pac_instruction = 0;
};

BranchPatchSite select_branch_patch_site(const uint32_t *target_words) {
    BranchPatchSite site;
    if (instruction_is_bti(target_words[0])) site.offset = sizeof(uint32_t);
    if (instruction_is_pac_sp(target_words[site.offset / sizeof(uint32_t)])) {
        site.pac_instruction = target_words[site.offset / sizeof(uint32_t)];
        site.offset += sizeof(uint32_t);
    }
    return site;
}

bool prologue_can_be_relocated(const uint32_t *target_words) {
    for (int i = 0; i < 4; ++i) {
        if (!instruction_is_pc_relative(target_words[i])) continue;

        log_warn("inline_hook: refusing to hook; PC-relative instruction in prologue");
        return false;
    }

    return true;
}

bool page_span_for(void *addr, size_t length, uintptr_t *aligned, size_t *span) {
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0 || length == 0) return false;

    const auto base = reinterpret_cast<uintptr_t>(addr);
    if (base > UINTPTR_MAX - length) return false;
    *aligned = base & ~static_cast<uintptr_t>(page_size - 1);
    *span = (base + length) - *aligned;
    return true;
}

bool set_page_protection(void *addr, size_t length, int protection) {
    uintptr_t aligned = 0;
    size_t span = 0;
    if (!page_span_for(addr, length, &aligned, &span)) return false;

    return mprotect(reinterpret_cast<void *>(aligned), span, protection) == 0;
}

bool unprotect_page(void *addr, size_t length) {
    // 目标页在写入期间仍保持可执行。如果临时去掉 EXEC，其他线程正好执行
    // 同一页代码时会直接崩溃；因此这里使用 R+W+X 作为很短的补丁窗口。
    return set_page_protection(addr, length, PROT_READ | PROT_WRITE | PROT_EXEC);
}

bool reprotect_page(void *addr, size_t length) {
    return set_page_protection(addr, length, PROT_READ | PROT_EXEC);
}

// 在尚未发布给任何线程的内存中构造绝对跳转。写入顺序不重要，因为此时
// 没有控制流能够到达这段 trampoline。
void build_absolute_jump(uint32_t *dst, void *destination) {
    dst[0] = kLdrX16Pc8;
    dst[1] = kBrX16;
    std::memcpy(&dst[2], &destination, sizeof(destination));
}

// 在正在被执行的函数入口上发布 16 字节绝对跳转。地址低/高位和 BR 先写，
// 最后用 release store 写入第一条 LDR。这样新进入函数的线程只会看到完整
// 旧序言或完整新跳转，不会看到“新 LDR + 旧地址”的半成品状态。
void publish_live_absolute_jump(uint32_t *dst, void *destination) {
    const uintptr_t addr = reinterpret_cast<uintptr_t>(destination);
    auto *words = reinterpret_cast<volatile uint32_t *>(dst);
    words[2] = static_cast<uint32_t>(addr);
    words[3] = static_cast<uint32_t>(addr >> 32);
    words[1] = kBrX16;
    __atomic_thread_fence(__ATOMIC_RELEASE);
    __atomic_store_n(&dst[0], kLdrX16Pc8, __ATOMIC_RELEASE);
}

// 回滚路径和 publish_live_absolute_jump 一样发布到正在被执行的入口，
// 因此必须遵守同样的发布顺序：先写第二条指令 + 地址常量，再用 release
// store 写回原始第一条指令。直接 memcpy 会让另一个线程有机会看到
// “新 LDR 目标字节 + 旧地址常量”的半成品序言。
void rollback_live_prologue(uint32_t *dst, const uint8_t *original) {
    uint32_t original_words[4];
    std::memcpy(original_words, original, kHookSize);
    auto *words = reinterpret_cast<volatile uint32_t *>(dst);
    words[1] = original_words[1];
    words[2] = original_words[2];
    words[3] = original_words[3];
    __atomic_thread_fence(__ATOMIC_RELEASE);
    __atomic_store_n(&dst[0], original_words[0], __ATOMIC_RELEASE);
}

void clear_instruction_cache(void *start, size_t length) {
    auto *begin = static_cast<char *>(start);
    __builtin___clear_cache(begin, begin + length);
}

bool encode_branch_instruction(uintptr_t target_addr, void *destination, uint32_t *out);

void *allocate_rw_trampoline(size_t length, const char *tag) {
    void *mapping = mmap(nullptr, length, PROT_READ | PROT_WRITE,
                         MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mapping != MAP_FAILED) return mapping;

    log_warn(std::string(tag) + ": mmap failed for trampoline");
    return nullptr;
}

bool make_trampoline_rx(void *trampoline, size_t length, const char *tag) {
    if (mprotect(trampoline, length, PROT_READ | PROT_EXEC) == 0) {
        clear_instruction_cache(trampoline, length);
        return true;
    }

    log_warn(std::string(tag) + ": mprotect RX failed for trampoline");
    return false;
}

bool prepare_install_trampoline(void *target, void *trampoline) {
    // trampoline 先复制原始 16 字节序言，再追加一个绝对跳转回 target+16。
    // 调用方得到的 out_trampoline 可以作为“原函数入口”继续执行。
    std::memcpy(trampoline, target, kHookSize);
    auto *tail = reinterpret_cast<uint32_t *>(
        reinterpret_cast<uint8_t *>(trampoline) + kHookSize);
    build_absolute_jump(tail, reinterpret_cast<uint8_t *>(target) + kHookSize);

    return make_trampoline_rx(trampoline, kTrampolineSize, "inline_hook");
}

bool patch_target_with_absolute_jump(void *target,
                                     void *handler,
                                     const uint8_t *original) {
    if (!unprotect_page(target, kHookSize)) {
        log_warn("inline_hook: mprotect rwx failed");
        return false;
    }

    publish_live_absolute_jump(reinterpret_cast<uint32_t *>(target), handler);
    clear_instruction_cache(target, kHookSize);
    if (!reprotect_page(target, kHookSize)) {
        rollback_live_prologue(reinterpret_cast<uint32_t *>(target), original);
        clear_instruction_cache(target, kHookSize);
        reprotect_page(target, kHookSize);
        log_warn("inline_hook: protection restore failed; patch rolled back");
        return false;
    }

    return true;
}

bool branch_offset_in_range(intptr_t byte_offset) {
    return byte_offset >= -kBranchRange && byte_offset <= kBranchRange - 4;
}

bool address_distance(uintptr_t from, uintptr_t to, intptr_t *out) {
    if (to >= from) {
        const uintptr_t distance = to - from;
        if (distance > static_cast<uintptr_t>(INTPTR_MAX)) return false;
        *out = static_cast<intptr_t>(distance);
        return true;
    }

    const uintptr_t distance = from - to;
    if (distance > static_cast<uintptr_t>(INTPTR_MAX)) return false;
    *out = -static_cast<intptr_t>(distance);
    return true;
}

bool branch_target_in_range(uintptr_t from, uintptr_t to) {
    intptr_t offset = 0;
    return address_distance(from, to, &offset) && branch_offset_in_range(offset);
}

bool page_hint_near_target(uintptr_t target_addr,
                           uintptr_t offset,
                           int direction,
                           size_t page_size,
                           uintptr_t *hint) {
    if (direction < 0 && target_addr < offset) return false;
    if (direction > 0 && UINTPTR_MAX - target_addr < offset) return false;

    const uintptr_t raw = direction > 0 ? target_addr + offset : target_addr - offset;
    *hint = raw & ~static_cast<uintptr_t>(page_size - 1);
    return *hint != 0;
}

void *try_map_nearby_page(uintptr_t target_addr,
                          uintptr_t offset,
                          int direction,
                          size_t page_size) {
    uintptr_t hint = 0;
    if (!page_hint_near_target(target_addr, offset, direction, page_size, &hint)) {
        return nullptr;
    }

    void *result = mmap(reinterpret_cast<void *>(hint), page_size, PROT_READ | PROT_WRITE,
                        MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
    if (result == MAP_FAILED) return nullptr;

    const auto mapped_addr = reinterpret_cast<uintptr_t>(result);
    if (branch_target_in_range(target_addr, mapped_addr)) return result;

    munmap(result, page_size);
    return nullptr;
}

void *allocate_nearby_trampoline(uintptr_t target_addr, size_t page_size) {
    const int directions[] = {1, -1};

    // 单条 B 指令只能跳转 +/-128MB。按页向两侧探测，找到一个足够近的
    // RW 页面后再把它变成 RX trampoline。
    for (uintptr_t offset = page_size; offset < 120u * 1024u * 1024u; offset += page_size) {
        for (int direction : directions) {
            void *result = try_map_nearby_page(target_addr, offset, direction, page_size);
            if (result != nullptr) return result;
        }
    }

    return nullptr;
}

bool encode_branch_instruction(uintptr_t target_addr, void *trampoline, uint32_t *out) {
    intptr_t branch_offset = 0;
    if (!address_distance(target_addr, reinterpret_cast<uintptr_t>(trampoline),
                          &branch_offset) ||
        !branch_offset_in_range(branch_offset) || (branch_offset & 3) != 0) {
        return false;
    }

    const int32_t imm26 = static_cast<int32_t>(branch_offset / 4);
    *out = 0x14000000u | (static_cast<uint32_t>(imm26) & 0x03FFFFFFu);
    return true;
}

bool patch_target_with_branch(void *target,
                              uint32_t branch_instruction,
                              uint32_t original_instruction) {
    if (!unprotect_page(target, 4)) {
        log_warn("inline_hook_branch: mprotect failed");
        return false;
    }

    // 入口只写一条对齐的 32-bit B 指令。其他线程取指时看到的是旧指令或
    // 完整新指令，不会看到撕裂的一半机器码。
    auto *target_word = reinterpret_cast<uint32_t *>(target);
    uint32_t expected = original_instruction;
    if (!__atomic_compare_exchange_n(target_word, &expected, branch_instruction, false,
                                     __ATOMIC_RELEASE, __ATOMIC_ACQUIRE)) {
        reprotect_page(target, 4);
        log_warn("inline_hook_branch: target changed before patch publication");
        return false;
    }
    clear_instruction_cache(target, 4);
    if (!reprotect_page(target, 4)) {
        expected = branch_instruction;
        __atomic_compare_exchange_n(target_word, &expected, original_instruction, false,
                                    __ATOMIC_RELEASE, __ATOMIC_ACQUIRE);
        clear_instruction_cache(target, 4);
        reprotect_page(target, 4);
        log_warn("inline_hook_branch: protection restore failed; patch rolled back");
        return false;
    }

    return true;
}

bool prepare_branch_trampoline(void *trampoline,
                               size_t page_size,
                               void *target,
                               const BranchPatchSite &site,
                               void *handler,
                               void **original_entry) {
    // 近 trampoline 仍然使用 LDR/BR 加 64-bit 绝对地址，因此 handler 可以在
    // 任意地址；只有 target -> trampoline 这一跳受 B 指令范围限制。
    auto *dispatch = static_cast<uint8_t *>(trampoline);
    if (site.pac_instruction == kPaciasp) {
        std::memcpy(dispatch, &kAutiasp, sizeof(kAutiasp));
        build_absolute_jump(reinterpret_cast<uint32_t *>(dispatch + sizeof(uint32_t)), handler);
    } else if (site.pac_instruction == kPacibsp) {
        std::memcpy(dispatch, &kAutibsp, sizeof(kAutibsp));
        build_absolute_jump(reinterpret_cast<uint32_t *>(dispatch + sizeof(uint32_t)), handler);
    } else {
        build_absolute_jump(reinterpret_cast<uint32_t *>(dispatch), handler);
    }

    if (original_entry != nullptr) {
        auto *original = static_cast<uint8_t *>(trampoline) + kBranchDispatchSize;
        if (site.offset != 0) std::memcpy(original, target, site.offset);
        std::memcpy(original + site.offset, static_cast<uint8_t *>(target) + site.offset,
                    sizeof(uint32_t));
        const size_t branch_offset = site.offset + sizeof(uint32_t);
        uint32_t return_branch = 0;
        if (!encode_branch_instruction(
                reinterpret_cast<uintptr_t>(original + branch_offset),
                static_cast<uint8_t *>(target) + branch_offset, &return_branch)) {
            log_warn("inline_hook_branch: original trampoline return is out of range");
            return false;
        }
        std::memcpy(original + branch_offset, &return_branch, sizeof(return_branch));
        *original_entry = original;
    }
    return make_trampoline_rx(trampoline, page_size, "inline_hook_branch");
}

bool install_branch_hook(void *target, void *handler, void **out_trampoline) {
    if (target == nullptr || handler == nullptr ||
        (reinterpret_cast<uintptr_t>(target) & 3u) != 0) {
        return false;
    }

    const auto *target_words = reinterpret_cast<const uint32_t *>(target);
    const BranchPatchSite site = select_branch_patch_site(target_words);
    auto *patch_target = static_cast<uint8_t *>(target) + site.offset;
    const uint32_t original_instruction = __atomic_load_n(
        reinterpret_cast<uint32_t *>(patch_target), __ATOMIC_ACQUIRE);
    if (out_trampoline != nullptr && instruction_is_pc_relative(original_instruction)) {
        log_warn("inline_hook_branch: first instruction cannot be replayed safely");
        return false;
    }

    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0 || static_cast<size_t>(page_size) < kBranchRelaySize) return false;

    const uintptr_t target_addr = reinterpret_cast<uintptr_t>(patch_target);
    void *relay = allocate_nearby_trampoline(target_addr, static_cast<size_t>(page_size));
    if (relay == nullptr) {
        log_warn("inline_hook_branch: failed to allocate nearby trampoline");
        return false;
    }

    void *original_entry = nullptr;
    if (!prepare_branch_trampoline(relay, static_cast<size_t>(page_size), target, site, handler,
                                   out_trampoline == nullptr ? nullptr : &original_entry)) {
        munmap(relay, static_cast<size_t>(page_size));
        return false;
    }

    uint32_t branch_instruction = 0;
    if (!encode_branch_instruction(target_addr, relay, &branch_instruction)) {
        munmap(relay, static_cast<size_t>(page_size));
        return false;
    }

    void *previous_original = nullptr;
    if (out_trampoline != nullptr) {
        previous_original = __atomic_load_n(out_trampoline, __ATOMIC_ACQUIRE);
        __atomic_store_n(out_trampoline, original_entry, __ATOMIC_RELEASE);
    }
    if (!patch_target_with_branch(patch_target, branch_instruction, original_instruction)) {
        if (out_trampoline != nullptr) {
            __atomic_store_n(out_trampoline, previous_original, __ATOMIC_RELEASE);
        }
        munmap(relay, static_cast<size_t>(page_size));
        return false;
    }

    log_info("inline_hook_branch: installed atomic relay");
    return true;
}

} // namespace

bool inline_hook_install(void *target, void *handler, void **out_trampoline) {
    std::lock_guard<std::mutex> lock(g_install_mutex);
    if (out_trampoline == nullptr) return false;
    return install_branch_hook(target, handler, out_trampoline);
}

bool inline_hook_install_absolute(void *target, void *handler, void **out_trampoline) {
    std::lock_guard<std::mutex> lock(g_install_mutex);
    if (target == nullptr || handler == nullptr || out_trampoline == nullptr) {
        return false;
    }
    if ((reinterpret_cast<uintptr_t>(target) & 3u) != 0) return false;

    auto *target_words = reinterpret_cast<uint32_t *>(target);
    if (!prologue_can_be_relocated(target_words)) return false;

    void *trampoline = allocate_rw_trampoline(kTrampolineSize, "inline_hook");
    if (trampoline == nullptr) return false;

    if (!prepare_install_trampoline(target, trampoline)) {
        munmap(trampoline, kTrampolineSize);
        return false;
    }

    void *previous_original = __atomic_load_n(out_trampoline, __ATOMIC_ACQUIRE);
    __atomic_store_n(out_trampoline, trampoline, __ATOMIC_RELEASE);
    if (!patch_target_with_absolute_jump(
            target, handler, static_cast<const uint8_t *>(trampoline))) {
        __atomic_store_n(out_trampoline, previous_original, __ATOMIC_RELEASE);
        munmap(trampoline, kTrampolineSize);
        return false;
    }

    return true;
}

bool inline_hook_branch(void *target, void *handler) {
    std::lock_guard<std::mutex> lock(g_install_mutex);
    return install_branch_hook(target, handler, nullptr);
}

bool inline_hook_branch(void *target, void *handler, void **out_trampoline) {
    std::lock_guard<std::mutex> lock(g_install_mutex);
    if (out_trampoline == nullptr) return false;
    return install_branch_hook(target, handler, out_trampoline);
}

} // namespace arirang
