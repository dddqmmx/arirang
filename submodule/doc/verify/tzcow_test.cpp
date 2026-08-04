// tzcow_test — device verification for per-process timezone_prop CoW spoofing.
//
// Proves the §12 "Truman-world" mechanism from timezone_per_app_research.md:
//   1. Locate the mapped property-context file that backs persist.sys.timezone.
//   2. Replace the SHARED mapping with a private MAP_PRIVATE|MAP_FIXED copy.
//   3. Patch the prop_info value + serial inside the private copy.
//   4. Read back through every bionic path: __system_property_get,
//      __system_property_read, __system_property_read_callback.
//   5. Show that an exec'd child (getprop) still sees the REAL value — i.e. the
//      CoW is strictly process-local and does not even leak to fork+exec.
//
// Build (host): $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang++
// Run (device): as root, so it can open the property context file O_RDONLY.

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <sys/system_properties.h>

namespace {

constexpr const char* kKey = "persist.sys.timezone";

// Find the [start,end) VMA and pathname that contains address `p`.
bool find_mapping(const void* p, uintptr_t& start, uintptr_t& end, char* path, size_t path_len) {
    FILE* f = fopen("/proc/self/maps", "re");
    if (!f) return false;
    char line[512];
    const uintptr_t addr = reinterpret_cast<uintptr_t>(p);
    bool found = false;
    while (fgets(line, sizeof(line), f)) {
        uintptr_t s, e;
        char perms[8] = {0};
        int name_off = 0;
        // format: start-end perms offset dev inode pathname
        if (sscanf(line, "%lx-%lx %7s %*x %*x:%*x %*d %n", &s, &e, perms, &name_off) >= 3) {
            if (addr >= s && addr < e) {
                start = s; end = e;
                const char* nm = line + name_off;
                // strip trailing newline
                size_t n = strlen(nm);
                while (n && (nm[n-1] == '\n' || nm[n-1] == ' ')) n--;
                if (n >= path_len) n = path_len - 1;
                memcpy(path, nm, n);
                path[n] = '\0';
                fprintf(stderr, "[maps] %lx-%lx %s %s\n", s, e, perms, path);
                found = true;
                break;
            }
        }
    }
    fclose(f);
    return found;
}

void read_all_paths(const char* tag) {
    char v[PROP_VALUE_MAX] = {0};
    int n = __system_property_get(kKey, v);
    printf("  %-26s __system_property_get   = \"%s\" (len=%d)\n", tag, v, n);

    const prop_info* pi = __system_property_find(kKey);
    if (pi) {
        char name[PROP_NAME_MAX] = {0};
        char val[PROP_VALUE_MAX] = {0};
        int rn = __system_property_read(pi, name, val);
        printf("  %-26s __system_property_read  = name=\"%s\" val=\"%s\" (len=%d)\n", tag, name, val, rn);

        struct CbCtx { char buf[PROP_VALUE_MAX]; uint32_t serial; } ctx{};
        __system_property_read_callback(pi, [](void* c, const char*, const char* value, uint32_t serial) {
            auto* x = static_cast<CbCtx*>(c);
            strncpy(x->buf, value, PROP_VALUE_MAX - 1);
            x->serial = serial;
        }, &ctx);
        printf("  %-26s read_callback           = \"%s\" (serial=0x%08x, len=%u)\n",
               tag, ctx.buf, ctx.serial, ctx.serial >> 24);
    }
}

} // namespace

int main(int argc, char** argv) {
    const char* spoof = (argc > 1) ? argv[1] : "America/New_York";
    printf("=== tzcow_test: spoof persist.sys.timezone -> \"%s\" (this process only) ===\n", spoof);

    printf("[1] BEFORE (real, shared mapping):\n");
    read_all_paths("before");

    const prop_info* pi = __system_property_find(kKey);
    if (!pi) { fprintf(stderr, "FATAL: property not found\n"); return 1; }

    // Locate the context file mapping that holds this prop_info.
    uintptr_t start = 0, end = 0;
    char path[256] = {0};
    if (!find_mapping(pi, start, end, path, sizeof(path))) {
        fprintf(stderr, "FATAL: could not find mapping for prop_info %p\n", pi);
        return 2;
    }
    const size_t len = end - start;
    printf("[2] prop_info=%p lives in %s [%zu bytes]\n", (const void*)pi, path, len);

    // Open the same context file and replace the shared mapping with a private
    // CoW copy at the exact same address. MAP_FIXED makes the swap atomic; the
    // global shared file/pages are untouched, so every other process keeps the
    // real value.
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) { perror("open ctx file"); return 3; }
    void* m = mmap(reinterpret_cast<void*>(start), len, PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_FIXED, fd, 0);
    close(fd);
    if (m == MAP_FAILED) { perror("mmap MAP_PRIVATE|MAP_FIXED"); return 4; }
    printf("[3] remapped %s as MAP_PRIVATE (CoW) at %p\n", path, m);

    // Patch the prop_info. Layout (bionic, short prop):
    //   offset 0  : atomic uint32 serial   (bits 24..31 = value length)
    //   offset 4  : char value[PROP_VALUE_MAX==92]
    //   offset 96 : char name[]            (flexible)
    auto* base = reinterpret_cast<uint8_t*>(const_cast<prop_info*>(pi));
    uint32_t old_serial;
    memcpy(&old_serial, base, sizeof(old_serial));
    char* value_field = reinterpret_cast<char*>(base + sizeof(uint32_t));
    const char* name_field = reinterpret_cast<const char*>(base + sizeof(uint32_t) + PROP_VALUE_MAX);
    printf("[4] pre-patch: serial=0x%08x value=\"%s\" name@96=\"%s\"\n",
           old_serial, value_field, name_field);

    const size_t slen = strlen(spoof);
    if (slen >= PROP_VALUE_MAX) { fprintf(stderr, "spoof too long\n"); return 5; }
    memset(value_field, 0, PROP_VALUE_MAX);
    memcpy(value_field, spoof, slen);
    // New serial: length in top byte, bump change counter, keep dirty bit (0) clear.
    uint32_t new_serial = (static_cast<uint32_t>(slen) << 24) |
                          (((old_serial & 0x00ffffff) + 2) & 0x00fffffe);
    memcpy(base, &new_serial, sizeof(new_serial));
    printf("[5] post-patch: serial=0x%08x value=\"%s\"\n", new_serial, value_field);

    // Optional: restore read-only protection so maps perms look normal (r--p).
    mprotect(reinterpret_cast<void*>(start), len, PROT_READ);

    printf("[6] AFTER (private copy, this process):\n");
    read_all_paths("after");

    // Prove locality: a fresh exec'd getprop re-maps the REAL global file.
    printf("[7] exec'd child `getprop %s` (fresh libc, real global):\n", kKey);
    fflush(stdout);
    char cmd[128];
    snprintf(cmd, sizeof(cmd), "getprop %s", kKey);
    int rc = system(cmd);
    (void)rc;

    printf("=== done. In-process reads should be \"%s\"; child getprop should be real. ===\n", spoof);
    return 0;
}
