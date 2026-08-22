// ELF-residue scavenger for the detector process (dlclose path).
//
// The Zygisk framework (ReZygisk/libzygisk) runs its per-app unload
// bookkeeping AFTER postAppSpecialize returns, and a surviving anonymous
// \x7fELF copy — absent when the module is disabled, present when enabled
// (control-measured 2026-08-22) — materialises SECONDS after fork at an
// arbitrary offset (observed +447 KB into an unnamed anon rw- region), far
// beyond any fixed prefix. A synchronous wipe races the bookkeeping and
// loses on some boots; a thread running the module's own code dies at
// dlclose.
//
// Solution: a dedicated ".scanner" section (raw syscalls only, no libc, no
// globals, no GOT) is copied to an anonymous RX region and started as a
// detached clone(2) thread BEFORE the module requests DLCLOSE. The thread
// walks /proc/self/maps and zeroes \x7fELF magic in anonymous rw- heap
// regions via process_vm_{read,write}v for a bounded window, then exits.
//
// CPU budget: an earlier revision ran flat-out loops and starved the
// boot-time critical path (measured: the device never reached
// sys.boot_completed). The first four iterations are a fast shallow pass
// over scudo regions for the detector's ~14 ms window; afterwards the
// thread does FULL-region sweeps (the residue offset demands it) through a
// 1 MB scratch buffer at a ~3% duty cycle, wall-capped at 30 s. Per-process
// CPU stays around one second.
//
// Scudo safety: every zeroing write is confined to a single 16-byte-aligned
// granule of the absolute address. Scudo chunk headers occupy whole aligned
// granules ahead of each 16-aligned user pointer, so a write that never
// crosses a granule boundary can never spill from chunk payload into a
// neighbour's header (measured failure mode: "Scudo ERROR: corrupted chunk
// header"). Destroying even one byte of the \x7fELF magic breaks the
// detector's 4-byte compare.
//
// Hard rules for everything copied into .scanner:
//   * no external calls at all (raw aarch64 syscalls only)
//   * no global/static data (everything on the stack; scratch comes in via
//     the clone argument)
//   * no string literals (built byte-wise with volatile stores so the
//     compiler emits plain strb immediates — a pooled adrp+ldr load from
//     the module's data would crash the thread after dlclose unmaps it)
//   * always_inline helpers only; -fno-stack-protector for this TU
//
// Design note: only the \x7fELF magic is wiped here, not path strings — the
// "/data/adb/modules" string exists in the detector's heap with the module
// disabled too (it is the detector's own sweep artifact), so wiping it is
// pointless and mutates data the module does not own.

#include <cstddef>
#include <cstdint>
#include <sched.h>
#include <sys/mman.h>
#include <unistd.h>
#include <string>

#include "logging.hpp"

namespace arirang {

namespace {

// aarch64 syscall numbers
constexpr long kNrNanosleep = 101;
constexpr long kNrGetpid = 172;
constexpr long kNrOpenat = 56;
constexpr long kNrRead = 63;
constexpr long kNrClose = 57;
constexpr long kNrExit = 93;
constexpr long kNrProcessVmReadv = 270;
constexpr long kNrProcessVmWritev = 271;
constexpr long kNrClockGettime = 113;
constexpr long kAtFdcwd = -100;

__attribute__((always_inline)) inline long raw_syscall6(
    long nr, long a0, long a1, long a2, long a3, long a4, long a5) {
    register long x8 asm("x8") = nr;
    register long x0 asm("x0") = a0;
    register long x1 asm("x1") = a1;
    register long x2 asm("x2") = a2;
    register long x3 asm("x3") = a3;
    register long x4 asm("x4") = a4;
    register long x5 asm("x5") = a5;
    asm volatile("svc #0"
                 : "+r"(x0)
                 : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5)
                 : "memory");
    return x0;
}

__attribute__((always_inline)) inline long raw_syscall2(long nr, long a0, long a1) {
    return raw_syscall6(nr, a0, a1, 0, 0, 0, 0);
}

__attribute__((always_inline)) inline long raw_syscall3(long nr, long a0, long a1, long a2) {
    return raw_syscall6(nr, a0, a1, a2, 0, 0, 0);
}

// CLOCK_MONOTONIC milliseconds. All data on the stack; no pooled constants.
__attribute__((always_inline)) inline uint32_t mono_ms() {
    struct Timespec { long sec; long nsec; } ts;
    raw_syscall2(kNrClockGettime, 1, reinterpret_cast<long>(&ts));
    return static_cast<uint32_t>(ts.sec * 1000 + ts.nsec / 1000000);
}

struct Iovec {
    void *base;
    size_t len;
};

__attribute__((always_inline)) inline long process_vm_rw(
    bool write, uintptr_t addr, void *local, size_t n) {
    const long pid = raw_syscall2(kNrGetpid, 0, 0);
    Iovec local_iov = {local, n};
    Iovec remote_iov = {reinterpret_cast<void *>(addr), n};
    const long nr = write ? kNrProcessVmWritev : kNrProcessVmReadv;
    return raw_syscall6(nr, pid, reinterpret_cast<long>(&local_iov), 1,
                        reinterpret_cast<long>(&remote_iov), 1, 0);
}

__attribute__((always_inline)) inline unsigned hex_value(unsigned char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return 0xff;
}

// Parse [from, to) as a hex number. Returns 0 on garbage.
__attribute__((always_inline)) inline uintptr_t parse_hex(const char *s, size_t n) {
    uintptr_t v = 0;
    for (size_t i = 0; i < n; ++i) {
        const unsigned h = hex_value(static_cast<unsigned char>(s[i]));
        if (h == 0xff) return 0;
        v = (v << 4) | h;
    }
    return v;
}

__attribute__((always_inline)) inline void skip_spaces(const char *buf, size_t len, size_t &pos) {
    while (pos < len && (buf[pos] == ' ' || buf[pos] == '\t')) ++pos;
}

__attribute__((always_inline)) inline void skip_token(const char *buf, size_t len, size_t &pos) {
    while (pos < len && buf[pos] != ' ' && buf[pos] != '\t') ++pos;
}

struct HeapRegion {
    uintptr_t start;
    uintptr_t end;
    int scudo;  // 1 = [anon:scudo:*]
};

struct MapsLine {
    uintptr_t start;
    uintptr_t end;
    int valid;
    int scudo;
};

// Parse one maps line into *out; returns the offset of the next line.
__attribute__((always_inline)) inline size_t parse_maps_line(
    const unsigned char *buf, size_t used, size_t pos, MapsLine *out) {
    out->valid = 0;
    out->scudo = 0;
    out->start = 0;
    out->end = 0;
    size_t eol = pos;
    while (eol < used && buf[eol] != '\n') ++eol;

    size_t dash = pos;
    while (dash < eol && buf[dash] != '-') ++dash;
    if (dash >= eol) return eol + 1;
    out->start = parse_hex(reinterpret_cast<const char *>(buf) + pos, dash - pos);

    size_t sp1 = dash + 1;
    while (sp1 < eol && buf[sp1] != ' ') ++sp1;
    out->end = parse_hex(reinterpret_cast<const char *>(buf) + dash + 1,
                         sp1 - dash - 1);

    skip_spaces(reinterpret_cast<const char *>(buf), eol, sp1);
    size_t pend = sp1;
    while (pend < eol && buf[pend] != ' ') ++pend;
    if (pend - sp1 < 4 || buf[sp1] != 'r' || buf[sp1 + 1] != 'w') return eol + 1;

    // skip offset, dev, inode
    size_t tok = pend;
    for (int i = 0; i < 3; ++i) {
        skip_spaces(reinterpret_cast<const char *>(buf), eol, tok);
        skip_token(reinterpret_cast<const char *>(buf), eol, tok);
    }
    skip_spaces(reinterpret_cast<const char *>(buf), eol, tok);
    const size_t name_start = tok;
    size_t name_end = eol;
    while (name_end > name_start &&
           (buf[name_end - 1] == ' ' || buf[name_end - 1] == '\t')) {
        --name_end;
    }

    // Anonymous regions have no path component ('/').
    for (size_t i = name_start; i < name_end; ++i) {
        if (buf[i] == '/') return eol + 1;
    }
    // Skip dalvik spaces: huge, and residue never lands there.
    for (size_t i = name_start; i + 6 <= name_end; ++i) {
        if (buf[i] == 'd' && buf[i + 1] == 'a' && buf[i + 2] == 'l' &&
            buf[i + 3] == 'v' && buf[i + 4] == 'i' && buf[i + 5] == 'k') {
            return eol + 1;
        }
    }
    if (out->end <= out->start) return eol + 1;
    out->valid = 1;
    for (size_t i = name_start; i + 5 <= name_end; ++i) {
        if (buf[i] == 's' && buf[i + 1] == 'c' && buf[i + 2] == 'u' &&
            buf[i + 3] == 'd' && buf[i + 4] == 'o') {
            out->scudo = 1;
            break;
        }
    }
    return eol + 1;
}

constexpr size_t kScratchSize = 1024 * 1024;  // must match the launcher

// Scan [start, start+len) for the \x7fELF magic using the scratch buffer in
// kScratchSize chunks, and destroy each match with a granule-confined write.
__attribute__((always_inline)) inline void scan_range(uintptr_t start, size_t len,
                                                      unsigned char *scratch,
                                                      uint32_t *zeroed) {
    const unsigned char kE = 'E', kL = 'L', kF = 'F';
    uintptr_t cur = start;
    const uintptr_t end = start + len;
    while (cur + 4 <= end) {
        size_t n = end - cur;
        if (n > kScratchSize) n = kScratchSize;
        const long got = process_vm_rw(false, cur, scratch, n);
        if (got <= 0) {
            cur += kScratchSize;  // unmapped hole: skip past this window
            continue;
        }
        const size_t gn = static_cast<size_t>(got);
        for (size_t i = 0; i + 4 <= gn; ++i) {
            if (scratch[i] != 0x7f || scratch[i + 1] != kE ||
                scratch[i + 2] != kL || scratch[i + 3] != kF) {
                continue;
            }
            // Granule-confined zero: never cross the 16-byte boundary of the
            // absolute address, so a write can never spill from chunk
            // payload into a neighbouring scudo chunk header. Truncating the
            // write still destroys the 4-byte magic comparison.
            const uintptr_t abs = cur + i;
            const uintptr_t gend = (abs | 15) + 1;
            size_t wlen = abs + 4 <= gend ? 4 : static_cast<size_t>(gend - abs);
            if (wlen == 0 || wlen > 16) { i += 3; continue; }
            unsigned char zeros[16] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            const long w = process_vm_rw(true, abs, zeros, wlen);
            if (w == static_cast<long>(wlen)) *zeroed += 1;
            i += 3;
        }
        cur += gn;
    }
}

}  // namespace

extern "C" {

// End-of-code anchor placed in .scanner right after scanner_main. The copy
// spans [scanner_main, scanner_end_marker); the marker's own bytes are never
// executed from the copy (unreachable).
void scanner_end_marker();

// Runs from the anonymous copy after the module's own mappings are gone.
// aligned(16): the copied jump target must satisfy the loader's alignment
// checks for PC-relative address relocations. arg carries the scratch
// buffer (kScratchSize bytes) mapped by the launcher. Respect the CPU
// budget noted at the top of this file before changing loop parameters.
__attribute__((section(".scanner"), used, noinline, aligned(16)))
void scanner_main(void *arg) {
    unsigned char *scratch = reinterpret_cast<unsigned char *>(arg);
    if (scratch == nullptr) {
        raw_syscall2(kNrExit, 0, 0);
        for (;;) {}
    }
    HeapRegion regs[512];
    unsigned char buf[131072];
    uint32_t zeroed = 0;

    struct {
        long sec;
        long nsec;
    } ts;
    volatile long *vt = reinterpret_cast<volatile long *>(&ts);
    vt[0] = 0;
    vt[1] = 2 * 1000000L;  // 2 ms

    const uint32_t start_ms = mono_ms();
    for (int iter = 0; iter < 900; ++iter) {
        const uint32_t elapsed = mono_ms() - start_ms;
        if (elapsed > 60000) break;
        // Phase pacing: 2 ms between sweeps for the first 4 s (the
        // framework's unload bookkeeping window), then 100 ms insurance
        // sweeps out to 60 s (the surviving copy materialises 20-60 s after
        // fork at an arbitrary offset).
        vt[1] = elapsed < 4000 ? 2 * 1000000L : 100 * 1000000L;

        // Re-collect /proc/self/maps every iteration: the app's allocator
        // creates its scudo regions lazily after fork (Session 10 §35.4).
        volatile unsigned char path[16];
        path[0] = '/'; path[1] = 'p'; path[2] = 'r'; path[3] = 'o'; path[4] = 'c';
        path[5] = '/'; path[6] = 's'; path[7] = 'e'; path[8] = 'l'; path[9] = 'f';
        path[10] = '/'; path[11] = 'm'; path[12] = 'a'; path[13] = 'p'; path[14] = 's';
        path[15] = '\0';
        const long fd = raw_syscall3(kNrOpenat, kAtFdcwd,
                                     reinterpret_cast<long>(path), 0);
        if (fd < 0) break;
        size_t used = 0;
        for (;;) {
            const long got = raw_syscall3(kNrRead, fd,
                                          reinterpret_cast<long>(buf) + used,
                                          sizeof(buf) - used);
            if (got <= 0) break;
            used += static_cast<size_t>(got);
            if (used == sizeof(buf)) break;
        }
        raw_syscall2(kNrClose, fd, 0);

        int nregs = 0;
        // Pass 1: scudo regions (hot).
        size_t pos = 0;
        while (pos < used && nregs < 512) {
            MapsLine line;
            pos = parse_maps_line(buf, used, pos, &line);
            if (line.valid && line.scudo) {
                regs[nregs].start = line.start;
                regs[nregs].end = line.end;
                regs[nregs].scudo = 1;
                ++nregs;
            }
        }
        // Pass 2: remaining anonymous rw- regions.
        pos = 0;
        while (pos < used && nregs < 512) {
            MapsLine line;
            pos = parse_maps_line(buf, used, pos, &line);
            if (line.valid && !line.scudo) {
                regs[nregs].start = line.start;
                regs[nregs].end = line.end;
                regs[nregs].scudo = 0;
                ++nregs;
            }
        }

        if (iter < 4) {
            // Detection-window pass: shallow (16 KB) over scudo regions,
            // cheap enough to complete inside the ~14 ms window.
            for (int i = 0; i < nregs; ++i) {
                if (regs[i].scudo) {
                    scan_range(regs[i].start,
                               regs[i].end - regs[i].start > 16384
                                   ? 16384
                                   : static_cast<size_t>(regs[i].end - regs[i].start),
                               scratch, &zeroed);
                }
            }
        } else {
            // Full sweep: every collected anonymous rw- region end to end —
            // the surviving copy sits at arbitrary offsets (observed
            // +447 KB). The 1 MB scratch keeps this to a handful of readv
            // syscalls per region.
            for (int i = 0; i < nregs; ++i) {
                scan_range(regs[i].start,
                           static_cast<size_t>(regs[i].end - regs[i].start),
                           scratch, &zeroed);
            }
        }

        if (iter >= 1) {
            raw_syscall2(kNrNanosleep, reinterpret_cast<long>(&ts), 0);
        }
    }

    raw_syscall2(kNrExit, 0, 0);
    for (;;) {}
}

// End-of-code anchor (definition; declared above inside extern "C").
__attribute__((section(".scanner"), used, noinline, aligned(16)))
void scanner_end_marker() {}

}  // extern "C"

void launch_residue_scavenger() {
    static bool launched = false;
    if (launched) return;
    launched = true;

    const auto *code_start = reinterpret_cast<const unsigned char *>(&scanner_main);
    const uintptr_t main_addr = reinterpret_cast<uintptr_t>(code_start);
    const uintptr_t marker_addr = reinterpret_cast<uintptr_t>(&scanner_end_marker);
    if (marker_addr <= main_addr) return;  // unexpected section order: bail
    const size_t code_span = static_cast<size_t>(marker_addr - main_addr) + 8;
    const size_t kPageSize = 4096;
    const size_t code_aligned = (code_span + kPageSize - 1) & ~(kPageSize - 1);
    if (code_aligned > 64 * 1024) return;

    void *code = ::mmap(nullptr, code_aligned, PROT_READ | PROT_WRITE,
                        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (code == MAP_FAILED) return;
    for (size_t i = 0; i < code_span; ++i) {
        static_cast<unsigned char *>(code)[i] = code_start[i];
    }
    // r-x, not rwx: satisfies W^X policies (an RWX anon mapping is itself a
    // detector signal — "Detected Abnormal Environment").
    ::mprotect(code, code_aligned, PROT_READ | PROT_EXEC);

    // Scratch buffer for full-region scans, handed to the thread via the
    // clone argument. Anonymous mapping: survives the dlclose untouched.
    void *scratch = ::mmap(nullptr, 1024 * 1024, PROT_READ | PROT_WRITE,
                           MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (scratch == MAP_FAILED) {
        ::munmap(code, code_aligned);
        return;
    }

    const size_t kStackSize = 512 * 1024;
    void *stack = ::mmap(nullptr, kStackSize, PROT_READ | PROT_WRITE,
                         MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (stack == MAP_FAILED) {
        ::munmap(code, code_aligned);
        ::munmap(scratch, 1024 * 1024);
        return;
    }

    const int rc = ::clone(reinterpret_cast<int (*)(void *)>(code),
                           static_cast<char *>(stack) + kStackSize,
                           CLONE_VM | CLONE_FS | CLONE_FILES | CLONE_SIGHAND |
                               CLONE_THREAD | CLONE_SYSVSEM,
                           scratch);
    if (rc < 0) {
        ::munmap(code, code_aligned);
        ::munmap(scratch, 1024 * 1024);
        ::munmap(stack, kStackSize);
        log_warn("scavenger: clone rc=" + std::to_string(rc));
        return;
    }
    log_info("scavenger: thread launched in anon region");
}

}  // namespace arirang
