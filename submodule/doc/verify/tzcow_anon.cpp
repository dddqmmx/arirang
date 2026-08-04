// tzcow_anon — label-independent per-process timezone_prop spoof.
//
// Unlike tzcow_test (which open()s the context file and mmap(MAP_PRIVATE,fd)),
// this variant never opens the file. It:
//   1. reads the process's OWN inherited PROT_READ mapping of the prop area,
//   2. copies it into a scratch buffer,
//   3. replaces the mapping with MAP_ANONYMOUS|MAP_PRIVATE|MAP_FIXED,
//   4. restores the bytes and patches persist.sys.timezone,
//   5. mprotect()s back to PROT_READ.
//
// Needs NO SELinux file open/map on timezone_prop, NO root — only the ability
// to read your own memory and make anonymous mappings, which every app domain
// has. This is why it is robust even when the context file is mislabeled
// (as it currently is on this device from a prior broken bind experiment).

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>
#include <sys/system_properties.h>

namespace {
constexpr const char* kKey = "persist.sys.timezone";

bool find_mapping(const void* p, uintptr_t& start, uintptr_t& end, char* path, size_t path_len) {
    FILE* f = fopen("/proc/self/maps", "re");
    if (!f) return false;
    char line[512];
    const uintptr_t addr = reinterpret_cast<uintptr_t>(p);
    bool found = false;
    while (fgets(line, sizeof(line), f)) {
        uintptr_t s, e; char perms[8] = {0}; int name_off = 0;
        if (sscanf(line, "%lx-%lx %7s %*x %*x:%*x %*d %n", &s, &e, perms, &name_off) >= 3) {
            if (addr >= s && addr < e) {
                start = s; end = e;
                const char* nm = line + name_off;
                size_t n = strlen(nm);
                while (n && (nm[n-1] == '\n' || nm[n-1] == ' ')) n--;
                if (n >= path_len) n = path_len - 1;
                memcpy(path, nm, n); path[n] = '\0';
                fprintf(stderr, "[maps] %lx-%lx %s %s\n", s, e, perms, path);
                found = true; break;
            }
        }
    }
    fclose(f);
    return found;
}

void read_all(const char* tag) {
    char v[PROP_VALUE_MAX] = {0};
    int n = __system_property_get(kKey, v);
    printf("  %-7s get=\"%s\"(%d)", tag, v, n);
    const prop_info* pi = __system_property_find(kKey);
    if (pi) {
        struct { char buf[PROP_VALUE_MAX]; } c{};
        __system_property_read_callback(pi, [](void* x, const char*, const char* val, uint32_t){
            strncpy(static_cast<decltype(&c)>(x)->buf, val, PROP_VALUE_MAX-1);
        }, &c);
        printf("  cb=\"%s\"\n", c.buf);
    } else printf("\n");
}
} // namespace

int main(int argc, char** argv) {
    const char* spoof = (argc > 1) ? argv[1] : "Europe/London";
    printf("=== tzcow_anon: %s (anonymous CoW, no file open) ===\n", spoof);
    printf("[1] BEFORE: "); read_all("before");

    const prop_info* pi = __system_property_find(kKey);
    if (!pi) { fprintf(stderr, "not found\n"); return 1; }
    uintptr_t start = 0, end = 0; char path[256] = {0};
    if (!find_mapping(pi, start, end, path, sizeof(path))) { fprintf(stderr, "no map\n"); return 2; }
    const size_t len = end - start;

    // 1. snapshot the current (readable, inherited) contents
    void* save = malloc(len);
    if (!save) { perror("malloc"); return 3; }
    memcpy(save, reinterpret_cast<void*>(start), len);

    // 2. replace the file mapping with a private anonymous one at the SAME addr
    void* m = mmap(reinterpret_cast<void*>(start), len, PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
    if (m == MAP_FAILED) { perror("mmap anon MAP_FIXED"); return 4; }

    // 3. restore contents into the anonymous copy
    memcpy(m, save, len);
    free(save);
    printf("[2] replaced mapping with MAP_ANONYMOUS private copy at %p (%zu bytes)\n", m, len);

    // 4. patch prop_info (serial@0, value@4, name@96)
    auto* base = reinterpret_cast<uint8_t*>(const_cast<prop_info*>(pi));
    uint32_t old_serial; memcpy(&old_serial, base, 4);
    char* value_field = reinterpret_cast<char*>(base + 4);
    const size_t slen = strlen(spoof);
    memset(value_field, 0, PROP_VALUE_MAX);
    memcpy(value_field, spoof, slen);
    uint32_t new_serial = (static_cast<uint32_t>(slen) << 24) |
                          (((old_serial & 0x00ffffff) + 2) & 0x00fffffe);
    memcpy(base, &new_serial, 4);
    mprotect(reinterpret_cast<void*>(start), len, PROT_READ);
    printf("[3] patched: name@96=\"%s\" serial 0x%08x->0x%08x\n",
           reinterpret_cast<const char*>(base + 4 + PROP_VALUE_MAX), old_serial, new_serial);

    printf("[4] AFTER:  "); read_all("after");
    printf("[5] child getprop (fresh libc): "); fflush(stdout);
    char cmd[128]; snprintf(cmd, sizeof(cmd), "getprop %s", kKey);
    int rc = system(cmd); (void)rc;
    return 0;
}
