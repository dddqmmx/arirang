// libarirang_drm_hook.so
//
// LD_PRELOAD'd into the Widevine DRM HAL daemon (system layer). Reads a
// spoofed device unique id from a HAL-readable config file (written by the
// Magisk module's post-fs-data.sh on each boot, sourced from the Arirang
// app's own config). Locates libwvhidl.so / libwvdrmengine.so, finds HIDL
// or AIDL getPropertyByteArray symbols via the dynamic symbol table, then
// hooks them by rewriting vtable / method-table slots instead of patching
// machine code. This avoids fragile prologue analysis, PC-relative
// instruction checks and PAC (Pointer Authentication) issues.
//
// This .so is NEVER loaded into ordinary app processes - only into native
// DRM HAL daemons via init.rc `setenv LD_PRELOAD`.

#include "logging.hpp"
#include "symbol_resolver.hpp"
#include "vtable_hook.hpp"

#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <pthread.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

// Hidden C-linkage globals storing the original function pointers captured by
// the vtable hook. The AIDL and HIDL entry points call through these pointers
// to reach the real HAL implementation.
#define ARIRANG_HIDDEN __attribute__((visibility("hidden")))
extern "C" ARIRANG_HIDDEN void *g_trampoline = nullptr;
extern "C" ARIRANG_HIDDEN void *g_hidl_trampoline = nullptr;

extern "C" ARIRANG_HIDDEN void arirang_drm_aidl_post(void *this_ptr,
                                                      const void *name_ptr,
                                                      void *vec_ptr);
struct AIDL_ScopedAStatus;
AIDL_ScopedAStatus arirang_drm_aidl_entry(void* this_ptr, void* name_ptr, void* vec_ptr);

static const char *g_hook_method = nullptr;

#include <functional>

namespace hidl {
    struct string {
        const char* mBuffer;
        uint32_t mSize;
        bool mOwnsBuffer;
    };
    template<typename T>
    struct vec {
        T* mBuffer;
        uint32_t mSize;
        bool mOwnsBuffer;
        vec(T* buf, uint32_t sz, bool owns) : mBuffer(buf), mSize(sz), mOwnsBuffer(owns) {}
    };
    enum class Status : uint32_t {
        OK = 0,
        ERROR_DRM_UNKNOWN = 1,
        // ... other error codes
    };
}

using HidlCallback = std::function<void(hidl::Status, const hidl::vec<uint8_t>&)>;

// The real HIDL method returns android::hardware::Return<void>, a trivially
// destructible 32-byte-ish struct.  We use a sufficiently large opaque struct
// with the same AArch64 ABI (returned indirectly via x8) so the compiler
// preserves and forwards the caller's hidden return pointer to the original
// function.  Returning void made x8 garbage, which caused the original HAL
// function to crash while zeroing its Return<void>.
struct HidlReturnVoid {
    alignas(16) unsigned char payload[64];
};

using HidlGetPropertyByteArrayFunc = HidlReturnVoid (*)(void* this_ptr,
                                                        const hidl::string& name,
                                                        HidlCallback cb);

HidlReturnVoid arirang_drm_hidl_hook(void* this_ptr, const hidl::string& name, HidlCallback cb);

namespace {

constexpr const char *kSpoofIdPath = "/data/adb/modules/arirang-submodule/runtime/widevine_id";
constexpr char kPropertyName[] = "deviceUniqueId";
constexpr size_t kPropertyNameLen = sizeof(kPropertyName) - 1;
constexpr const char *kSymbolNeedle = "getPropertyByteArray";

const char *const kCandidateLibraries[] = {
    "/vendor/lib64/mediadrm/libwvdrmengine.so",
    "/vendor/lib64/libwvhidl.so",
    "/vendor/lib64/libwvaidl.so",
    "/system/lib64/mediadrm/libwvdrmengine.so",
    nullptr,
};

std::vector<uint8_t> g_spoof_bytes;
pthread_rwlock_t g_spoof_lock = PTHREAD_RWLOCK_INITIALIZER;

std::vector<uint8_t> hex_to_bytes(const std::string &hex) {
    std::vector<uint8_t> out;
    if (hex.empty() || (hex.size() % 2) != 0) return out;
    out.reserve(hex.size() / 2);
    for (size_t i = 0; i < hex.size(); i += 2) {
        const char buf[3] = {hex[i], hex[i + 1], '\0'};
        char *end = nullptr;
        const unsigned long byte = std::strtoul(buf, &end, 16);
        if (end != buf + 2) {
            out.clear();
            return out;
        }
        out.push_back(static_cast<uint8_t>(byte));
    }
    return out;
}

std::string read_spoof_id_hex() {
    // The persistent path is inside the module directory. The tmpfs fallback is
    // the path prepared in post-fs-data.sh for vendor/HAL namespace access.
    // Keeping both here lets the hook survive partial staging failures while
    // still preferring the canonical module-owned file.
    int fd = open(kSpoofIdPath, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        // Fallback to the tmpfs path written by post-fs-data.sh
        fd = open("/dev/.arirang/widevine_id", O_RDONLY | O_CLOEXEC);
    }
    if (fd < 0) return {};

    char buffer[256];
    std::string result;
    while (true) {
        ssize_t n = read(fd, buffer, sizeof(buffer));
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (n == 0) break;
        result.append(buffer, static_cast<size_t>(n));
        if (result.size() > 1024) {
            result.clear();
            break;
        }
    }
    close(fd);
    while (!result.empty() && (result.back() == '\n' || result.back() == '\r' ||
                                result.back() == ' ' || result.back() == '\t')) {
        result.pop_back();
    }
    return result;
}

void reload_spoof_bytes() {
    const std::string hex = read_spoof_id_hex();
    auto bytes = hex_to_bytes(hex);
    pthread_rwlock_wrlock(&g_spoof_lock);
    g_spoof_bytes = std::move(bytes);
    const size_t size = g_spoof_bytes.size();
    pthread_rwlock_unlock(&g_spoof_lock);
    arirang::log_info(std::string("arirang_drm_hook: spoof bytes loaded len=") +
                      std::to_string(size));
}

bool addr_looks_readable(const void *p) {
    // This is intentionally only a cheap sanity guard. We avoid probing memory
    // with mincore/process_vm_readv inside the HAL process because the hook sits
    // on a DRM hot path and should not introduce extra syscalls for every call.
    return p != nullptr && reinterpret_cast<uintptr_t>(p) > 0x1000ULL;
}

// libc++'s std::string can be laid out a few different ways depending on ABI
// version (Android NDK r25+ vs older, _LIBCPP_ABI_ALTERNATE_STRING_LAYOUT,
// etc.). Rather than commit to one layout, we probe the small number of
// plausible interpretations and report a match if any one yields the exact
// bytes "deviceUniqueId\0".
bool string_equals_devuniqueid(const void *str_ptr) {
    if (!addr_looks_readable(str_ptr)) return false;
    const auto *raw = reinterpret_cast<const uint8_t *>(str_ptr);

    // (a) Short form, data at offset 0, size+long byte at offset 23.
    //     Used by libc++ default layout on Android.
    if (std::memcmp(raw, kPropertyName, kPropertyNameLen) == 0 && raw[kPropertyNameLen] == '\0') {
        return true;
    }

    // (b) Short form, size byte at offset 0, data at offset 1.
    //     Used by ALTERNATE_STRING_LAYOUT.
    if (std::memcmp(raw + 1, kPropertyName, kPropertyNameLen) == 0 && raw[kPropertyNameLen + 1] == '\0') {
        return true;
    }

    // (c) Long form, data pointer at offset 0, size at offset 8.
    const char *ptr = nullptr;
    size_t len = 0;
    std::memcpy(&ptr, raw, sizeof(ptr));
    std::memcpy(&len, raw + sizeof(ptr), sizeof(len));
    if (len == kPropertyNameLen && addr_looks_readable(ptr) &&
        std::memcmp(ptr, kPropertyName, kPropertyNameLen) == 0) {
        return true;
    }

    // (d) Long form, data pointer at offset 16 (mirrored layout).
    std::memcpy(&ptr, raw + 16, sizeof(ptr));
    std::memcpy(&len, raw + 8, sizeof(len));
    if (len == kPropertyNameLen && addr_looks_readable(ptr) &&
        std::memcmp(ptr, kPropertyName, kPropertyNameLen) == 0) {
        return true;
    }

    return false;
}

// libc++ std::vector<uint8_t> layout (24 bytes): [__begin_][__end_][__end_cap_].
// We free the old buffer with delete[] and install a fresh new[]-allocated
// buffer of the spoofed contents. This matches libc++'s default allocator.
void overwrite_vector_bytes(void *vec_ptr, const std::vector<uint8_t> &bytes) {
    if (!addr_looks_readable(vec_ptr)) return;
    auto *raw = reinterpret_cast<uint8_t **>(vec_ptr);
    uint8_t *old_begin = raw[0];
    if (old_begin != nullptr) {
        delete[] old_begin;
    }
    uint8_t *new_begin = nullptr;
    if (!bytes.empty()) {
        new_begin = new uint8_t[bytes.size()];
        std::memcpy(new_begin, bytes.data(), bytes.size());
    }
    raw[0] = new_begin;
    raw[1] = new_begin + bytes.size();
    raw[2] = new_begin + bytes.size();
}

bool symbol_is_hidl_target(const std::string &mangled) {
    // HIDL signature: getPropertyByteArray(..., hidl_string, std::function<... hidl_vec ...>)
    return mangled.find("11hidl_string") != std::string::npos &&
           mangled.find("8function") != std::string::npos &&
           mangled.find("8hidl_vec") != std::string::npos;
}

bool symbol_is_aidl_target(const std::string &mangled) {
    // AIDL signature: getPropertyByteArray(..., std::string, std::vector<uint8_t>*)
    return mangled.find("basic_string") != std::string::npos &&
           mangled.find("vector") != std::string::npos &&
           !symbol_is_hidl_target(mangled);
}

bool install_hook_in_library(const char *library_path);

void *worker(void *) {
    reload_spoof_bytes();

    // The hook library is LD_PRELOAD'd before Widevine necessarily loads its
    // plugin implementation. Poll with RTLD_NOLOAD so we only inspect objects
    // already mapped by the daemon and never force-load DRM plugins ourselves.
    for (int attempt = 0; attempt < 120; ++attempt) {
        for (const char *const *p = kCandidateLibraries; *p != nullptr; ++p) {
            const char *path = *p;
            struct stat st{};
            if (stat(path, &st) != 0) continue;
            void *handle = dlopen(path, RTLD_NOW | RTLD_NOLOAD);
            if (handle == nullptr) continue;
            dlclose(handle);
            if (install_hook_in_library(path)) {
                return nullptr;
            }
        }
        usleep(500 * 1000);
    }
    arirang::log_warn("arirang_drm_hook: Widevine plugin .so never appeared; giving up");
    return nullptr;
}

bool install_hook_in_library(const char *library_path) {
    auto symbols = arirang::resolve_symbols_by_substring(library_path, kSymbolNeedle);
    if (symbols.empty()) {
        arirang::log_warn(std::string("arirang_drm_hook: no ") + kSymbolNeedle +
                          " symbols in " + library_path);
        return false;
    }

    for (auto &sym : symbols) {
        arirang::log_info(std::string("arirang_drm_hook: candidate ") + sym.name);
    }

    // Hook through vtable / method-table slots instead of rewriting function
    // prologues. This is stable across instruction encodings, compilers and
    // ARMv8.3+ PAC because it only touches data tables.
    for (auto &sym : symbols) {
        void *hook = nullptr;
        void **trampoline_slot = nullptr;
        const char *flavor = nullptr;
        if (symbol_is_aidl_target(sym.name)) {
            hook = reinterpret_cast<void *>(&arirang_drm_aidl_entry);
            trampoline_slot = &g_trampoline;
            flavor = "AIDL";
        } else if (symbol_is_hidl_target(sym.name)) {
            hook = reinterpret_cast<void *>(&arirang_drm_hidl_hook);
            trampoline_slot = &g_hidl_trampoline;
            flavor = "HIDL";
        }
        if (hook == nullptr) continue;

        std::vector<arirang::VtablePatch> patches;
        // Pass the exact mangled symbol name as a substring so only this
        // overload is resolved and scanned.
        if (!arirang::vtable_hook_install(library_path, sym.name.c_str(),
                                          hook, &patches)) {
            __android_log_print(ANDROID_LOG_WARN, "ArirangDrmHook",
                                "vtable hook failed for %s %s", flavor, sym.name.c_str());
            arirang::log_warn(std::string("arirang_drm_hook: failed to hook ") + flavor +
                              " " + sym.name);
            continue;
        }

        if (!patches.empty()) {
            // The original implementation is the same in every patched slot;
            // any patch gives us a usable function pointer.
            *trampoline_slot = patches[0].original_function;
        }
        g_hook_method = "vtable";
        __android_log_print(ANDROID_LOG_INFO, "ArirangDrmHook",
                            "vtable hook success for %s %s", flavor, sym.name.c_str());
        arirang::log_info(std::string("arirang_drm_hook: vtable-hooked ") + flavor +
                          " " + sym.name + " in " + library_path);
        return true;
    }

    arirang::log_warn(std::string("arirang_drm_hook: no AIDL/HIDL-flavored symbol matched in ") +
                      library_path);
    return false;
}

} // namespace

HidlReturnVoid arirang_drm_hidl_hook(void* this_ptr, const hidl::string& name, HidlCallback cb) {
    static bool path_logged = false;
    if (!path_logged && g_hook_method != nullptr) {
        arirang::log_info(std::string("arirang_drm_hook: HIDL callback reached via ") +
                          g_hook_method + " hook");
        path_logged = true;
    }

    bool spoof = false;
    // HIDL passes android::hardware::hidl_string, not std::string. The layout
    // wrapper above models only the fields used by this comparison, so do not
    // call string helpers or constructors on it.
    if (name.mBuffer != nullptr && name.mSize == 14 /* "deviceUniqueId" */ &&
        std::memcmp(name.mBuffer, "deviceUniqueId", 14) == 0) {
        spoof = true;
    }

    auto func = reinterpret_cast<HidlGetPropertyByteArrayFunc>(g_hidl_trampoline);
    if (!spoof) {
        // C++17 guaranteed copy elision passes the caller's hidden return pointer
        // (x8) straight through to the original HAL function.
        return func(this_ptr, name, std::move(cb));
    }

    // Capture only a pointer to the original std::function callback instead
    // of moving it into the lambda.  The HIDL callback is invoked synchronously
    // by the original HAL function, so the original callback pointer stays
    // valid for the whole call.  Moving the callback into the wrapper caused a
    // crash while destroying the nested std::function object after the call
    // returned.
    HidlCallback *original_cb = &cb;
    HidlCallback wrapped_cb = [original_cb](hidl::Status status, const hidl::vec<uint8_t>& orig_vec) {
        if (original_cb == nullptr) return;
        if (static_cast<uint32_t>(status) == 0) {
            pthread_rwlock_rdlock(&g_spoof_lock);
            auto bytes = g_spoof_bytes;
            pthread_rwlock_unlock(&g_spoof_lock);

            if (!bytes.empty()) {
                // hidl::vec here borrows bytes.data(); the callback is invoked
                // synchronously before this stack frame exits, so owns=false is
                // required to avoid the HAL trying to free our vector storage.
                hidl::vec<uint8_t> spoofed_vec(bytes.data(), static_cast<uint32_t>(bytes.size()), false);
                (*original_cb)(status, spoofed_vec);
                arirang::log_info("arirang_drm_hook: spoofed deviceUniqueId byte[] (HIDL)");
                return;
            }
        }
        (*original_cb)(status, orig_vec);
    };

    // Forward the Return<void> ABI to the original; the dummy return struct
    // keeps x8 intact across the hook.
    return func(this_ptr, name, std::move(wrapped_cb));
    // `cb` still owns the original std::function here (we did not move it) and
    // is destroyed normally when we return.
}

// Post-call C handler invoked from the entry thunk after the original
// function has already executed. Decides whether to rewrite the returned
// vector based on the property name.
extern "C" void arirang_drm_aidl_post(void * /*this_ptr*/, const void *name_ptr,
                                       void *vec_ptr) {
    if (!string_equals_devuniqueid(name_ptr)) return;

    pthread_rwlock_rdlock(&g_spoof_lock);
    const auto bytes = g_spoof_bytes;
    pthread_rwlock_unlock(&g_spoof_lock);
    if (bytes.empty()) return;

    // AIDL returns the vector by out-parameter. We call the real HAL first so
    // status/error handling remains untouched, then replace only the successful
    // byte vector when the requested key is deviceUniqueId.
    overwrite_vector_bytes(vec_ptr, bytes);
    arirang::log_info("arirang_drm_hook: spoofed deviceUniqueId byte[]");
}

// We define a dummy struct with a non-trivial copy constructor and destructor.
// According to the AArch64 AAPCS, returning a struct with non-trivial lifecycle
// semantics forces the compiler to use the indirect return pointer (x8 register).
// This guarantees that the caller's x8 buffer pointer is seamlessly forwarded
// to the original HAL function without needing fragile inline assembly.
struct AIDL_ScopedAStatus {
    void* ptr;
    AIDL_ScopedAStatus() : ptr(nullptr) {}
    AIDL_ScopedAStatus(const AIDL_ScopedAStatus& other) : ptr(other.ptr) {}
    ~AIDL_ScopedAStatus() {}
};

AIDL_ScopedAStatus arirang_drm_aidl_entry(void* this_ptr, void* name_ptr, void* vec_ptr) {
    static bool path_logged = false;
    if (!path_logged && g_hook_method != nullptr) {
        arirang::log_info(std::string("arirang_drm_hook: AIDL callback reached via ") +
                          g_hook_method + " hook");
        path_logged = true;
    }

    auto func = reinterpret_cast<AIDL_ScopedAStatus (*)(void*, void*, void*)>(g_trampoline);
    // Keep this as a post-call wrapper. Pre-call short-circuiting would require
    // constructing ndk::ScopedAStatus exactly as libbinder_ndk expects; letting
    // the original implementation produce it avoids ABI drift across Android
    // releases.
    AIDL_ScopedAStatus ret = func(this_ptr, name_ptr, vec_ptr);
    arirang_drm_aidl_post(this_ptr, name_ptr, vec_ptr);
    return ret;
}

extern "C" __attribute__((constructor)) void arirang_drm_hook_init() {
    arirang::log_info("arirang_drm_hook: constructor entered");
    // Do all symbol scanning off the loader's constructor path. Running dlopen,
    // dl_iterate_phdr, and vtable patching directly from a constructor risks
    // loader-lock reentrancy while the HAL is still resolving dependencies.
    pthread_t tid;
    if (pthread_create(&tid, nullptr, worker, nullptr) == 0) {
        pthread_detach(tid);
    } else {
        arirang::log_warn("arirang_drm_hook: pthread_create failed");
    }
}
