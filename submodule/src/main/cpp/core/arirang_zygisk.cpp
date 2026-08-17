#include "zygisk.hpp"
#include "arirang_build_config.hpp"
#include "build_spoofer.hpp"
#include "elf_residue_scavenger.hpp"
#include "jni_utils.hpp"
#include "logging.hpp"
#include "submodule_config.hpp"
#include "sensor_spoofer.hpp"
#include "system_property_spoofer.hpp"
#include "timezone_prop_cow.hpp"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <csignal>
#include <csetjmp>
#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>
#include <string>

namespace {

// AOSP Process.PHONE_UID / android.uid.phone. Process-name matching alone is
// insufficient because an untrusted APK can request a misleading nice name.
constexpr jint kAndroidPhoneUid = 1001;

// FNV-1a of the first 22 bytes of "com.reveny.nativecheck".
// Isolated (:iso…) and AppZygote (_zygote) share that prefix. The
// package bytes are never stored; a contiguous detector name in the
// mapped image is itself an injection signal.
constexpr uint32_t kDetectorNameHash = 0x813ba9d2u;
constexpr size_t kDetectorNameLen = 22;

uint32_t fnv1a_prefix(const std::string &s, size_t n) {
    if (s.size() < n) return 0;
    uint32_t h = 2166136261u;
    for (size_t i = 0; i < n; ++i) {
        h ^= static_cast<unsigned char>(s[i]);
        h *= 16777619u;
    }
    return h;
}

bool is_detector_name(const std::string &s) {
    return fnv1a_prefix(s, kDetectorNameLen) == kDetectorNameHash;
}

// True only for the primary zygote process (parent is init).
// cmdline is still "zygote"/"zygote64" in every forked child until
// specialize rewrites it, so a cmdline prefix match at onLoad misfires
// in every app and re-runs remap_own_to_memfd() after fork — that
// post-start executable mapping is what nativecheck reports as
// "Found Injection".
bool is_zygote_process() {
    return ::getppid() == 1;
}

// Locate the module's own loaded image (the mapping this function's code
// executes from) and return its base address + ELF header validity.
uintptr_t find_own_base() {
    const uintptr_t self_pc = reinterpret_cast<uintptr_t>(&find_own_base);
    uintptr_t base = 0;
    std::string own_name;

    FILE *maps = std::fopen("/proc/self/maps", "r");
    if (maps == nullptr) return 0;
    char line[512];
    while (std::fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long long start = 0, end = 0, off = 0;
        char perms[8] = {0};
        char name[300] = {0};
        if (std::sscanf(line, "%llx-%llx %7s %llx %*s %*s %299[^\n]", &start, &end,
                        perms, &off, name) < 4) {
            continue;
        }
        char *p = name;
        while (*p == ' ' || *p == '\t') ++p;
        const uintptr_t s = static_cast<uintptr_t>(start);
        if (own_name.empty() && s <= self_pc && self_pc < static_cast<uintptr_t>(end)) {
            own_name = p;
        }
    }
    std::rewind(maps);
    while (std::fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long long start = 0, end = 0, off = 0;
        char perms[8] = {0};
        char name[300] = {0};
        if (std::sscanf(line, "%llx-%llx %7s %llx %*s %*s %299[^\n]", &start, &end,
                        perms, &off, name) < 4) {
            continue;
        }
        char *p = name;
        while (*p == ' ' || *p == '\t') ++p;
        if (own_name.empty() || std::strcmp(p, own_name.c_str()) != 0) continue;
        if (off == 0) {
            base = static_cast<uintptr_t>(start);
            break;
        }
    }
    std::fclose(maps);
    if (own_name.empty() || base == 0) return 0;
    const auto *eh = reinterpret_cast<const Elf64_Ehdr *>(base);
    if (eh->e_ident[EI_MAG0] != ELFMAG0 || eh->e_ident[EI_MAG1] != ELFMAG1 ||
        eh->e_ident[EI_MAG2] != ELFMAG2 || eh->e_ident[EI_MAG3] != ELFMAG3) {
        return 0;
    }
    return base;
}

// Replace this process's private copy of the module's ELF-header page
// (offset 0x0-0x1000, R-only: headers only, .text starts at 0x1c000) with an
// anonymous zeroed page. The app domain cannot mprotect the R-only file
// mapping RW (no VM_MAYWRITE: EACCES), but mapping a fresh anon page over it
// needs no permission change. The framework's unload bookkeeping memcpys the
// image into the detector's heap after postAppSpecialize returns; with the
// header page zeroed the copies carry no \x7fELF magic and "Detected Zygisk"
// counts nothing. Writes are CoW-private; nothing else ever maps the page.
__attribute__((unused)) void scrub_own_elf_header() {
    const uintptr_t base = find_own_base();
    if (base == 0) {
        arirang::log_info("scrub_own_elf_header: find_own_base failed");
        return;
    }
    void *p = ::mmap(reinterpret_cast<void *>(base), 0x1000,
                     PROT_READ | PROT_WRITE,
                     MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
    if (p == MAP_FAILED) {
        arirang::log_info(std::string("scrub_own_elf_header: mmap failed base=") +
                          std::to_string(base) + " errno=" + std::to_string(errno));
        return;
    }
    arirang::log_info(std::string("scrub_own_elf_header: replaced base=") +
                      std::to_string(base));
}

// Swap the module's exported zygisk entry-point names in this process's own
// private copy of the mapping. The framework resolves these symbols once in
// Zygote (dlsym at load time) and never re-queries them in app processes, so
// scrubbing the app-side copies is safe; it leaves nothing in any app process
// that identifies this library as a Zygisk module. The on-disk .so is
// untouched (the writes are CoW-private) and map_write authorization for
// app domains is granted in sepolicy.rule.
__attribute__((unused)) void swap_own_zygisk_symbols() {
    const uintptr_t base = find_own_base();
    if (base == 0) return;

    const auto *eh = reinterpret_cast<const Elf64_Ehdr *>(base);
    if (eh->e_ident[EI_MAG0] != ELFMAG0 || eh->e_ident[EI_MAG1] != ELFMAG1 ||
        eh->e_ident[EI_MAG2] != ELFMAG2 || eh->e_ident[EI_MAG3] != ELFMAG3) {
        return;
    }
    const auto *ph = reinterpret_cast<const Elf64_Phdr *>(base + eh->e_phoff);
    uintptr_t dyn_symtab = 0, dyn_strtab = 0;
    size_t dyn_strsz = 0;
    for (size_t i = 0; i < eh->e_phnum; ++i) {
        if (ph[i].p_type != PT_DYNAMIC) continue;
        const auto *dyn = reinterpret_cast<const Elf64_Dyn *>(base + ph[i].p_vaddr);
        const size_t count = ph[i].p_memsz / sizeof(Elf64_Dyn);
        for (size_t j = 0; j < count; ++j) {
            if (dyn[j].d_tag == DT_SYMTAB)
                dyn_symtab = base + static_cast<uintptr_t>(dyn[j].d_un.d_ptr);
            if (dyn[j].d_tag == DT_STRTAB)
                dyn_strtab = base + static_cast<uintptr_t>(dyn[j].d_un.d_ptr);
            if (dyn[j].d_tag == DT_STRSZ) dyn_strsz = dyn[j].d_un.d_val;
        }
    }
    if (dyn_strtab == 0 || dyn_strtab < base || dyn_strtab - base > 0x400000) return;

    const uintptr_t kPageSize = 0x1000;
    const char *raw = reinterpret_cast<const char *>(dyn_strtab);
    static const struct { const char *from; const char *to; } kRepl[] = {
        {"zygisk_module_entry", "vendor_drm_util_ent"},
        {"zygisk_companion_entry", "vendor_drm_media_entry"},
    };
    for (const auto &r : kRepl) {
        const size_t len = std::strlen(r.from);
        for (size_t i = 0; i + len <= dyn_strsz; ++i) {
            if (std::memcmp(raw + i, r.from, len) != 0) continue;
            const uintptr_t page = (dyn_strtab + i) & ~static_cast<uintptr_t>(kPageSize - 1);
            const uintptr_t end = dyn_strtab + i + len;
            const uintptr_t last_page = (end - 1) & ~static_cast<uintptr_t>(kPageSize - 1);
            bool ok = true;
            for (uintptr_t p = page; p <= last_page; p += kPageSize) {
                if (::mprotect(reinterpret_cast<void *>(p), kPageSize,
                               PROT_READ | PROT_WRITE) != 0) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            std::memcpy(const_cast<char *>(raw) + i, r.to, len);
            for (uintptr_t p = page; p <= last_page; p += kPageSize) {
                ::mprotect(reinterpret_cast<void *>(p), kPageSize, PROT_READ);
            }
            if (dyn_symtab != 0) {
                const auto *sym = reinterpret_cast<const Elf64_Sym *>(dyn_symtab);
                for (size_t s = 0; s < dyn_strsz / sizeof(Elf64_Sym); ++s) {
                    if (sym[s].st_name != i) continue;
                    const uintptr_t saddr = dyn_symtab + s * sizeof(Elf64_Sym);
                    const uintptr_t spage = saddr & ~static_cast<uintptr_t>(kPageSize - 1);
                    if (::mprotect(reinterpret_cast<void *>(spage), kPageSize,
                                   PROT_READ | PROT_WRITE) != 0) {
                        continue;
                    }
                    auto *m = const_cast<Elf64_Sym *>(&sym[s]);
                    m->st_name = 0;
                    m->st_value = 0;
                    m->st_size = 0;
                    ::mprotect(reinterpret_cast<void *>(spage), kPageSize, PROT_READ);
                }
            }
        }
    }
    arirang::log_info("swap_own_zygisk_symbols: done");
}

// Remap the module's own file-backed mappings onto a memfd so that no
// /proc/<pid>/maps entry resolves to the staged library path. Run in the
// zygote (onLoad provably executes there — see onLoad logging): the
// framework (ReZygisk) dlopens the module once in the zygote and every
// derived process (system_server, com.android.phone, apps) inherits the
// mapping, so converting it in the zygote removes the path everywhere.
//
// Detectors read /proc/<pid>/maps of zygote64/system_server/phone from the
// app uid; the "Detected Zygisk (2)" card tracks the module's file-backed
// mapping presence, independent of path string or dynstr symbols (both
// empirically falsified). An anonymous fd-backed mapping is the last
// in-place disguise available to the module without touching the framework.
//
// Technique: create a memfd, write each segment's bytes into it (via a
// private mapping of the memfd), then MAP_FIXED the same address ranges onto
// the memfd pages. Because the memfd pages are fully populated before the
// MAP_FIXED call, the instruction stream is preserved even for the executing
// .text segment — no zero-page window exists. Original perms are restored
// with mprotect. Requires MFD_CLOEXEC (detectors may scan /proc/self/fd).
__attribute__((unused)) void remap_own_to_memfd() {
    const uintptr_t base = find_own_base();
    if (base == 0) return;

    static sigjmp_buf jb;
    struct sigaction old;
    struct sigaction act = {};
    act.sa_handler = [](int) { siglongjmp(jb, 1); };
    sigemptyset(&act.sa_mask);
    act.sa_flags = SA_RESTART;
    if (sigaction(SIGSEGV, &act, &old) != 0) return;
    if (sigsetjmp(jb, 1) != 0) {
        sigaction(SIGSEGV, &old, nullptr);
        return;
    }

    // Collect the module's own segments (same file name as the base mapping).
    struct Seg { uintptr_t start; uintptr_t end; off_t off; int prot; };
    Seg segs[16];
    size_t nsegs = 0;
    std::string own_name;

    FILE *maps = std::fopen("/proc/self/maps", "r");
    if (maps == nullptr) {
        sigaction(SIGSEGV, &old, nullptr);
        return;
    }
    char line[512];
    const uintptr_t self_pc = reinterpret_cast<uintptr_t>(&remap_own_to_memfd);
    while (std::fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long long start = 0, end = 0, off = 0;
        char perms[8] = {0};
        char name[300] = {0};
        if (std::sscanf(line, "%llx-%llx %7s %llx %*s %*s %299[^\n]", &start, &end,
                        perms, &off, name) < 4) {
            continue;
        }
        char *p = name;
        while (*p == ' ' || *p == '\t') ++p;
        if (own_name.empty() && start <= self_pc && self_pc < end) {
            own_name = p;
        }
    }
    if (own_name.empty()) {
        std::fclose(maps);
        sigaction(SIGSEGV, &old, nullptr);
        return;
    }

    std::rewind(maps);
    while (std::fgets(line, sizeof(line), maps) != nullptr && nsegs < 16) {
        unsigned long long start = 0, end = 0, off = 0;
        char perms[8] = {0};
        char name[300] = {0};
        if (std::sscanf(line, "%llx-%llx %7s %llx %*s %*s %299[^\n]", &start, &end,
                        perms, &off, name) < 4) {
            continue;
        }
        char *p = name;
        while (*p == ' ' || *p == '\t') ++p;
        if (std::strcmp(p, own_name.c_str()) != 0) continue;
        if (std::strchr(perms, 's') != nullptr) continue;  // shared: keep as-is
        // Only executable segments are remapped. Relocation segments
        // (.rela.dyn/.rela.plt), the GOT (.got/.got.plt), dynstr/dynsym and
        // .data carry linker live state (resolved symbol addresses, lazy
        // binding PLT slots) that is rewritten at load time; replacing them
        // breaks PLT resolution and the zygote crashes in a restart loop
        // (measured: "restart too much times, stop injecting"). Keeping them
        // on the original file mapping preserves resolved GOT entries while
        // the code pages come from the memfd.
        if (perms[2] != 'x') continue;
        int prot = 0;
        prot |= PROT_READ;
        prot |= PROT_EXEC;
        segs[nsegs++] = {static_cast<uintptr_t>(start), static_cast<uintptr_t>(end),
                         static_cast<off_t>(off), prot};
    }
    std::fclose(maps);
    if (nsegs == 0) {
        sigaction(SIGSEGV, &old, nullptr);
        return;
    }

    // Highest file offset covered: size the memfd (rounded to a page).
    uintptr_t max_end = 0;
    for (size_t i = 0; i < nsegs; ++i) {
        const uintptr_t file_end = static_cast<uintptr_t>(segs[i].off) + (segs[i].end - segs[i].start);
        if (file_end > max_end) max_end = file_end;
    }
    const uintptr_t kPageSize = 0x1000;
    const uintptr_t memfd_size = (max_end + kPageSize - 1) & ~(kPageSize - 1);

    int fd = ::memfd_create("libcamera_hal.so", MFD_CLOEXEC);
    if (fd < 0) {
        sigaction(SIGSEGV, &old, nullptr);
        return;
    }
    if (::ftruncate(fd, static_cast<off_t>(memfd_size)) != 0) {
        ::close(fd);
        sigaction(SIGSEGV, &old, nullptr);
        return;
    }

    // Populate the memfd from the live mappings. pwrite is used instead of a
    // MAP_PRIVATE buffer: writes to a private mapping never reach the fd, so
    // a later mmap(fd) would read back the hole (zero pages) and the replaced
    // .text would fault with SIGILL on the first instruction after the swap.
    for (size_t i = 0; i < nsegs; ++i) {
        const size_t len = segs[i].end - segs[i].start;
        if (::pwrite(fd, reinterpret_cast<const void *>(segs[i].start), len,
                     segs[i].off) != static_cast<ssize_t>(len)) {
            ::close(fd);
            sigaction(SIGSEGV, &old, nullptr);
            return;
        }
    }

    // Replace each segment in place; contents are already in the memfd, so
    // the executing .text segment continues seamlessly.
    size_t replaced = 0;
    for (size_t i = 0; i < nsegs; ++i) {
        void *m = ::mmap(reinterpret_cast<void *>(segs[i].start), segs[i].end - segs[i].start,
                         segs[i].prot, MAP_PRIVATE | MAP_FIXED, fd, segs[i].off);
        if (m == MAP_FAILED) continue;
        ++replaced;
    }
    ::close(fd);  // mapping survives; fd must not leak to forks

    sigaction(SIGSEGV, &old, nullptr);
    arirang::log_info("remap_own_to_memfd: replaced " + std::to_string(replaced) +
                      "/" + std::to_string(nsegs) + " segments, size=" + std::to_string(memfd_size));
}

// The Zygisk framework (ReZygisk/libzygisk) keeps private in-heap copies of
// its own ELF image; when it unloads itself from an app process those copies
// remain and are counted one-for-one by root detectors as "Detected Zygisk"
// leftovers (empirically: exactly 2 anonymous-heap blocks starting with
// \x7fELF, headers distinct from the module's own ELF). Their creation is
// staggered across ~+12..+16 ms after fork, so a wipe must run after that
// window. Neutralize them by zeroing the ELF magic of every anonymous-heap
// Wipe duplicate in-heap ELF copies and leftover root/Zygisk residues in
// anonymous rw- regions. The process_vm_readv/writev pair is mandatory: the
// allocator can munmap or PROT_NONE a region mid-scan and a direct load would
// fault the whole process; readv returns an error for such regions instead.
__attribute__((unused)) void wipe_duplicate_elf_copies() {
    static const struct {
        const char *pattern;
        size_t len;
    } kResidues[] = {
        {"\x7f\x45\x4c\x46", 4}, // \x7fELF
        {"/data/adb", 9},
        {"rezygisk", 8},
        {"zygisk", 6},
        {"magisk", 6},
        {"lsposed", 7},
        {"libhwc_vendor", 14},
        {"arirang", 7},
    };
    constexpr size_t kNumResidues = sizeof(kResidues) / sizeof(kResidues[0]);

    unsigned char chunk[65536];
    unsigned char zeroes[32] = {0};
    const pid_t self = ::getpid();
    int wiped = 0;
    FILE *maps = std::fopen("/proc/self/maps", "r");
    if (maps == nullptr) return;
    char line[512];
    while (std::fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long long start = 0, end = 0;
        char perms[8] = {0};
        char name[300] = {0};
        if (std::sscanf(line, "%llx-%llx %7s %*s %*s %*s %299[^\n]", &start, &end,
                        perms, name) < 3) {
            continue;
        }
        char *p = name;
        while (*p == ' ' || *p == '\t') ++p;
        // Only anonymous heap regions: unnamed or "[anon:...]"
        if (std::strlen(p) != 0 && std::strchr(p, '/') != nullptr) continue;
        if (perms[0] != 'r' || perms[1] != 'w' || perms[2] == 'x') continue;
        // Skip huge Dalvik spaces (> 64MB)
        if ((end - start) > 64 * 1024 * 1024) continue;

        const uintptr_t s = static_cast<uintptr_t>(start);
        const uintptr_t e = static_cast<uintptr_t>(end);
        for (uintptr_t cur = s; cur < e; cur += sizeof(chunk)) {
            const size_t to_read = (e - cur > sizeof(chunk)) ? sizeof(chunk) : (e - cur);
            struct iovec local = {chunk, to_read};
            struct iovec remote = {reinterpret_cast<void *>(cur), to_read};
            const long got = ::syscall(SYS_process_vm_readv, self, &local, 1, &remote, 1, 0);
            if (got <= 0) continue;
            const size_t gn = static_cast<size_t>(got);

            for (size_t r = 0; r < kNumResidues; ++r) {
                const char *pat = kResidues[r].pattern;
                const size_t plen = kResidues[r].len;
                if (gn < plen) continue;
                for (size_t i = 0; i + plen <= gn; ++i) {
                    if (std::memcmp(chunk + i, pat, plen) == 0) {
                        struct iovec wlocal = {zeroes, plen};
                        struct iovec wremote = {reinterpret_cast<void *>(cur + i), plen};
                        const long w = ::syscall(SYS_process_vm_writev, self, &wlocal, 1, &wremote, 1, 0);
                        if (w == static_cast<long>(plen)) ++wiped;
                        i += plen - 1;
                    }
                }
            }
        }
    }
    std::fclose(maps);
    arirang::log_info("wipe_duplicate_elf_copies: wiped " + std::to_string(wiped) + " residue(s)");
}

} // namespace

class ArirangZygisk final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
        if (api_ == nullptr || env_ == nullptr) {
            arirang::log_warn("onLoad: Zygisk API or JNIEnv unavailable; module disabled for this process");
            return;
        }
        // swap_own_zygisk_symbols() in the zygote: with the zygote-side
        // DLCLOSE request removed (see below), there is no deferred unloader
        // walking this mapping anymore, so scrubbing the dynstr here is safe
        // and hides the module's Zygisk entry-point symbols from the live
        // zygote image that system_server / com.android.phone inherit.
        const bool zygote = is_zygote_process();
        arirang::log_info(std::string("onLoad pid=") + std::to_string(::getpid()) +
                          (zygote ? " cmd=zygote" : " cmd=app"));
        if (zygote) {
            wipe_duplicate_elf_copies();
        }
        //
        // onLoad runs in every forked process, which is why preAppSpecialize has
        // to DLCLOSE the module out of ordinary apps below. Reading the config
        // and JNI-writing android.os.Build here therefore did both in every
        // third-party app's VM before that unload decision was made: a disk read
        // and ~15 static field writes on every cold start, plus per-process
        // identity spoofing in apps this module is explicitly not allowed to
        // touch. See the MANDATORY DESIGN COMPLIANCE note in preAppSpecialize --
        // global Build/property identity is resetprop.sh's job, and it already
        // spoofs ro.product.* and ro.build.fingerprint at boot.
        //
        // Config loading and Build spoofing now happen only in the two processes
        // the module actually stays resident in: com.android.phone and
        // system_server.
    }

    /**
     * Loads the submodule config once per process.
     *
     * Prefers direct disk config because it is available even if the Zygisk
     * companion socket is unavailable in this implementation, falling back to
     * the companion where the module process cannot read the app-owned paths.
     */
    void ensure_config_loaded() {
        if (config_loaded_) return;
        config_loaded_ = true;
        if (!arirang::load_config_from_disk(config_)) {
            arirang::load_config_from_companion(api_, config_);
        }
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        // nice_name is the process name Zygisk is about to specialize into.
        // Copy it while the JNIEnv/string is still valid; later callbacks only
        // use the cached std::string.
        current_app_process_.clear();
        current_app_package_.clear();
        current_app_timezone_.clear();
        keep_module_loaded_in_app_ = false;
        if (env_ != nullptr && args != nullptr && args->nice_name != nullptr) {
            const char *nice_name = env_->GetStringUTFChars(args->nice_name, nullptr);
            if (nice_name != nullptr) {
                current_app_process_ = nice_name;
                env_->ReleaseStringUTFChars(args->nice_name, nice_name);
            } else if (env_->ExceptionCheck()) {
                env_->ExceptionClear();
            }
        }
        // The package name comes from the app data dir, NOT the process name:
        // background/isolated/multi-process come through as "<pkg>:<service>"
        // or "<pkg>:push", so matching `nice_name` alone would miss every
        // non-main process of a spoofed app. app_data_dir is "<user-dir>/<pkg>".
        if (env_ != nullptr && args != nullptr && args->app_data_dir != nullptr) {
            const char *data_dir = env_->GetStringUTFChars(args->app_data_dir, nullptr);
            if (data_dir != nullptr) {
                const std::string dir(data_dir);
                const size_t slash = dir.find_last_of('/');
                if (slash != std::string::npos && slash + 1 < dir.size()) {
                    current_app_package_ = dir.substr(slash + 1);
                }
                env_->ReleaseStringUTFChars(args->app_data_dir, data_dir);
            } else if (env_->ExceptionCheck()) {
                env_->ExceptionClear();
            }
        }

        // Process-local presence marker, delivered ONLY to the manager app.
        //
        // Environment variables are per-process: setenv() below writes into THIS
        // forked child before the app starts, and every other app forks its own
        // zygote child that never inherits it. This is the same per-process
        // targeting Zygisk uses to decide inject-vs-skip, applied to reporting
        // "the submodule is installed" back to the manager.
        //
        // Deliberately NOT a system property or shared file: those are globally
        // readable and would leak module presence to the very apps being spoofed.
        if (args != nullptr &&
            current_app_package_ == arirang::application_id() &&
            args->uid >= 10000 && args->uid < 90000) {
            // The env var name must not exist as a literal in the module image:
            // it is the only remaining unique marker (the manager reads the
            // same runtime-assembled name in its own process). Assembled from
            // fragments like the config paths to keep the loaded image clean.
            std::string env_name;
            env_name.append("HWC_", 4);
            env_name.append("MODULE", 6);
            env_name.append("_VER", 4);
            env_name.append("SION", 4);
            setenv(env_name.c_str(), arirang::kModuleVersion, 1);
        }
        
        /* 
         * MANDATORY DESIGN COMPLIANCE: Arirang is a system-level privacy model.
         * 
         * 1. DO NOT inject hooks into arbitrary third-party applications. This avoids
         *    unnecessary performance impact and runtime behavior interference.
         * 2. Global property protection (e.g. build info, serials) MUST be handled via 
         *    system-level modifications (like resetprop in post-fs-data.sh) rather than 
         *    per-process hooks.
         * 3. Hooks are reserved EXCLUSIVELY for framework-level components that serve
         *    as data providers (e.g., com.android.phone for SIM/IMEI data).
         *
         * The per-app time zone feature (§4) is the one deliberate exception whose
         * constraint is different: it does NOT inject any hook. It performs a
         * data-only property-area CoW during the specialize callback that fires in
         * every forked process anyway, then lets DLCLOSE remove the module unless
         * keepModuleLoadedInAllApps is set (detection research: dlclose'd soinfos
         * are "leftovers" for com.reveny.nativecheck). See
         * timezone_prop_cow.cpp and the research doc.
         */
        // Config must be loaded before the keep-loaded decision: the flag lives
        // in config.json (keepModuleLoadedInAllApps). Reading it first left the
        // default (false) in place, so the scavenger never launched even when
        // the on-disk config asked for keep-loaded.
        ensure_config_loaded();
        keep_module_loaded_in_app_ = (args != nullptr &&
                                     args->uid == kAndroidPhoneUid &&
                                     current_app_process_ == "com.android.phone") ||
                                    config_.keep_module_loaded_in_all_apps;
        // Detector family must DLCLOSE: keep-loaded in that process is
        // reported as 7× "Found Injection" (isolation: disable module →
        // 0 Injection). Other apps stay mapped so ReZygisk does not leave
        // soinfo leftovers there.
        // NOTE: the scavenger must NOT be launched here (preAppSpecialize):
        // the zygote's selinux_android_setcontext() runs between
        // preAppSpecialize and postAppSpecialize and FAILS when the forked
        // child already has extra threads, aborting the zygote. The launch
        // therefore lives in postAppSpecialize, after the setcontext.
        if (is_detector_name(current_app_package_) ||
            is_detector_name(current_app_process_)) {
            keep_module_loaded_in_app_ = false;
        }

        if (!keep_module_loaded_in_app_) {
            // Ordinary app: apply the per-process timezone illusion if this
            // package has an override. The module unloads via the framework:
            // the app-side library is never present in app processes anyway,
            // and the per-app DLCLOSE is what makes ReZygisk execute the
            // deferred zygote-side unload at the first app specialization.
            constexpr jint kAidIsolatedStart = 90000;
            if (!current_app_package_.empty() && (args == nullptr || args->uid < kAidIsolatedStart)) {
                current_app_timezone_ = arirang::resolve_timezone_for_package(
                    config_, current_app_package_);
            }
            return;
        }
    }

    void preServerSpecialize(zygisk::ServerSpecializeArgs *) override {
        ensure_config_loaded();
        // Some Zygisk implementations used by KernelSU Next keep the module
        // mapped in system_server but do not reliably call postServerSpecialize.
        // Install the SensorService vtable hooks before specialization instead.
        if (config_.sensor_config_enabled) {
            arirang::install_sensor_spoofer(api_, env_, config_, true);
        }
        arirang::log_info("preServerSpecialize: installed early system_server hooks");
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *args) override {
        if (keep_module_loaded_in_app_) {
            const bool phone = args != nullptr && args->uid == kAndroidPhoneUid &&
                               current_app_process_ == "com.android.phone";
            if (phone) {
                // The phone process owns several telephony property write/read paths.
                // Keep hooks here source-level so third-party apps observe spoofed data
                // through normal framework IPC rather than by being injected.
                ensure_config_loaded();
                arirang::spoof_build_fields(env_, config_);
                arirang::install_system_property_spoofer(api_, env_, config_, true);
                arirang::log_info(std::string("installed phone process native hooks"));
                return;
            }
            // keepModuleLoadedInAllApps: stay mapped, install no app hooks.
            // Detector package name is intentionally not written here —
            // a contiguous "com.reveny.*" in the mapped image is itself
            // an injection signal.
            arirang::log_info("kept module mapped, no app hooks (keepModuleLoadedInAllApps) scavenger=off");
            return;
        }

        // Ordinary app: apply the per-process timezone illusion if this package
        // has an override, then exit the app process entirely. Keeping the
        // module mapped is reported as "Found Injection" by root detectors
        // regardless of path/name/content, and the framework's own unload
        // leaves a leftover that "Detected Zygisk" counts; with the scrubbed
        // neutral-path .so the unloaded state exposes nothing at all, so the
        // cleanest policy is to unload through the framework.
        if (!current_app_timezone_.empty()) {
            arirang::install_timezone_illusion(env_, current_app_timezone_);
        }
        // The framework's unload bookkeeping still drops two in-heap
        // \x7fELF copies of the module into the detector process; a
        // survivor thread launched here scrubs them shortly after (its
        // code lives in an anonymous region, so it outlives the dlclose).
        // The launch must stay here, AFTER the zygote's SELinux context
        // transition: an extra thread before selinux_android_setcontext
        // aborts the zygote (see the note in preAppSpecialize).
        // App-zygotes ("<package>_zygote") must also be excluded: they
        // prefix-match is_detector_name() and a raw-clone thread in the
        // app-zygote makes ART's PreZygoteFork fail with
        // "Failed to reach single-threaded state" at every child fork,
        // aborting the app-zygote and breaking all detector process spawns.
        const bool is_app_zygote = current_app_process_.size() >= 7 &&
                                   current_app_process_.compare(
                                       current_app_process_.size() - 7, 7, "_zygote") == 0;
        if (api_ != nullptr) {
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            if (!is_app_zygote &&
                (is_detector_name(current_app_package_) ||
                 is_detector_name(current_app_process_))) {
                wipe_duplicate_elf_copies();
                scrub_own_elf_header();
            }
        }
    }

    void postServerSpecialize(const zygisk::ServerSpecializeArgs *) override {
        ensure_config_loaded();
        arirang::log_info(std::string("postServerSpecialize: enter sensor_enabled=") +
                          (config_.sensor_config_enabled ? "true" : "false"));
        arirang::spoof_build_fields(env_, config_);
        arirang::install_system_property_spoofer(api_, env_, config_, true);
        if (config_.sensor_config_enabled) {
            // SensorService lives in system_server on current target builds.
            // Installing here makes sensor-list and sensor-event spoofing apply
            // to every app through the normal SensorManager service.
            arirang::install_sensor_spoofer(api_, env_, config_, true);
        } else {
            arirang::log_info("postServerSpecialize: sensor disabled by config, skipping");
        }
        arirang::log_info("installed system_server native hooks");
    }

private:
    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
    arirang::SubmoduleConfig config_;
    std::string current_app_process_;
    std::string current_app_package_;
    std::string current_app_timezone_;
    bool keep_module_loaded_in_app_ = false;
    bool config_loaded_ = false;
};

REGISTER_ZYGISK_MODULE(ArirangZygisk)
REGISTER_ZYGISK_COMPANION(arirang::companion_handler)
