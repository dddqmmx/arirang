// ELF-residue scavenger for the detector process (dlclose path).
//
// The Zygisk framework (ReZygisk/libzygisk) runs its per-app unload
// bookkeeping AFTER postAppSpecialize returns: it installs in-heap copies
// of the module's ELF image into the app process. Root detectors walk the
// process's anonymous heap for \x7fELF blocks and report each as
// "Detected Zygisk (N)". Zeroing them in postAppSpecialize is a no-op (the
// copies do not exist yet), and a detached thread from the module's own
// code dies at dlclose (its pages are unmapped).
//
// Solution: this translation unit is compiled into a dedicated ".scanner"
// section with NO libc calls, NO globals and NO GOT references. Before the
// module requests DLCLOSE, launch_residue_scavenger() copies the section
// to an anonymous RWX region and starts a detached thread there via
// clone(2). The thread survives the unload, sleeps past the framework's
// bookkeeping, then walks /proc/self/maps and zeroes every \x7fELF block
// in the process's anonymous rw- heap regions via process_vm_{read,write}v
// (same-process syscalls, no ptrace).
//
// Hard rules for scanner_main and its helpers (all in .scanner):
//   * no external calls at all (raw aarch64 syscalls only)
//   * no global/static data (everything on the stack)
//   * no string literals (built byte-wise on the stack)
//   * always_inline helpers only; no stack-protector in this TU

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

// Diagnostic state block, emitted into its own ".scanner_state" section (a
// data section cannot share the executable ".scanner" section without a
// section-type conflict). The scavenger thread publishes progress here from
// the anonymous copy after dlclose; the host locates it at
// rwxp_region_start + (scanner_state - copy_base). Nothing here is executed.
struct ScannerState {
    volatile uint32_t magic;       // 0x51A7E11F once running
    volatile uint32_t iterations;  // loop iterations completed
    volatile uint32_t nregs;       // regions in the last collect
    volatile uint32_t hits;        // \x7fELF blocks found in total
    volatile uint32_t zeroed;      // magic-zeroing writev calls done
    volatile uint32_t start_ms;    // CLOCK_MONOTONIC ms at thread start
    volatile uint32_t first_ms;    // CLOCK_MONOTONIC ms at iteration 0 end
    volatile uint32_t end_ms;      // CLOCK_MONOTONIC ms at loop end
    volatile uint32_t first_hit_off;   // byte offset of the first \x7fELF block
    volatile uint32_t first_hit_lo;    // first hit's first 4 bytes (u32)
    volatile uint32_t first_hit_hi;    // first hit's next 4 bytes (u32)
};
__attribute__((section(".scanner_state"), used, aligned(16)))
volatile ScannerState scanner_state;

// One collected anonymous rw- region. Hot regions (scudo:secondary) hold all
// observed residue and are scanned every iteration; warm regions are swept
// rarely as insurance.
struct HeapRegion {
    uintptr_t start;
    uintptr_t end;
    bool hot;
};

struct MapsLine {
    uintptr_t start;
    uintptr_t end;
    bool is_rw;
    bool has_secondary;
};

// Scan the leading part of one anonymous rw- region for \x7fELF blocks and
// zero their magic. All app-local residue observed so far sits within the
// first 4 KB of the region (offsets 784..3808); the deep offsets (up to
// 42688) belong to the zygote's inherited copies, which the app's own
// allocations reuse within ms of fork. A shallow pass therefore costs
// ~0.3 ms for the whole hot set; call with deep=true on the first iteration
// (covers inherited copies from fork) and every 64th iteration (insurance).
__attribute__((always_inline)) inline void scan_region(uintptr_t start, uintptr_t end, bool deep, ScannerState *st) {
    const size_t kPrefix = deep ? 65536 : 4096;
    unsigned char chunk[65536];
    unsigned char zero4[4] = {0, 0, 0, 0};
    const size_t size = static_cast<size_t>(end - start);
    const size_t n = (size < kPrefix) ? size : kPrefix;
    // process_vm_readv is mandatory: the app's allocator can munmap a region
    // between the maps re-read and this scan, and a direct load would fault
    // the whole process (observed: SEGV_MAPERR at an unmapped region start).
    // The readv syscall returns an error for such regions instead.
    const long got = process_vm_rw(false, start, chunk, n);
    if (got <= 0) return;
    size_t hits[32];
    int nh = 0;
    const size_t gn = static_cast<size_t>(got);
    for (size_t i = 0; i + 4 <= gn; ++i) {
        if (chunk[i] == 0x7f && chunk[i + 1] == 'E' && chunk[i + 2] == 'L' &&
            chunk[i + 3] == 'F') {
            if (nh < 32) hits[nh++] = i;
            i += 3;
        }
    }
    if (nh > 0) st->hits += static_cast<uint32_t>(nh);
    for (int k = 0; k < nh; ++k) {
        const long w = process_vm_rw(true, start + hits[k], zero4, 4);
        if (w == 4) st->zeroed++;
    }
}

// One readv(2) call for the whole hot set: a single syscall instead of one
// per region keeps the first pass ~1 ms even under load (syscall latency
// dominates per-region reads). On a failing iovec the kernel stops at it and
// returns the bytes already copied, so surviving regions still get scanned.
__attribute__((always_inline)) inline void scan_hot_regions(
    const HeapRegion *regs, int n, ScannerState *st) {
    Iovec iovs[64];
    unsigned char chunk[64 * 4096];
    int ni = 0;
    for (int i = 0; i < n && regs[i].hot && ni < 64; ++i) {
        const size_t size = static_cast<size_t>(regs[i].end - regs[i].start);
        const size_t len = (size < 4096) ? size : 4096;
        iovs[ni].base = reinterpret_cast<void *>(regs[i].start);
        iovs[ni].len = len;
        ++ni;
    }
    if (ni == 0) return;
    const long pid = raw_syscall2(kNrGetpid, 0, 0);
    Iovec local = {chunk, static_cast<size_t>(ni) * 4096};
    const long got = raw_syscall6(kNrProcessVmReadv, pid,
                                  reinterpret_cast<long>(&local), 1,
                                  reinterpret_cast<long>(&iovs), ni, 0);
    if (got <= 0) return;
    size_t remaining = static_cast<size_t>(got);
    for (int i = 0; i < ni && remaining > 0; ++i) {
        const size_t len = iovs[i].len;
        if (remaining < len) break;
        const unsigned char *p = chunk + static_cast<size_t>(i) * 4096;
        size_t hits[32];
        int nh = 0;
        for (size_t j = 0; j + 4 <= len; ++j) {
            if (p[j] == 0x7f && p[j + 1] == 'E' && p[j + 2] == 'L' &&
                p[j + 3] == 'F') {
                if (nh < 32) hits[nh++] = j;
                j += 3;
            }
        }
        if (nh > 0) st->hits += static_cast<uint32_t>(nh);
        if (nh > 0 && st->first_hit_off == 0) {
            st->first_hit_off = static_cast<uint32_t>(hits[0]);
            st->first_hit_lo = 0;
            st->first_hit_hi = 0;
        }
        for (int k = 0; k < nh; ++k) {
            unsigned char zero4[4] = {0, 0, 0, 0};
            const long w = process_vm_rw(true, regs[i].start + hits[k], zero4, 4);
            if (w == 4) st->zeroed++;
        }
        remaining -= len;
    }
}

}  // namespace

extern "C" {

// End-of-code anchor: a tiny marker function placed in .scanner right after
// scanner_main (defined below, after scanner_main's definition). The copy
// spans [scanner_main, scanner_end_marker); the marker's own bytes are never
// executed (unreachable from the copy).
void scanner_end_marker();

// Parse /proc/self/maps once and collect anonymous rw- regions (the heap
// areas where the framework's unload bookkeeping drops \x7fELF chunks),
// skipping the dalvik spaces (which never hold ELF residue and would make
// every pass slow). All residue observed so far lives in scudo:secondary
// regions at offsets < 64 KB from the region start, so those are collected
// first (regs[0..] in address order) and flagged hot; the remaining

__attribute__((always_inline)) inline size_t parse_maps_line(
    const unsigned char *buf, size_t used, size_t pos, MapsLine *out) {
    out->start = 0;
    out->end = 0;
    out->is_rw = false;
    out->has_secondary = false;
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
    out->is_rw = (pend - sp1 >= 4 && buf[sp1] == 'r' && buf[sp1 + 1] == 'w');

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
    bool has_slash = false;
    for (size_t i = name_start; i < name_end; ++i) {
        if (buf[i] == '/') { has_slash = true; break; }
    }
    // Skip dalvik spaces: huge, and ELF residue never lands there.
    bool has_dalvik = false;
    for (size_t i = name_start; i + 6 <= name_end; ++i) {
        if (buf[i] == 'd' && buf[i + 1] == 'a' && buf[i + 2] == 'l' &&
            buf[i + 3] == 'v' && buf[i + 4] == 'i' && buf[i + 5] == 'k') {
            has_dalvik = true;
            break;
        }
    }
    out->has_secondary = false;
    for (size_t i = name_start; i + 9 <= name_end; ++i) {
        if (buf[i] == 's' && buf[i + 1] == 'e' && buf[i + 2] == 'c' &&
            buf[i + 3] == 'o' && buf[i + 4] == 'n' && buf[i + 5] == 'd' &&
            buf[i + 6] == 'a' && buf[i + 7] == 'r' && buf[i + 8] == 'y') {
            out->has_secondary = true;
            break;
        }
    }
    if (has_slash || has_dalvik || !out->is_rw || out->end <= out->start) {
        out->is_rw = false;
    }
    return eol + 1;
}

__attribute__((always_inline)) inline int collect_heap_regions(
    unsigned char *buf, size_t cap,
    HeapRegion *regs, int max_regs) {
    // open("/proc/self/maps", O_RDONLY) — path built byte-wise on the stack.
    // Volatile stores: the compiler must emit plain strb immediates — a
    // pooled 16-byte load (adrp+ldr) from the module's data would crash the
    // scavenger thread after dlclose unmaps it.
    volatile unsigned char path[16];
    path[0] = '/'; path[1] = 'p'; path[2] = 'r'; path[3] = 'o'; path[4] = 'c';
    path[5] = '/'; path[6] = 's'; path[7] = 'e'; path[8] = 'l'; path[9] = 'f';
    path[10] = '/'; path[11] = 'm'; path[12] = 'a'; path[13] = 'p'; path[14] = 's';
    path[15] = '\0';
    const long fd = raw_syscall3(kNrOpenat, kAtFdcwd,
                                 reinterpret_cast<long>(path), 0);
    if (fd < 0) return 0;

    size_t used = 0;
    for (;;) {
        const long got = raw_syscall3(kNrRead, fd,
                                      reinterpret_cast<long>(buf) + used,
                                      cap - used);
        if (got <= 0) break;
        used += static_cast<size_t>(got);
        if (used == cap) break;
    }
    raw_syscall2(kNrClose, fd, 0);

    int nregs = 0;
    // Pass 1: hot scudo:secondary regions first.
    size_t pos = 0;
    while (pos < used && nregs < max_regs) {
        MapsLine line;
        pos = parse_maps_line(buf, used, pos, &line);
        if (line.is_rw && line.has_secondary) {
            regs[nregs].start = line.start;
            regs[nregs].end = line.end;
            regs[nregs].hot = true;
            ++nregs;
        }
    }
    // Pass 2: remaining anonymous rw- regions, warm.
    pos = 0;
    while (pos < used && nregs < max_regs) {
        MapsLine line;
        pos = parse_maps_line(buf, used, pos, &line);
        if (line.is_rw && !line.has_secondary) {
            regs[nregs].start = line.start;
            regs[nregs].end = line.end;
            regs[nregs].hot = false;
            ++nregs;
        }
    }
    return nregs;
}

// Runs from the anonymous copy after the module's own mappings are gone.
// aligned(16): the copied jump target must satisfy the loader's alignment
// checks for PC-relative address relocations.
__attribute__((section(".scanner"), used, noinline, aligned(16)))
void scanner_main(void *arg) {
    (void)arg;
    HeapRegion regs[512];
    unsigned char buf[131072];
    ScannerState st_local = {};
    ScannerState *st = &st_local;
    st->magic = 0x51A7E11Fu;
    st->iterations = 0;
    st->nregs = 0;
    st->hits = 0;
    st->zeroed = 0;
    st->start_ms = mono_ms();

    struct {
        long sec;
        long nsec;
    } ts;
    volatile long *vt = reinterpret_cast<volatile long *>(&ts);
    vt[0] = 0;
    vt[1] = 1000000L;

    for (int iter = 0; iter < 3000; ++iter) {
        const int n = collect_heap_regions(buf, sizeof(buf), regs, 512);
        st->nregs = static_cast<uint32_t>(n);
        const bool deep = (iter >= 64) && ((iter & 63) == 0);
        scan_hot_regions(regs, n, st);
        if (iter >= 64 && ((iter & 63) == 63)) {
            for (int i = n - 1; i >= 0; --i) {
                if (!regs[i].hot) scan_region(regs[i].start, regs[i].end, deep, st);
            }
        }
        st->iterations = static_cast<uint32_t>(iter + 1);
        if (iter == 0) st->first_ms = mono_ms();
        if (iter >= 500) {
            raw_syscall2(kNrNanosleep, reinterpret_cast<long>(&ts), 0);
        }
    }
    st->end_ms = mono_ms();

    raw_syscall2(kNrExit, 0, 0);
    for (;;) {}
}

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
    // Make code mapping r-x (not rwx) to satisfy W^X security policies
    ::mprotect(code, code_aligned, PROT_READ | PROT_EXEC);

    const size_t kStackSize = 512 * 1024;
    void *stack = ::mmap(nullptr, kStackSize, PROT_READ | PROT_WRITE,
                         MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (stack == MAP_FAILED) {
        ::munmap(code, code_aligned);
        return;
    }

    const int rc = ::clone(reinterpret_cast<int (*)(void *)>(code),
                           static_cast<char *>(stack) + kStackSize,
                           CLONE_VM | CLONE_FS | CLONE_FILES | CLONE_SIGHAND |
                               CLONE_THREAD | CLONE_SYSVSEM,
                           nullptr);
    if (rc < 0) {
        ::munmap(code, code_aligned);
        ::munmap(stack, kStackSize);
        log_warn("scavenger: clone rc=" + std::to_string(rc));
        return;
    }
    log_info("scavenger: thread launched in anon region");
}

}  // namespace arirang