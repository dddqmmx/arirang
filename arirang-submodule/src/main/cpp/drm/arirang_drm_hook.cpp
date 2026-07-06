// libarirang_drm_hook.so
//
// LD_PRELOAD'd into the Widevine DRM HAL daemon (system layer). Spoofs
// the deviceUniqueId byte array returned by getPropertyByteArray.
//
// Architecture: this module is ARM64-only. The vtable scan and inline
// hook trampolines assume AArch64 instruction encodings and pointer sizes.
// See drm_vtable_hook.cpp (primary strategy) and drm_inline_hook.cpp
// (instruction-level fallback) for the two hooking approaches.
//
// Hook strategy:
//   1. PRIMARY: scan .data/.data.rel.ro for vtable/method-table slots
//      pointing to getPropertyByteArray and rewrite the data pointers.
//   2. FALLBACK: if vtable scanning finds no usable slots, attempt
//      instruction-level prologue patching (inline hook). This is less
//      reliable on ARMv8.3+ PAC-enabled devices and is logged as a
//      downgrade warning.
//
// This .so is NEVER loaded into ordinary app processes — only into
// native DRM HAL daemons via init.rc `setenv LD_PRELOAD`.

#include "drm_inline_hook.hpp"
#include "drm_vtable_hook.hpp"
#include "logging.hpp"

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

#define ARIRANG_HIDDEN __attribute__((visibility("hidden")))

extern "C" ARIRANG_HIDDEN void *g_trampoline = nullptr;
extern "C" ARIRANG_HIDDEN void *g_hidl_trampoline = nullptr;

const char *g_hook_method = nullptr;

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
    };
}

using HidlCallback = std::function<void(hidl::Status, const hidl::vec<uint8_t>&)>;

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

bool string_equals_devuniqueid(const void *str_ptr) {
    if (!addr_looks_readable(str_ptr)) return false;
    const auto *raw = reinterpret_cast<const uint8_t *>(str_ptr);

    if (std::memcmp(raw, kPropertyName, kPropertyNameLen) == 0 && raw[kPropertyNameLen] == '\0') {
        return true;
    }
    if (std::memcmp(raw + 1, kPropertyName, kPropertyNameLen) == 0 && raw[kPropertyNameLen + 1] == '\0') {
        return true;
    }
    const char *ptr = nullptr;
    size_t len = 0;
    std::memcpy(&ptr, raw, sizeof(ptr));
    std::memcpy(&len, raw + sizeof(ptr), sizeof(len));
    if (len == kPropertyNameLen && addr_looks_readable(ptr) &&
        std::memcmp(ptr, kPropertyName, kPropertyNameLen) == 0) {
        return true;
    }
    std::memcpy(&ptr, raw + 16, sizeof(ptr));
    std::memcpy(&len, raw + 8, sizeof(len));
    if (len == kPropertyNameLen && addr_looks_readable(ptr) &&
        std::memcmp(ptr, kPropertyName, kPropertyNameLen) == 0) {
        return true;
    }
    return false;
}

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

void *worker(void *) {
    reload_spoof_bytes();

    // Phase 1: try vtable-based data-level hooking (primary strategy).
    if (drm_vtable::poll_libraries() != nullptr) {
        return nullptr;
    }

    // Phase 2: vtable approach failed — attempt instruction-level inline
    // hook as a fallback. This is less reliable and is logged as a
    // downgrade event.
    arirang::log_warn("arirang_drm_hook: vtable strategy exhausted; "
                      "attempting inline hook fallback");

    const char *const kInlineCandidates[] = {
        "/vendor/lib64/mediadrm/libwvdrmengine.so",
        "/vendor/lib64/libwvhidl.so",
        "/vendor/lib64/libwvaidl.so",
        "/system/lib64/mediadrm/libwvdrmengine.so",
        nullptr,
    };

    for (int attempt = 0; attempt < 30; ++attempt) {
        for (const char *const *p = kInlineCandidates; *p != nullptr; ++p) {
            const char *path = *p;
            struct stat st{};
            if (stat(path, &st) != 0) continue;
            void *handle = dlopen(path, RTLD_NOW | RTLD_NOLOAD);
            if (handle == nullptr) continue;
            dlclose(handle);

            if (drm_inline::install_hook_in_library(path)) {
                return nullptr;
            }
        }
        usleep(500 * 1000);
    }
    arirang::log_warn("arirang_drm_hook: all hook strategies failed; giving up");
    return nullptr;
}

} // namespace

HidlReturnVoid arirang_drm_hidl_hook(void* this_ptr, const hidl::string& name, HidlCallback cb) {
    static bool path_logged = false;
    if (!path_logged && g_hook_method != nullptr) {
        arirang::log_info(std::string("arirang_drm_hook: HIDL callback via ") +
                          g_hook_method + " hook");
        path_logged = true;
    }

    bool spoof = false;
    if (name.mBuffer != nullptr && name.mSize == 14 &&
        std::memcmp(name.mBuffer, "deviceUniqueId", 14) == 0) {
        spoof = true;
    }

    auto func = reinterpret_cast<HidlGetPropertyByteArrayFunc>(g_hidl_trampoline);
    if (!spoof) {
        return func(this_ptr, name, std::move(cb));
    }

    HidlCallback *original_cb = &cb;
    HidlCallback wrapped_cb = [original_cb](hidl::Status status, const hidl::vec<uint8_t>& orig_vec) {
        if (original_cb == nullptr) return;
        if (static_cast<uint32_t>(status) == 0) {
            pthread_rwlock_rdlock(&g_spoof_lock);
            auto bytes = g_spoof_bytes;
            pthread_rwlock_unlock(&g_spoof_lock);

            if (!bytes.empty()) {
                hidl::vec<uint8_t> spoofed_vec(bytes.data(), static_cast<uint32_t>(bytes.size()), false);
                (*original_cb)(status, spoofed_vec);
                arirang::log_info("arirang_drm_hook: spoofed deviceUniqueId byte[] (HIDL)");
                return;
            }
        }
        (*original_cb)(status, orig_vec);
    };

    return func(this_ptr, name, std::move(wrapped_cb));
}

extern "C" void arirang_drm_aidl_post(void * /*this_ptr*/, const void *name_ptr,
                                       void *vec_ptr) {
    if (!string_equals_devuniqueid(name_ptr)) return;

    pthread_rwlock_rdlock(&g_spoof_lock);
    const auto bytes = g_spoof_bytes;
    pthread_rwlock_unlock(&g_spoof_lock);
    if (bytes.empty()) return;

    overwrite_vector_bytes(vec_ptr, bytes);
    arirang::log_info("arirang_drm_hook: spoofed deviceUniqueId byte[] (AIDL)");
}

struct AIDL_ScopedAStatus {
    void* ptr;
    AIDL_ScopedAStatus() : ptr(nullptr) {}
    AIDL_ScopedAStatus(const AIDL_ScopedAStatus& other) : ptr(other.ptr) {}
    ~AIDL_ScopedAStatus() {}
};

AIDL_ScopedAStatus arirang_drm_aidl_entry(void* this_ptr, void* name_ptr, void* vec_ptr) {
    static bool path_logged = false;
    if (!path_logged && g_hook_method != nullptr) {
        arirang::log_info(std::string("arirang_drm_hook: AIDL callback via ") +
                          g_hook_method + " hook");
        path_logged = true;
    }

    auto func = reinterpret_cast<AIDL_ScopedAStatus (*)(void*, void*, void*)>(g_trampoline);
    AIDL_ScopedAStatus ret = func(this_ptr, name_ptr, vec_ptr);
    arirang_drm_aidl_post(this_ptr, name_ptr, vec_ptr);
    return ret;
}

// C-linkage accessors: expose hook entry points as void* so that other
// translation units can obtain their addresses without importing HIDL types.
#ifdef __cplusplus
extern "C" {
#endif

void* arirang_drm_aidl_entry_get() {
    return reinterpret_cast<void*>(arirang_drm_aidl_entry);
}

void* arirang_drm_hidl_hook_get() {
    return reinterpret_cast<void*>(reinterpret_cast<void (*)(void*, const hidl::string&, HidlCallback)>(arirang_drm_hidl_hook));
}

#ifdef __cplusplus
}
#endif

extern "C" __attribute__((constructor)) void arirang_drm_hook_init() {
    arirang::log_info("arirang_drm_hook: constructor entered");
    pthread_t tid;
    if (pthread_create(&tid, nullptr, worker, nullptr) == 0) {
        pthread_detach(tid);
    } else {
        arirang::log_warn("arirang_drm_hook: pthread_create failed");
    }
}
