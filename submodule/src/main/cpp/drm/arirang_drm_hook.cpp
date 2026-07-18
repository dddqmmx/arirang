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
#include "drm_hook_entry.hpp"
#include "drm_vtable_hook.hpp"
#include "logging.hpp"

#include <android/binder_auto_utils.h>
#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <memory>
#include <pthread.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <vector>

#define ARIRANG_HIDDEN __attribute__((visibility("hidden")))

extern "C" ARIRANG_HIDDEN void *g_trampoline = nullptr;
extern "C" ARIRANG_HIDDEN void *g_hidl_trampoline = nullptr;

ARIRANG_HIDDEN const char *g_hook_method = nullptr;

#include <functional>

namespace hidl {
    struct string {
        const char* mBuffer;
        uint32_t mSize;
        bool mOwnsBuffer;
        uint8_t mPad[3];
    };
    template<typename T>
    struct vec {
        T* mBuffer;
        uint32_t mSize;
        bool mOwnsBuffer;
        uint8_t mPad[3];
        vec(T* buf, uint32_t sz, bool owns)
            : mBuffer(buf), mSize(sz), mOwnsBuffer(owns), mPad{} {}
    };
    enum class Status : uint32_t {
        OK = 0,
        ERROR_DRM_UNKNOWN = 1,
    };
}

using HidlCallback = std::function<void(hidl::Status, const hidl::vec<uint8_t>&)>;

struct HidlReturnVoid {
    alignas(8) unsigned char payload[40];
};

static_assert(sizeof(hidl::string) == 16 && alignof(hidl::string) == 8);
static_assert(offsetof(hidl::string, mBuffer) == 0);
static_assert(offsetof(hidl::string, mSize) == 8);
static_assert(offsetof(hidl::string, mOwnsBuffer) == 12);
static_assert(sizeof(hidl::vec<uint8_t>) == 16 && alignof(hidl::vec<uint8_t>) == 8);
static_assert(sizeof(HidlReturnVoid) == 40 && alignof(HidlReturnVoid) == 8);

using HidlGetPropertyByteArrayFunc = HidlReturnVoid (*)(void* this_ptr,
                                                        const hidl::string& name,
                                                        HidlCallback cb);

HidlReturnVoid arirang_drm_hidl_hook(void* this_ptr, const hidl::string& name, HidlCallback cb);

namespace {

constexpr const char *kSpoofIdPath = "/data/adb/modules/arirang-submodule/runtime/widevine_id";
constexpr const char *kStagedSpoofIdPath = "/dev/.arirang/widevine_id";
constexpr char kPropertyName[] = "deviceUniqueId";
constexpr size_t kPropertyNameLen = sizeof(kPropertyName) - 1;
constexpr size_t kMaxSpoofHexLength = 1024;

std::shared_ptr<const std::vector<uint8_t>> g_spoof_bytes;
std::atomic<bool> g_hidl_path_logged{false};
std::atomic<bool> g_aidl_path_logged{false};
std::atomic<bool> g_hidl_spoof_logged{false};
std::atomic<bool> g_aidl_spoof_logged{false};

int hex_nibble(char value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
}

std::vector<uint8_t> hex_to_bytes(const std::string &hex) {
    std::vector<uint8_t> out;
    if (hex.empty() || (hex.size() % 2) != 0) return out;
    out.reserve(hex.size() / 2);
    for (size_t i = 0; i < hex.size(); i += 2) {
        const int high = hex_nibble(hex[i]);
        const int low = hex_nibble(hex[i + 1]);
        if (high < 0 || low < 0) {
            out.clear();
            return out;
        }
        out.push_back(static_cast<uint8_t>((high << 4) | low));
    }
    return out;
}

bool spoof_file_metadata_is_trusted(const struct stat &st) {
    return S_ISREG(st.st_mode) && st.st_uid == 0 && st.st_nlink == 1 &&
           (st.st_mode & 0022) == 0 && st.st_size >= 0 &&
           static_cast<uint64_t>(st.st_size) <= kMaxSpoofHexLength;
}

int open_trusted_spoof_file(const char *path, struct stat *metadata) {
    const int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0) return -1;

    struct stat st{};
    if (fstat(fd, &st) == 0 && spoof_file_metadata_is_trusted(st)) {
        *metadata = st;
        return fd;
    }

    close(fd);
    return -1;
}

bool same_file_snapshot(const struct stat &before, const struct stat &after) {
    return spoof_file_metadata_is_trusted(after) && before.st_dev == after.st_dev &&
           before.st_ino == after.st_ino && before.st_size == after.st_size &&
           before.st_mtim.tv_sec == after.st_mtim.tv_sec &&
           before.st_mtim.tv_nsec == after.st_mtim.tv_nsec &&
           before.st_ctim.tv_sec == after.st_ctim.tv_sec &&
           before.st_ctim.tv_nsec == after.st_ctim.tv_nsec;
}

std::string read_spoof_id_hex() {
    struct stat before{};
    int fd = open_trusted_spoof_file(kSpoofIdPath, &before);
    if (fd < 0) fd = open_trusted_spoof_file(kStagedSpoofIdPath, &before);
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
        if (result.size() > kMaxSpoofHexLength) {
            result.clear();
            break;
        }
    }
    struct stat after{};
    const bool stable = fstat(fd, &after) == 0 && same_file_snapshot(before, after);
    close(fd);
    if (!stable) return {};
    while (!result.empty() && (result.back() == '\n' || result.back() == '\r' ||
                                result.back() == ' ' || result.back() == '\t')) {
        result.pop_back();
    }
    return result;
}

std::shared_ptr<const std::vector<uint8_t>> spoof_bytes_snapshot() noexcept {
    return std::atomic_load_explicit(&g_spoof_bytes, std::memory_order_acquire);
}

void reload_spoof_bytes() {
    const std::string hex = read_spoof_id_hex();
    const auto decoded = hex_to_bytes(hex);
    std::shared_ptr<const std::vector<uint8_t>> bytes;
    try {
        if (!decoded.empty()) {
            bytes = std::make_shared<const std::vector<uint8_t>>(decoded);
        }
    } catch (...) {
        arirang::log_warn("arirang_drm_hook: cannot allocate spoof byte snapshot");
    }
    const size_t size = bytes == nullptr ? 0 : bytes->size();
    std::atomic_store_explicit(&g_spoof_bytes, std::move(bytes),
                               std::memory_order_release);
    arirang::log_info(std::string("arirang_drm_hook: spoof bytes loaded len=") +
                      std::to_string(size));
}

bool property_name_matches(const std::string &name) {
    return name.size() == kPropertyNameLen &&
           std::memcmp(name.data(), kPropertyName, kPropertyNameLen) == 0;
}

void log_hook_path_once(std::atomic<bool> *logged, const char *abi) {
    bool expected = false;
    if (!logged->compare_exchange_strong(expected, true, std::memory_order_relaxed)) return;

    const char *method = arirang_drm_hook_method();
    if (method == nullptr) return;
    arirang::log_info(std::string("arirang_drm_hook: ") + abi + " callback via " +
                      method + " hook");
}

void log_spoof_once(std::atomic<bool> *logged, const char *abi) {
    bool expected = false;
    if (!logged->compare_exchange_strong(expected, true, std::memory_order_relaxed)) return;

    arirang::log_info(std::string("arirang_drm_hook: spoofed deviceUniqueId byte[] (") +
                      abi + ")");
}

void *worker(void *) {
    reload_spoof_bytes();

    // Phase 1: try vtable-based data-level hooking (primary strategy).
    if (drm_vtable::poll_libraries()) {
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
    log_hook_path_once(&g_hidl_path_logged, "HIDL");

    bool spoof = false;
    if (name.mBuffer != nullptr && name.mSize == 14 &&
        std::memcmp(name.mBuffer, "deviceUniqueId", 14) == 0) {
        spoof = true;
    }

    auto func = reinterpret_cast<HidlGetPropertyByteArrayFunc>(arirang_drm_original(1));
    if (func == nullptr) {
        arirang::log_warn("arirang_drm_hook: HIDL original is unavailable");
        return {};
    }
    if (!spoof) {
        return func(this_ptr, name, std::move(cb));
    }

    HidlCallback wrapped_cb = [original_cb = std::move(cb)](
                                  hidl::Status status,
                                  const hidl::vec<uint8_t>& orig_vec) mutable {
        if (!original_cb) return;
        if (static_cast<uint32_t>(status) == 0) {
            const auto bytes = spoof_bytes_snapshot();

            if (bytes != nullptr && !bytes->empty()) {
                hidl::vec<uint8_t> spoofed_vec(const_cast<uint8_t *>(bytes->data()),
                                               static_cast<uint32_t>(bytes->size()), false);
                original_cb(status, spoofed_vec);
                log_spoof_once(&g_hidl_spoof_logged, "HIDL");
                return;
            }
        }
        original_cb(status, orig_vec);
    };

    return func(this_ptr, name, std::move(wrapped_cb));
}

void replace_aidl_output(const std::string &name, std::vector<uint8_t> *output) {
    if (output == nullptr) return;
    if (!property_name_matches(name)) return;

    const auto bytes = spoof_bytes_snapshot();
    if (bytes == nullptr || bytes->empty()) return;

    try {
        output->assign(bytes->begin(), bytes->end());
    } catch (...) {
        arirang::log_warn("arirang_drm_hook: failed to replace AIDL output vector");
        return;
    }
    log_spoof_once(&g_aidl_spoof_logged, "AIDL");
}

using AidlGetPropertyByteArrayFunc = ndk::ScopedAStatus (*)(
    void *this_ptr,
    const std::string &name,
    std::vector<uint8_t> *output);

ndk::ScopedAStatus arirang_drm_aidl_entry(void *this_ptr,
                                          const std::string &name,
                                          std::vector<uint8_t> *output) {
    log_hook_path_once(&g_aidl_path_logged, "AIDL");

    auto func = reinterpret_cast<AidlGetPropertyByteArrayFunc>(arirang_drm_original(0));
    if (func == nullptr) {
        arirang::log_warn("arirang_drm_hook: AIDL original is unavailable");
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }
    ndk::ScopedAStatus ret = func(this_ptr, name, output);
    if (ret.isOk()) replace_aidl_output(name, output);
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

void arirang_drm_publish_original(int hidl, void *original) {
    void **slot = hidl != 0 ? &g_hidl_trampoline : &g_trampoline;
    __atomic_store_n(slot, original, __ATOMIC_RELEASE);
}

void* arirang_drm_original(int hidl) {
    void **slot = hidl != 0 ? &g_hidl_trampoline : &g_trampoline;
    return __atomic_load_n(slot, __ATOMIC_ACQUIRE);
}

void arirang_drm_publish_hook_method(const char *method) {
    __atomic_store_n(&g_hook_method, method, __ATOMIC_RELEASE);
}

const char* arirang_drm_hook_method() {
    return __atomic_load_n(&g_hook_method, __ATOMIC_ACQUIRE);
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
