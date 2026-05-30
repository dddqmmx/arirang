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
#include <linux/uio.h>
#include <string>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <unistd.h>
#include <utility>
#include <vector>

namespace {

constexpr const char *kLinkerNames[] = {
    "/system/bin/linker64",
    "/apex/com.android.runtime/bin/linker64",
    nullptr,
};

void die(const char *msg) {
    std::fprintf(stderr, "[arirang_injector] %s: %s\n", msg, std::strerror(errno));
    std::exit(2);
}

void die_no_errno(const char *msg) {
    std::fprintf(stderr, "[arirang_injector] %s\n", msg);
    std::exit(2);
}

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

const MapEntry *find_first_executable(const std::vector<MapEntry> &maps,
                                       const char *substr) {
    for (const auto &e : maps) {
        if (e.path.find(substr) == std::string::npos) continue;
        if (e.perms[2] != 'x') continue;
        return &e;
    }
    return nullptr;
}

uintptr_t resolve_dlopen_in_target(pid_t pid) {
    auto maps = read_maps(pid);

    void* sym_addr = dlsym(RTLD_DEFAULT, "dlopen");
    if (!sym_addr) {
        die_no_errno("dlsym(RTLD_DEFAULT, dlopen) failed");
    }

    Dl_info info;
    if (!dladdr(sym_addr, &info)) {
        die_no_errno("dladdr failed for dlopen");
    }

    uintptr_t local_base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    uintptr_t local_offset = reinterpret_cast<uintptr_t>(sym_addr) - local_base;

    const char* basename = std::strrchr(info.dli_fname, '/');
    basename = basename ? basename + 1 : info.dli_fname;

    const MapEntry* remote = nullptr;
    for (const auto& e : maps) {
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
        if (!remote) die_no_errno("could not find module containing dlopen in target maps");
    }

    uintptr_t remote_sym = remote->start + local_offset;

    std::fprintf(stderr,
                 "[arirang_injector] resolved dlopen @ local=%p (base=0x%lx) "
                 "remote=0x%lx (base=0x%lx)\n",
                 sym_addr, (unsigned long)local_base,
                 (unsigned long)remote_sym, (unsigned long)remote->start);
    return remote_sym;
}

const MapEntry *find_rw_scratch(const std::vector<MapEntry> &maps) {
    for (const auto &e : maps) {
        if (e.perms[0] == 'r' && e.perms[1] == 'w' &&
            e.path.find("[anon:libc_malloc]") != std::string::npos) {
            return &e;
        }
    }
    // Fallback: any rw anonymous region with size >= 256 bytes.
    for (const auto &e : maps) {
        if (e.perms[0] == 'r' && e.perms[1] == 'w' &&
            (e.end - e.start) >= 4096) {
            return &e;
        }
    }
    return nullptr;
}

void ptrace_write_memory(pid_t pid, uintptr_t addr, const void *buf, size_t len) {
    iovec local{const_cast<void *>(buf), len};
    iovec remote{reinterpret_cast<void *>(addr), len};
    ssize_t written = process_vm_writev(pid, &local, 1, &remote, 1, 0);
    if (written < 0 || static_cast<size_t>(written) != len) {
        die("process_vm_writev");
    }
}

void ptrace_read_memory(pid_t pid, uintptr_t addr, void *buf, size_t len) {
    iovec local{buf, len};
    iovec remote{reinterpret_cast<void *>(addr), len};
    ssize_t got = process_vm_readv(pid, &local, 1, &remote, 1, 0);
    if (got < 0 || static_cast<size_t>(got) != len) {
        die("process_vm_readv");
    }
}

void wait_for_stop(pid_t pid, const char *what) {
    int status = 0;
    while (true) {
        pid_t w = waitpid(pid, &status, __WALL);
        if (w < 0) {
            if (errno == EINTR) continue;
            die("waitpid");
        }
        if (WIFSTOPPED(status)) return;
        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            std::fprintf(stderr, "[arirang_injector] target died during %s\n", what);
            std::exit(3);
        }
    }
}

void get_regs(pid_t pid, user_regs_struct *regs) {
    iovec iov{regs, sizeof(*regs)};
    if (ptrace(PTRACE_GETREGSET, pid, NT_PRSTATUS, &iov) < 0) die("PTRACE_GETREGSET");
}

void set_regs(pid_t pid, user_regs_struct *regs) {
    iovec iov{regs, sizeof(*regs)};
    if (ptrace(PTRACE_SETREGSET, pid, NT_PRSTATUS, &iov) < 0) die("PTRACE_SETREGSET");
}

int do_inject(pid_t pid, const char *so_path) {
    if (ptrace(PTRACE_ATTACH, pid, 0, 0) < 0) die("PTRACE_ATTACH");
    wait_for_stop(pid, "attach");

    user_regs_struct saved{};
    get_regs(pid, &saved);

    auto maps = read_maps(pid);
    const MapEntry *scratch = find_rw_scratch(maps);
    if (scratch == nullptr) {
        ptrace(PTRACE_DETACH, pid, 0, 0);
        die_no_errno("could not find rw scratch region in target");
    }
    // Use the END of the rw region (minus a buffer) for our string. Less
    // likely to collide with active allocations.
    const size_t path_len = std::strlen(so_path) + 1;
    const uintptr_t string_addr = scratch->end - 0x100 - path_len;
    if (string_addr < scratch->start) {
        ptrace(PTRACE_DETACH, pid, 0, 0);
        die_no_errno("scratch region too small");
    }

    // Save original bytes so we can restore them after the call.
    std::vector<uint8_t> saved_bytes(path_len);
    ptrace_read_memory(pid, string_addr, saved_bytes.data(), path_len);
    ptrace_write_memory(pid, string_addr, so_path, path_len);

    const uintptr_t dlopen_addr = resolve_dlopen_in_target(pid);

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

    set_regs(pid, &regs);

    if (ptrace(PTRACE_CONT, pid, 0, 0) < 0) die("PTRACE_CONT");
    wait_for_stop(pid, "remote call");

    user_regs_struct after{};
    get_regs(pid, &after);
    const uintptr_t dl_result = static_cast<uintptr_t>(after.regs[0]);
    std::fprintf(stderr, "[arirang_injector] dlopen returned 0x%lx (pc=0x%llx)\n",
                 (unsigned long)dl_result,
                 (unsigned long long)after.pc);

    // Restore scratch bytes and original registers.
    ptrace_write_memory(pid, string_addr, saved_bytes.data(), path_len);
    set_regs(pid, &saved);

    if (ptrace(PTRACE_DETACH, pid, 0, 0) < 0) die("PTRACE_DETACH");

    if (dl_result == 0) {
        std::fprintf(stderr, "[arirang_injector] dlopen returned NULL\n");
        return 4;
    }
    return 0;
}

} // namespace

int main(int argc, char **argv) {
    if (argc != 3) {
        std::fprintf(stderr, "usage: %s <pid> <absolute-path-to-.so>\n", argv[0]);
        return 1;
    }
    const pid_t pid = static_cast<pid_t>(std::atoi(argv[1]));
    const char *path = argv[2];
    if (pid <= 0) {
        std::fprintf(stderr, "invalid pid\n");
        return 1;
    }
    return do_inject(pid, path);
}
