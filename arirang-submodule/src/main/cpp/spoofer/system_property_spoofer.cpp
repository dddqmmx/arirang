#include "system_property_spoofer.hpp"

#include "jni_utils.hpp"
#include "logging.hpp"
#include "runtime_config.hpp"
#include "hook/inline_hook.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <dlfcn.h>
#include <limits.h>
#include <memory>
#include <mutex>
#include <sys/system_properties.h>

namespace arirang {
namespace {

using NativeGetWithDefault = jstring (*)(JNIEnv *, jclass, jstring, jstring);
using NativeGetLong = jlong (*)(JNIEnv *, jclass, jstring, jlong);

std::atomic<NativeGetWithDefault> original_native_get_with_default{nullptr};
std::atomic<NativeGetLong> original_native_get_long{nullptr};

using LibcSystemPropertyGet = int (*)(const char *, char *);
void *original_system_property_get = nullptr;

using LibcSystemPropertyReadCallback = void (*)(const prop_info *, void (*)(void *, const char *, const char *, uint32_t), void *);
void *original_system_property_read_callback = nullptr;

using LibcSystemPropertyRead = int (*)(const prop_info *, char *, char *);
void *original_system_property_read = nullptr;

using LibcExecve = int (*)(const char *, char *const [], char *const []);
void *original_execve = nullptr;

std::atomic<bool> active_process{false};
std::mutex install_mutex;
bool hooks_installed = false;

template <typename Function>
Function load_original(void *const *slot) {
    return reinterpret_cast<Function>(__atomic_load_n(slot, __ATOMIC_ACQUIRE));
}

bool install_libc_hook(void *target, void *handler, void **original, const char *name) {
    if (target == nullptr) return false;
    if (inline_hook_branch(target, handler, original)) return true;
    log_warn(std::string("failed to install atomic libc hook: ") + name);
    return false;
}

size_t copy_property_value(char *destination, const std::string &replacement) {
    if (destination == nullptr) return 0;
    const size_t copy_size = std::min(replacement.size(),
                                      static_cast<size_t>(PROP_VALUE_MAX - 1));
    std::memcpy(destination, replacement.data(), copy_size);
    destination[copy_size] = '\0';
    return copy_size;
}

const std::string *replacement_for_property(const SubmoduleConfig &config, const char *key) {
    if (config.device_info_enabled) {
        // Android exposes build identity through multiple partition-scoped
        // property namespaces. Spoof all known mirrors so apps cannot recover
        // the original identity by reading vendor/system/product variants.
        // Brand
        if (std::strcmp(key, "ro.product.brand") == 0 ||
            std::strcmp(key, "ro.product.vendor.brand") == 0 ||
            std::strcmp(key, "ro.product.system.brand") == 0 ||
            std::strcmp(key, "ro.product.odm.brand") == 0 ||
            std::strcmp(key, "ro.product.product.brand") == 0 ||
            std::strcmp(key, "ro.product.system_ext.brand") == 0) return &config.build_brand;

        // Manufacturer
        if (std::strcmp(key, "ro.product.manufacturer") == 0 ||
            std::strcmp(key, "ro.product.vendor.manufacturer") == 0 ||
            std::strcmp(key, "ro.product.system.manufacturer") == 0 ||
            std::strcmp(key, "ro.product.odm.manufacturer") == 0 ||
            std::strcmp(key, "ro.product.product.manufacturer") == 0 ||
            std::strcmp(key, "ro.product.system_ext.manufacturer") == 0) return &config.build_manufacturer;

        // Model
        if (std::strcmp(key, "ro.product.model") == 0 ||
            std::strcmp(key, "ro.product.vendor.model") == 0 ||
            std::strcmp(key, "ro.product.system.model") == 0 ||
            std::strcmp(key, "ro.product.odm.model") == 0 ||
            std::strcmp(key, "ro.product.product.model") == 0 ||
            std::strcmp(key, "ro.product.system_ext.model") == 0) return &config.build_model;

        // Device
        if (std::strcmp(key, "ro.product.device") == 0 ||
            std::strcmp(key, "ro.product.vendor.device") == 0 ||
            std::strcmp(key, "ro.product.system.device") == 0 ||
            std::strcmp(key, "ro.product.odm.device") == 0 ||
            std::strcmp(key, "ro.product.product.device") == 0 ||
            std::strcmp(key, "ro.product.system_ext.device") == 0) return &config.build_device;

        // Name (Product)
        if (std::strcmp(key, "ro.product.name") == 0 ||
            std::strcmp(key, "ro.product.vendor.name") == 0 ||
            std::strcmp(key, "ro.product.system.name") == 0 ||
            std::strcmp(key, "ro.product.odm.name") == 0 ||
            std::strcmp(key, "ro.product.product.name") == 0 ||
            std::strcmp(key, "ro.product.system_ext.name") == 0) return &config.build_product;

        // Board
        if (std::strcmp(key, "ro.product.board") == 0 ||
            std::strcmp(key, "ro.board.platform") == 0) return &config.build_board;

        // Hardware
        if (std::strcmp(key, "ro.hardware") == 0 ||
            std::strcmp(key, "ro.boot.hardware") == 0) return &config.build_hardware;

        // Fingerprint
        if (std::strcmp(key, "ro.build.fingerprint") == 0 ||
            std::strcmp(key, "ro.vendor.build.fingerprint") == 0 ||
            std::strcmp(key, "ro.system.build.fingerprint") == 0 ||
            std::strcmp(key, "ro.odm.build.fingerprint") == 0 ||
            std::strcmp(key, "ro.product.build.fingerprint") == 0 ||
            std::strcmp(key, "ro.system_ext.build.fingerprint") == 0 ||
            std::strcmp(key, "ro.bootimage.build.fingerprint") == 0) return &config.build_fingerprint;

        // Other Build fields
        if (std::strcmp(key, "ro.build.display.id") == 0) return &config.build_display;
        if (std::strcmp(key, "ro.build.host") == 0) return &config.build_host;
        if (std::strcmp(key, "ro.build.id") == 0) return &config.build_id;
        if (std::strcmp(key, "ro.build.tags") == 0) return &config.build_tags;
        if (std::strcmp(key, "ro.build.type") == 0) return &config.build_type;
        if (std::strcmp(key, "ro.build.user") == 0) return &config.build_user;

        // Serial
        if (config.unique_identifier_enabled && !config.serial.empty()) {
            if (std::strcmp(key, "ro.serialno") == 0 ||
                std::strcmp(key, "ro.boot.serialno") == 0) return &config.serial;
        }
    }

    if (std::strcmp(key, "gsm.sim.operator.iso-country") == 0) return &config.gsm_sim_operator_iso_country;
    if (std::strcmp(key, "gsm.operator.iso-country") == 0) return &config.gsm_operator_iso_country;
    if (std::strcmp(key, "gsm.sim.operator.numeric") == 0) return &config.gsm_sim_operator_numeric;
    if (std::strcmp(key, "gsm.operator.numeric") == 0) return &config.gsm_operator_numeric;
    if (std::strcmp(key, "gsm.sim.operator.alpha") == 0) return &config.gsm_sim_operator_alpha;
    if (std::strcmp(key, "gsm.operator.alpha") == 0) return &config.gsm_operator_alpha;

    if (std::strcmp(key, "gsm.sim.state") == 0) {
        static const std::string loaded = "LOADED,LOADED";
        return &loaded;
    }

    return nullptr;
}

bool replacement_for_long_property(const SubmoduleConfig &config, const char *key, jlong *value) {
    if (config.device_info_enabled && std::strcmp(key, "ro.build.date.utc") == 0) {
        *value = config.build_time / 1000;
        return true;
    }
    return false;
}

jstring call_original_jni(JNIEnv *env, jclass clazz, jstring key, jstring fallback) {
    const auto original = original_native_get_with_default.load(std::memory_order_acquire);
    return original != nullptr
        ? original(env, clazz, key, fallback)
        : fallback;
}

jstring native_get_with_default(JNIEnv *env, jclass clazz, jstring key, jstring fallback) {
    const auto config = current_runtime_config();
    if (!active_process.load(std::memory_order_relaxed) || config == nullptr ||
        !config->enabled || env == nullptr || key == nullptr) {
        return call_original_jni(env, clazz, key, fallback);
    }

    const char *chars = env->GetStringUTFChars(key, nullptr);
    if (chars == nullptr) return nullptr;
    const std::string *replacement = replacement_for_property(*config, chars);
    env->ReleaseStringUTFChars(key, chars);
    if (replacement != nullptr && !replacement->empty()) {
        // Return a fresh Java string; the config storage remains native-owned
        // and may be replaced by a later disk reload.
        jstring result = new_jstring_utf8(env, *replacement);
        if (result == nullptr && env->ExceptionCheck()) return nullptr;
        return result != nullptr ? result : call_original_jni(env, clazz, key, fallback);
    }

    return call_original_jni(env, clazz, key, fallback);
}

jlong native_get_long(JNIEnv *env, jclass clazz, jstring key, jlong fallback) {
    const auto config = current_runtime_config();
    if (!active_process.load(std::memory_order_relaxed) || config == nullptr ||
        !config->enabled || env == nullptr || key == nullptr) {
        const auto original = original_native_get_long.load(std::memory_order_acquire);
        return original != nullptr
            ? original(env, clazz, key, fallback)
            : fallback;
    }

    const char *chars = env->GetStringUTFChars(key, nullptr);
    if (chars == nullptr) return fallback;
    jlong replacement = fallback;
    const bool replaced = replacement_for_long_property(*config, chars, &replacement);
    env->ReleaseStringUTFChars(key, chars);
    if (replaced) return replacement;

    const auto original = original_native_get_long.load(std::memory_order_acquire);
    return original != nullptr
        ? original(env, clazz, key, fallback)
        : fallback;
}

// Libc hooks
int fake_system_property_get(const char *name, char *value) {
    const auto config = current_runtime_config();
    if (active_process.load(std::memory_order_relaxed) && config != nullptr &&
        config->enabled && name != nullptr && value != nullptr) {
        const std::string *replacement = replacement_for_property(*config, name);
        if (replacement != nullptr && !replacement->empty()) {
            // __system_property_get requires a NUL-terminated value no longer
            // than PROP_VALUE_MAX, even when the replacement came from JSON.
            return static_cast<int>(copy_property_value(value, *replacement));
        }
    }
    const auto original = load_original<LibcSystemPropertyGet>(&original_system_property_get);
    return original != nullptr
        ? original(name, value)
        : 0;
}

struct CallbackWrapper {
    void (*callback)(void *, const char *, const char *, uint32_t);
    void *cookie;
};

void fake_read_callback_handler(void *cookie, const char *name, const char *value, uint32_t serial) {
    auto *wrapper = static_cast<CallbackWrapper *>(cookie);
    if (wrapper == nullptr || wrapper->callback == nullptr) return;
    const auto config = current_runtime_config();
    if (active_process.load(std::memory_order_relaxed) && config != nullptr &&
        config->enabled && name != nullptr) {
        const std::string *replacement = replacement_for_property(*config, name);
        if (replacement != nullptr && !replacement->empty()) {
            // Preserve the original property serial. Some callers use it as a
            // change token; changing it here would make the spoof observable.
            std::array<char, PROP_VALUE_MAX> bounded{};
            copy_property_value(bounded.data(), *replacement);
            wrapper->callback(wrapper->cookie, name, bounded.data(), serial);
            return;
        }
    }
    wrapper->callback(wrapper->cookie, name, value, serial);
}

void fake_system_property_read_callback(const prop_info *pi,
                                        void (*callback)(void *, const char *, const char *, uint32_t),
                                        void *cookie) {
    const auto original = load_original<LibcSystemPropertyReadCallback>(
        &original_system_property_read_callback);
    if (original == nullptr || callback == nullptr) return;
    CallbackWrapper wrapper = {callback, cookie};
    // The wrapper lives on this stack because bionic invokes the callback
    // synchronously from __system_property_read_callback.
    original(pi, fake_read_callback_handler, &wrapper);
}

int fake_system_property_read(const prop_info *pi, char *name, char *value) {
    const auto original = load_original<LibcSystemPropertyRead>(&original_system_property_read);
    if (original == nullptr) return 0;
    int res = original(pi, name, value);
    if (res > 0 && active_process.load(std::memory_order_relaxed) &&
        name != nullptr && value != nullptr) {
        const auto config = current_runtime_config();
        if (config != nullptr && config->enabled) {
            const std::string *replacement = replacement_for_property(*config, name);
            if (replacement != nullptr && !replacement->empty()) {
                return static_cast<int>(copy_property_value(value, *replacement));
            }
        }
    }
    return res;
}

int fake_execve(const char *pathname, char *const argv[], char *const envp[]) {
    if (active_process.load(std::memory_order_relaxed) && pathname != nullptr) {
        const size_t length = strnlen(pathname, PATH_MAX + 1U);
        constexpr char kGetProp[] = "getprop";
        if (length <= PATH_MAX &&
            std::search(pathname, pathname + length, std::begin(kGetProp),
                        std::end(kGetProp) - 1) != pathname + length) {
            // Known limitation: this only logs the exec of an external getprop
            // binary rather than spoofing its output. A child process invoking
            // getprop directly bypasses the __system_property_get/JNI hooks
            // above and can observe real property values. Logged at warn level
            // so this gap is visible instead of blending into routine info logs.
            log_warn("execve of external getprop binary observed; its output is not spoofed");
        }
    }
    const auto original = load_original<LibcExecve>(&original_execve);
    if (original != nullptr) return original(pathname, argv, envp);
    errno = ENOSYS;
    return -1;
}

} // namespace

void install_system_property_spoofer(
    zygisk::Api *api,
    JNIEnv *env,
    const SubmoduleConfig &config,
    bool should_spoof_process
) {
    active_process.store(should_spoof_process, std::memory_order_release);
    if (!should_spoof_process || api == nullptr || env == nullptr) return;
    initialize_runtime_config(config);

    std::lock_guard<std::mutex> lock(install_mutex);
    if (hooks_installed) return;

    // Layer 1: framework Java callers normally reach android.os.SystemProperties
    // native_get/native_get_long. Zygisk gives us a stable JNI-native hook point
    // before apps start caching Build.* fields.
    JNINativeMethod methods[] = {
        {
            const_cast<char *>("native_get"),
            const_cast<char *>("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
            reinterpret_cast<void *>(native_get_with_default),
        },
        {
            const_cast<char *>("native_get_long"),
            const_cast<char *>("(Ljava/lang/String;J)J"),
            reinterpret_cast<void *>(native_get_long),
        },
    };
    api->hookJniNativeMethods(env, "android/os/SystemProperties", methods, 2);
    if (methods[0].fnPtr != reinterpret_cast<void *>(native_get_with_default)) {
        original_native_get_with_default.store(
            reinterpret_cast<NativeGetWithDefault>(methods[0].fnPtr),
            std::memory_order_release);
    }
    if (methods[1].fnPtr != reinterpret_cast<void *>(native_get_long)) {
        original_native_get_long.store(
            reinterpret_cast<NativeGetLong>(methods[1].fnPtr),
            std::memory_order_release);
    }

    // Layer 2: native libraries can bypass android.os.SystemProperties and call
    // libc property APIs directly. Inline hooks cover the common bionic entry
    // points used by C/C++ code and command-line helpers inside the process.
    void *libc = dlopen("libc.so", RTLD_NOW);
    if (libc != nullptr) {
        void *get_fn = dlsym(libc, "__system_property_get");
        install_libc_hook(get_fn, reinterpret_cast<void *>(fake_system_property_get),
                          &original_system_property_get, "__system_property_get");
        void *read_fn = dlsym(libc, "__system_property_read_callback");
        install_libc_hook(read_fn, reinterpret_cast<void *>(fake_system_property_read_callback),
                          &original_system_property_read_callback,
                          "__system_property_read_callback");
        void *read_old_fn = dlsym(libc, "__system_property_read");
        install_libc_hook(read_old_fn, reinterpret_cast<void *>(fake_system_property_read),
                          &original_system_property_read, "__system_property_read");
        void *execve_fn = dlsym(libc, "execve");
        install_libc_hook(execve_fn, reinterpret_cast<void *>(fake_execve),
                          &original_execve, "execve");
        dlclose(libc);
    }

    hooks_installed = true;
    log_info("installed SystemProperties JNI and Libc hooks (expanded)");
}

} // namespace arirang
