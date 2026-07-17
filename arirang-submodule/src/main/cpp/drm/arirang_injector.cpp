// arirang_injector
//
// Standalone ARM64 binary that uses ptrace to remote-load a .so into an
// existing native daemon process. Designed for one job only: inject
// libarirang_drm_hook.so into the Widevine DRM HAL daemon.
//
// Algorithm:
//   1. ptrace(PTRACE_ATTACH, pid).
//   2. Locate dlopen()/__loader_dlopen() in the target's address space by
//      walking /proc/<pid>/maps to find linker, plus a local dlsym() of
//      the same linker symbol to compute the local offset, then translate
//      that offset into the remote linker's base.
//   3. Save target registers, allocate a small scratch area inside an
//      existing rw mapping in the target, write the path string there, set
//      up x0/x1/x30 to call dlopen(path, RTLD_NOW), then PTRACE_CONT.
//   4. Catch the SIGSEGV from x30=0 to know the call returned; read x0 for
//      the dlopen() return value.
//   5. Restore registers, PTRACE_DETACH.
//
// Every step from PTRACE_ATTACH onward is guarded by PtraceGuard: whatever
// register/memory state has been saved so far is restored and the tracee is
// detached on any exit from do_inject(), success or failure alike, instead
// of relying on manual cleanup duplicated at each error site.
//
// Errors are reported on stderr and propagated as non-zero exit codes.

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <string>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <unistd.h>
#include <utility>
#include <vector>

#include "json.hpp"

namespace {

constexpr const char *kLinkerNames[] = {
    "/system/bin/linker64",
    "/apex/com.android.runtime/bin/linker64",
    nullptr,
};

struct MapEntry {
    uintptr_t start;
    uintptr_t end;
    char perms[5];
    std::string path;
};

std::string read_file(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return {};
    std::string out;
    char buf[4096];
    while (true) {
        ssize_t n = read(fd, buf, sizeof(buf));
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (n == 0) break;
        out.append(buf, static_cast<size_t>(n));
    }
    close(fd);
    return out;
}

std::vector<MapEntry> read_maps(pid_t pid) {
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    std::string content = read_file(path);
    std::vector<MapEntry> out;
    size_t pos = 0;
    while (pos < content.size()) {
        size_t newline = content.find('\n', pos);
        if (newline == std::string::npos) newline = content.size();
        const std::string line = content.substr(pos, newline - pos);
        pos = newline + 1;
        if (line.empty()) continue;

        MapEntry e{};
        unsigned long long start = 0, end = 0;
        char perms[8] = {};
        char buf[1024] = {};
        const int n = std::sscanf(line.c_str(), "%llx-%llx %7s %*x %*s %*u %1023[^\n]",
                                   &start, &end, perms, buf);
        if (n < 3) continue;
        e.start = static_cast<uintptr_t>(start);
        e.end = static_cast<uintptr_t>(end);
        std::strncpy(e.perms, perms, sizeof(e.perms) - 1);
        if (n >= 4) {
            // sscanf %[^\n] keeps leading spaces; trim them.
            const char *p = buf;
            while (*p == ' ' || *p == '\t') ++p;
            e.path = p;
        }
        out.push_back(std::move(e));
    }
    return out;
}

// Mirrors module/service.sh's arirang_pid_start_time(): strip through the
// LAST ") " in the stat line (comm can itself contain ')'), then take the
// 20th whitespace-separated field of the remainder, which is proc(5)'s
// starttime field (field 22 of the whole line). Used to re-verify identity
// after PTRACE_ATTACH has stopped the process.
bool read_pid_start_time(pid_t pid, long long *out) {
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    const std::string content = read_file(path);
    if (content.empty()) return false;

    const size_t rparen = content.rfind(") ");
    if (rparen == std::string::npos) return false;
    const std::string remainder = content.substr(rparen + 2);

    size_t pos = 0;
    std::string field;
    for (int i = 0; i < 20; ++i) {
        while (pos < remainder.size() && (remainder[pos] == ' ' || remainder[pos] == '\t')) ++pos;
        const size_t start = pos;
        while (pos < remainder.size() && remainder[pos] != ' ' && remainder[pos] != '\t') ++pos;
        if (start == pos) return false;
        field = remainder.substr(start, pos - start);
    }
    if (field.empty()) return false;
    for (char c : field) {
        if (c < '0' || c > '9') return false;
    }

    errno = 0;
    char *end = nullptr;
    const long long value = std::strtoll(field.c_str(), &end, 10);
    if (errno != 0 || end == field.c_str() || *end != '\0') return false;
    *out = value;
    return true;
}

const MapEntry *find_first_executable(const std::vector<MapEntry> &maps,
                                       const char *substr) {
    for (const auto &e : maps) {
        if (e.path.find(substr) == std::string::npos) continue;
        if (e.perms[2] != 'x') continue;
        return &e;
    }
    return nullptr;
}

// Returns 0 on failure (never a valid resolved symbol address). Only reads
// local process info and the target's /proc/<pid>/maps; does not touch
// target registers or memory, so it is safe to call without any ptrace
// cleanup obligations of its own.
uintptr_t resolve_dlopen_in_target(pid_t pid) {
    auto maps = read_maps(pid);

    // Resolve the symbol in our own process first, then translate by offset
    // into the target process. Android's linker is mapped at a different base
    // per process, but the relative offset of dlopen inside the same linker
    // image is stable.
    void *sym_addr = dlsym(RTLD_DEFAULT, "dlopen");
    if (!sym_addr) {
        std::fprintf(stderr, "[arirang_injector] dlsym(RTLD_DEFAULT, dlopen) failed\n");
        return 0;
    }

    Dl_info info;
    if (!dladdr(sym_addr, &info)) {
        std::fprintf(stderr, "[arirang_injector] dladdr failed for dlopen\n");
        return 0;
    }

    uintptr_t local_base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    uintptr_t local_offset = reinterpret_cast<uintptr_t>(sym_addr) - local_base;

    const char *basename = std::strrchr(info.dli_fname, '/');
    basename = basename ? basename + 1 : info.dli_fname;

    const MapEntry *remote = nullptr;
    // Prefer the same mapped object that supplied our local dlopen. On Android
    // this is normally linker64 or the runtime APEX linker. If basename lookup
    // fails due to path differences, fall back to known linker paths.
    for (const auto &e : maps) {
        if (e.path.find(basename) != std::string::npos) {
            if (!remote || e.start < remote->start) {
                remote = &e;
            }
        }
    }

    if (!remote) {
        for (const char *const *name = kLinkerNames; *name != nullptr && !remote; ++name) {
            remote = find_first_executable(maps, *name);
        }
        if (!remote) {
            std::fprintf(stderr,
                         "[arirang_injector] could not find module containing dlopen in target maps\n");
            return 0;
        }
    }

    uintptr_t remote_sym = remote->start + local_offset;

    std::fprintf(stderr,
                 "[arirang_injector] resolved dlopen @ local=%p (base=0x%lx) "
                 "remote=0x%lx (base=0x%lx)\n",
                 sym_addr, (unsigned long)local_base,
                 (unsigned long)remote_sym, (unsigned long)remote->start);
    return remote_sym;
}

// Requires every candidate region to hold at least path_len + 0x100 bytes so
// the caller's `end - 0x100 - path_len` computation can never underflow,
// regardless of which branch below selects the region.
const MapEntry *find_rw_scratch(const std::vector<MapEntry> &maps, size_t path_len) {
    const size_t floor = path_len + 0x100;
    const size_t fallback_floor = floor > 4096 ? floor : 4096;

    // Reuse existing writable memory instead of making a remote mmap call.
    // That keeps the remote call setup to a single dlopen invocation and avoids
    // needing to resolve two libc/linker symbols in the target.
    for (const auto &e : maps) {
        if (e.perms[0] == 'r' && e.perms[1] == 'w' &&
            e.path.find("[anon:libc_malloc]") != std::string::npos &&
            (e.end - e.start) >= floor) {
            return &e;
        }
    }
    // Fallback: any sufficiently large rw anonymous region.
    for (const auto &e : maps) {
        if (e.perms[0] == 'r' && e.perms[1] == 'w' &&
            (e.end - e.start) >= fallback_floor) {
            return &e;
        }
    }
    return nullptr;
}

bool ptrace_write_memory(pid_t pid, uintptr_t addr, const void *buf, size_t len) {
    iovec local{const_cast<void *>(buf), len};
    iovec remote{reinterpret_cast<void *>(addr), len};
    ssize_t written = process_vm_writev(pid, &local, 1, &remote, 1, 0);
    return written >= 0 && static_cast<size_t>(written) == len;
}

bool ptrace_read_memory(pid_t pid, uintptr_t addr, void *buf, size_t len) {
    iovec local{buf, len};
    iovec remote{reinterpret_cast<void *>(addr), len};
    ssize_t got = process_vm_readv(pid, &local, 1, &remote, 1, 0);
    return got >= 0 && static_cast<size_t>(got) == len;
}

enum class WaitOutcome { kStopped, kExited, kWaitFailed };

WaitOutcome wait_for_stop(pid_t pid) {
    int status = 0;
    while (true) {
        pid_t w = waitpid(pid, &status, __WALL);
        if (w < 0) {
            if (errno == EINTR) continue;
            return WaitOutcome::kWaitFailed;
        }
        if (WIFSTOPPED(status)) return WaitOutcome::kStopped;
        if (WIFEXITED(status) || WIFSIGNALED(status)) return WaitOutcome::kExited;
    }
}

bool get_regs(pid_t pid, user_regs_struct *regs) {
    iovec iov{regs, sizeof(*regs)};
    return ptrace(PTRACE_GETREGSET, pid, NT_PRSTATUS, &iov) >= 0;
}

bool set_regs(pid_t pid, user_regs_struct *regs) {
    iovec iov{regs, sizeof(*regs)};
    return ptrace(PTRACE_SETREGSET, pid, NT_PRSTATUS, &iov) >= 0;
}

// Restores whatever register/scratch-memory state has been recorded so far
// and detaches, on ANY exit from do_inject() (success, error return, or an
// exception unwind). This replaces the previous pattern of calling
// exit()/die() directly from deep call sites, which skipped this cleanup
// entirely and could leave a live, system-critical daemon's registers
// pointed mid-call at dlopen with no restore and no detach.
class PtraceGuard {
public:
    explicit PtraceGuard(pid_t pid) : pid_(pid) {}
    PtraceGuard(const PtraceGuard &) = delete;
    PtraceGuard &operator=(const PtraceGuard &) = delete;

    ~PtraceGuard() {
        if (!attached_) return;
        // Best-effort: if the target has already died, these all fail
        // harmlessly (ESRCH) and PTRACE_DETACH below is a no-op as well.
        if (scratch_saved_) {
            iovec local{scratch_bytes_.data(), scratch_bytes_.size()};
            iovec remote{reinterpret_cast<void *>(scratch_addr_), scratch_bytes_.size()};
            process_vm_writev(pid_, &local, 1, &remote, 1, 0);
        }
        if (regs_saved_) {
            iovec iov{&saved_regs_, sizeof(saved_regs_)};
            ptrace(PTRACE_SETREGSET, pid_, NT_PRSTATUS, &iov);
        }
        ptrace(PTRACE_DETACH, pid_, 0, 0);
    }

    void mark_attached() { attached_ = true; }

    void save_regs(const user_regs_struct &regs) {
        saved_regs_ = regs;
        regs_saved_ = true;
    }

    void save_scratch(uintptr_t addr, std::vector<uint8_t> bytes) {
        scratch_addr_ = addr;
        scratch_bytes_ = std::move(bytes);
        scratch_saved_ = true;
    }

private:
    pid_t pid_;
    bool attached_ = false;
    bool regs_saved_ = false;
    bool scratch_saved_ = false;
    user_regs_struct saved_regs_{};
    uintptr_t scratch_addr_ = 0;
    std::vector<uint8_t> scratch_bytes_;
};

int do_inject(pid_t pid, const char *so_path, const long long *expected_start_time) {
    if (ptrace(PTRACE_ATTACH, pid, 0, 0) < 0) {
        std::fprintf(stderr, "[arirang_injector] PTRACE_ATTACH: %s\n", std::strerror(errno));
        return 2;
    }
    PtraceGuard guard(pid);
    guard.mark_attached();

    const WaitOutcome attach_wait = wait_for_stop(pid);
    if (attach_wait == WaitOutcome::kExited) {
        std::fprintf(stderr, "[arirang_injector] target died during attach\n");
        return 3;
    }
    if (attach_wait == WaitOutcome::kWaitFailed) {
        std::fprintf(stderr, "[arirang_injector] waitpid: %s\n", std::strerror(errno));
        return 2;
    }

    // The shell caller (module/service.sh) verifies process identity/start
    // time immediately before exec, but a PID can still be reused by an
    // unrelated process in the window before PTRACE_ATTACH actually stops
    // it. When the caller supplies the expected start time, repeat the
    // check now that the process is guaranteed stopped.
    if (expected_start_time != nullptr) {
        long long actual_start = 0;
        if (!read_pid_start_time(pid, &actual_start) || actual_start != *expected_start_time) {
            std::fprintf(stderr,
                         "[arirang_injector] pid start time changed after attach; aborting\n");
            return 6;
        }
    }

    // Registers and scratch bytes are restored by PtraceGuard even if dlopen
    // returns NULL. The only lasting side effect intended here is the
    // dynamic library mapping created by a successful dlopen.
    user_regs_struct saved{};
    if (!get_regs(pid, &saved)) {
        std::fprintf(stderr, "[arirang_injector] PTRACE_GETREGSET: %s\n", std::strerror(errno));
        return 2;
    }
    guard.save_regs(saved);

    auto maps = read_maps(pid);
    const size_t path_len = std::strlen(so_path) + 1;
    const MapEntry *scratch = find_rw_scratch(maps, path_len);
    if (scratch == nullptr) {
        std::fprintf(stderr, "[arirang_injector] could not find rw scratch region in target\n");
        return 2;
    }
    // Use the END of the rw region (minus a buffer) for our string. Less
    // likely to collide with active allocations. find_rw_scratch() already
    // guarantees the region is large enough that this cannot underflow.
    const uintptr_t string_addr = scratch->end - 0x100 - path_len;
    if (string_addr < scratch->start) {
        std::fprintf(stderr, "[arirang_injector] scratch region too small\n");
        return 2;
    }

    // Save original bytes so we can restore them after the call.
    std::vector<uint8_t> saved_bytes(path_len);
    if (!ptrace_read_memory(pid, string_addr, saved_bytes.data(), path_len)) {
        std::fprintf(stderr, "[arirang_injector] process_vm_readv: %s\n", std::strerror(errno));
        return 2;
    }
    if (!ptrace_write_memory(pid, string_addr, so_path, path_len)) {
        std::fprintf(stderr, "[arirang_injector] process_vm_writev: %s\n", std::strerror(errno));
        return 2;
    }
    guard.save_scratch(string_addr, saved_bytes);

    const uintptr_t dlopen_addr = resolve_dlopen_in_target(pid);
    if (dlopen_addr == 0) {
        std::fprintf(stderr, "[arirang_injector] failed to resolve dlopen in target\n");
        return 2;
    }

    user_regs_struct regs = saved;
    regs.regs[0] = string_addr;       // x0 = path
    regs.regs[1] = RTLD_NOW;           // x1 = flags
    // __loader_dlopen takes (path, flags, caller). caller=NULL is fine; the
    // bionic loader treats NULL as system caller. For public dlopen we only
    // need two args; the extra register is harmless.
    regs.regs[2] = 0;
    regs.regs[30] = 0;                 // LR=0 -> SIGSEGV when call returns
    regs.pc = dlopen_addr;
    // Realign SP to 16 bytes (AArch64 ABI requirement).
    regs.sp &= ~static_cast<uint64_t>(0xF);

    if (!set_regs(pid, &regs)) {
        std::fprintf(stderr, "[arirang_injector] PTRACE_SETREGSET: %s\n", std::strerror(errno));
        return 2;
    }

    // We do not plant a breakpoint instruction. LR=0 makes the remote call
    // fault after dlopen returns, which gives us a reliable stop point while
    // leaving target code pages untouched.
    if (ptrace(PTRACE_CONT, pid, 0, 0) < 0) {
        std::fprintf(stderr, "[arirang_injector] PTRACE_CONT: %s\n", std::strerror(errno));
        return 2;
    }

    const WaitOutcome call_wait = wait_for_stop(pid);
    if (call_wait == WaitOutcome::kExited) {
        std::fprintf(stderr, "[arirang_injector] target died during remote call\n");
        return 3;
    }
    if (call_wait == WaitOutcome::kWaitFailed) {
        std::fprintf(stderr, "[arirang_injector] waitpid: %s\n", std::strerror(errno));
        return 2;
    }

    user_regs_struct after{};
    if (!get_regs(pid, &after)) {
        std::fprintf(stderr, "[arirang_injector] PTRACE_GETREGSET (post-call): %s\n",
                     std::strerror(errno));
        return 2;
    }
    const uintptr_t dl_result = static_cast<uintptr_t>(after.regs[0]);
    std::fprintf(stderr, "[arirang_injector] dlopen returned 0x%lx (pc=0x%llx)\n",
                 (unsigned long)dl_result,
                 (unsigned long long)after.pc);

    // Falling out of scope here runs PtraceGuard's destructor, which
    // restores the saved scratch bytes and registers and detaches.
    if (dl_result == 0) {
        std::fprintf(stderr, "[arirang_injector] dlopen returned NULL\n");
        return 4;
    }
    return 0;
}

int do_config(const char *path, const char *key) {
    // service.sh uses this lightweight JSON accessor to avoid carrying shell
    // JSON parsing assumptions. It intentionally supports only top-level keys,
    // matching the flat config snapshot written by the manager app.
    std::string content = read_file(path);
    if (content.empty()) return 10;
    try {
        auto j = nlohmann::json::parse(content);
        if (j.contains(key)) {
            auto val = j[key];
            if (val.is_string()) std::printf("%s", val.get<std::string>().c_str());
            else if (val.is_boolean()) std::printf("%s", val.get<bool>() ? "true" : "false");
            else if (val.is_number()) std::printf("%s", val.dump().c_str());
            else std::printf("%s", val.dump().c_str());
            return 0;
        }
    } catch (const std::exception &e) {
        std::fprintf(stderr, "[arirang_injector] config error: %s\n", e.what());
    }
    return 11;
}

} // namespace

int main(int argc, char **argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage:\n");
        std::fprintf(stderr, "  %s <pid> <absolute-path-to-.so> [expected-start-time]\n", argv[0]);
        std::fprintf(stderr, "  %s config <path> <key>\n", argv[0]);
        return 1;
    }

    if (std::strcmp(argv[1], "config") == 0) {
        if (argc != 4) {
            std::fprintf(stderr, "usage: %s config <path> <key>\n", argv[0]);
            return 1;
        }
        return do_config(argv[2], argv[3]);
    }

    if (argc != 3 && argc != 4) {
        std::fprintf(stderr, "usage: %s <pid> <absolute-path-to-.so> [expected-start-time]\n",
                     argv[0]);
        return 1;
    }

    const pid_t pid = static_cast<pid_t>(std::atoi(argv[1]));
    const char *path = argv[2];
    if (pid <= 0) {
        std::fprintf(stderr, "invalid pid\n");
        return 1;
    }

    long long expected_start = 0;
    const long long *expected_start_ptr = nullptr;
    if (argc == 4) {
        const char *start_str = argv[3];
        if (*start_str == '\0') {
            std::fprintf(stderr, "invalid start time argument\n");
            return 1;
        }
        for (const char *p = start_str; *p != '\0'; ++p) {
            if (*p < '0' || *p > '9') {
                std::fprintf(stderr, "invalid start time argument\n");
                return 1;
            }
        }
        errno = 0;
        char *end = nullptr;
        expected_start = std::strtoll(start_str, &end, 10);
        if (errno != 0 || end == start_str || *end != '\0') {
            std::fprintf(stderr, "invalid start time argument\n");
            return 1;
        }
        expected_start_ptr = &expected_start;
    }

    return do_inject(pid, path, expected_start_ptr);
}
