// libarirang_drm_hook.so
//
// LD_PRELOAD'd into the Widevine DRM HAL daemon (system layer). Reads a
// spoofed device unique id from a HAL-readable config file (written by the
// Magisk module's post-fs-data.sh on each boot, sourced from the Arirang
// app's own config). Locates libwvdrmengine.so (the Widevine vendor plugin),
// finds an AIDL-style getPropertyByteArray symbol via the dynamic symbol
// table, installs an ARM64 inline hook, and rewrites the returned byte[]
// when the requested property name is "deviceUniqueId".
//
// This .so is NEVER loaded into ordinary app processes - only into native
// DRM HAL daemons via init.rc `setenv LD_PRELOAD`.

#include "inline_hook.hpp"
#include "logging.hpp"
#include "symbol_resolver.hpp"

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

// C-linkage globals used by the inline-asm thunk. Must NOT live in an anon
// namespace because the inline asm references them by bare symbol name. They
// are hidden so the asm's `adrp + add :lo12:` (PC-relative within this .so)
// addressing mode is safe under -fPIC; otherwise the linker rejects the
// relocation against a default-visibility (potentially preempted) symbol.
#define ARIRANG_HIDDEN __attribute__((visibility("hidden")))
extern "C" ARIRANG_HIDDEN void *g_trampoline = nullptr;
extern "C" ARIRANG_HIDDEN void arirang_drm_aidl_post(void *this_ptr,
                                                     const void *name_ptr,
                                                     void *vec_ptr);
ARIRANG_HIDDEN void arirang_drm_hidl_entry(void *, const void *, void *);
struct AIDL_ScopedAStatus;
AIDL_ScopedAStatus arirang_drm_aidl_entry(void* this_ptr, void* name_ptr, void* vec_ptr);

extern "C" ARIRANG_HIDDEN void *g_hidl_trampoline = nullptr;

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
using HidlGetPropertyByteArrayFunc = void (*)(void* this_ptr, const hidl::string& name, HidlCallback cb);

void arirang_drm_hidl_hook(void* this_ptr, const hidl::string& name, HidlCallback cb);

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

    for (auto &sym : symbols) {
        if (symbol_is_aidl_target(sym.name)) {
            void *trampoline = nullptr;
            if (!arirang::inline_hook_install(sym.address,
                                              reinterpret_cast<void *>(&arirang_drm_aidl_entry),
                                              &trampoline)) {
                arirang::log_warn(std::string("arirang_drm_hook: failed to hook AIDL ") + sym.name);
                continue;
            }
            g_trampoline = trampoline;
            arirang::log_info(std::string("arirang_drm_hook: hooked AIDL ") + sym.name +
                              " in " + library_path);
            return true;
        } else if (symbol_is_hidl_target(sym.name)) {
            void *trampoline = nullptr;
            if (!arirang::inline_hook_install(sym.address,
                                              reinterpret_cast<void *>(&arirang_drm_hidl_hook),
                                              &trampoline)) {
                arirang::log_warn(std::string("arirang_drm_hook: failed to hook HIDL ") + sym.name);
                continue;
            }
            g_hidl_trampoline = trampoline;
            arirang::log_info(std::string("arirang_drm_hook: hooked HIDL ") + sym.name +
                              " in " + library_path);
            return true;
        }
    }

    arirang::log_warn(std::string("arirang_drm_hook: no AIDL/HIDL-flavored symbol matched in ") +
                      library_path);
    return false;
}

} // namespace

void arirang_drm_hidl_hook(void* this_ptr, const hidl::string& name, HidlCallback cb) {
    bool spoof = false;
    if (name.mBuffer != nullptr && name.mSize == 14 /* "deviceUniqueId" */ &&
        std::memcmp(name.mBuffer, "deviceUniqueId", 14) == 0) {
        spoof = true;
    }

    auto func = reinterpret_cast<HidlGetPropertyByteArrayFunc>(g_hidl_trampoline);
    if (!spoof) {
        func(this_ptr, name, std::move(cb));
        return;
    }

    HidlCallback wrapped_cb = [cb = std::move(cb)](hidl::Status status, const hidl::vec<uint8_t>& orig_vec) {
        if (static_cast<uint32_t>(status) == 0) {
            pthread_rwlock_rdlock(&g_spoof_lock);
            auto bytes = g_spoof_bytes;
            pthread_rwlock_unlock(&g_spoof_lock);

            if (!bytes.empty()) {
                hidl::vec<uint8_t> spoofed_vec(bytes.data(), static_cast<uint32_t>(bytes.size()), false);
                cb(status, spoofed_vec);
                arirang::log_info("arirang_drm_hook: spoofed deviceUniqueId byte[] (HIDL)");
                return;
            }
        }
        cb(status, orig_vec);
    };

    func(this_ptr, name, std::move(wrapped_cb));
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
    auto func = reinterpret_cast<AIDL_ScopedAStatus (*)(void*, void*, void*)>(g_trampoline);
    AIDL_ScopedAStatus ret = func(this_ptr, name_ptr, vec_ptr);
    arirang_drm_aidl_post(this_ptr, name_ptr, vec_ptr);
    return ret;
}

extern "C" __attribute__((constructor)) void arirang_drm_hook_init() {
    arirang::log_info("arirang_drm_hook: constructor entered");
    pthread_t tid;
    if (pthread_create(&tid, nullptr, worker, nullptr) == 0) {
        pthread_detach(tid);
    } else {
        arirang::log_warn("arirang_drm_hook: pthread_create failed");
    }
}
