#include "system_property_spoofer.hpp"

#include "logging.hpp"
#include "hook/inline_hook.hpp"

#include <ctime>
#include <cstring>
#include <dlfcn.h>
#include <sys/system_properties.h>

namespace arirang {
namespace {

using NativeGetWithDefault = jstring (*)(JNIEnv *, jclass, jstring, jstring);
using NativeGetLong = jlong (*)(JNIEnv *, jclass, jstring, jlong);

NativeGetWithDefault original_native_get_with_default = nullptr;
NativeGetLong original_native_get_long = nullptr;

using LibcSystemPropertyGet = int (*)(const char *, char *);
LibcSystemPropertyGet original_system_property_get = nullptr;

using LibcSystemPropertyReadCallback = void (*)(const prop_info *, void (*)(void *, const char *, const char *, uint32_t), void *);
LibcSystemPropertyReadCallback original_system_property_read_callback = nullptr;

using LibcSystemPropertyRead = int (*)(const prop_info *, char *, char *);
LibcSystemPropertyRead original_system_property_read = nullptr;

using LibcExecve = int (*)(const char *, char *const [], char *const []);
LibcExecve original_execve = nullptr;

const SubmoduleConfig *active_config = nullptr;
SubmoduleConfig runtime_config;
bool active_process = false;
long long last_config_reload_ms = 0;

long long monotonic_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<long long>(ts.tv_sec) * 1000LL + ts.tv_nsec / 1000000LL;
}

const SubmoduleConfig *current_config() {
    if (active_config == nullptr) return nullptr;
    const long long now = monotonic_ms();
    if (now - last_config_reload_ms > 1000) {
        // Native property reads are common, so do not hit disk on every call.
        // A one-second refresh window is enough for UI-driven config changes
        // while keeping libc/JNI property hooks cheap.
        if (load_config_from_disk(runtime_config)) {
            active_config = &runtime_config;
        }
        last_config_reload_ms = now;
    }
    return active_config;
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
    return original_native_get_with_default != nullptr
        ? original_native_get_with_default(env, clazz, key, fallback)
        : fallback;
}

jstring native_get_with_default(JNIEnv *env, jclass clazz, jstring key, jstring fallback) {
    const SubmoduleConfig *config = current_config();
    if (!active_process || config == nullptr || !config->enabled || key == nullptr) {
        return call_original_jni(env, clazz, key, fallback);
    }

    const char *chars = env->GetStringUTFChars(key, nullptr);
    if (chars == nullptr) return fallback;
    const std::string *replacement = replacement_for_property(*config, chars);
    env->ReleaseStringUTFChars(key, chars);
    if (replacement != nullptr && !replacement->empty()) {
        // Return a fresh Java string; the config storage remains native-owned
        // and may be replaced by a later disk reload.
        return env->NewStringUTF(replacement->c_str());
    }

    return call_original_jni(env, clazz, key, fallback);
}

jlong native_get_long(JNIEnv *env, jclass clazz, jstring key, jlong fallback) {
    const SubmoduleConfig *config = current_config();
    if (!active_process || config == nullptr || !config->enabled || key == nullptr) {
        return original_native_get_long != nullptr
            ? original_native_get_long(env, clazz, key, fallback)
            : fallback;
    }

    const char *chars = env->GetStringUTFChars(key, nullptr);
    if (chars == nullptr) return fallback;
    jlong replacement = fallback;
    const bool replaced = replacement_for_long_property(*config, chars, &replacement);
    env->ReleaseStringUTFChars(key, chars);
    if (replaced) return replacement;

    return original_native_get_long != nullptr
        ? original_native_get_long(env, clazz, key, fallback)
        : fallback;
}

// Libc hooks
int fake_system_property_get(const char *name, char *value) {
    const SubmoduleConfig *config = current_config();
    if (active_process && config != nullptr && config->enabled && name != nullptr) {
        const std::string *replacement = replacement_for_property(*config, name);
        if (replacement != nullptr && !replacement->empty()) {
            // __system_property_get requires a NUL-terminated value no longer
            // than PROP_VALUE_MAX, even when the replacement came from JSON.
            std::strncpy(value, replacement->c_str(), PROP_VALUE_MAX);
            value[PROP_VALUE_MAX - 1] = '\0';
            return static_cast<int>(std::strlen(value));
        }
    }
    return original_system_property_get(name, value);
}

struct CallbackWrapper {
    void (*callback)(void *, const char *, const char *, uint32_t);
    void *cookie;
};

void fake_read_callback_handler(void *cookie, const char *name, const char *value, uint32_t serial) {
    auto *wrapper = static_cast<CallbackWrapper *>(cookie);
    const SubmoduleConfig *config = current_config();
    if (active_process && config != nullptr && config->enabled && name != nullptr) {
        const std::string *replacement = replacement_for_property(*config, name);
        if (replacement != nullptr && !replacement->empty()) {
            // Preserve the original property serial. Some callers use it as a
            // change token; changing it here would make the spoof observable.
            wrapper->callback(wrapper->cookie, name, replacement->c_str(), serial);
            return;
        }
    }
    wrapper->callback(wrapper->cookie, name, value, serial);
}

void fake_system_property_read_callback(const prop_info *pi,
                                        void (*callback)(void *, const char *, const char *, uint32_t),
                                        void *cookie) {
    CallbackWrapper wrapper = {callback, cookie};
    // The wrapper lives on this stack because bionic invokes the callback
    // synchronously from __system_property_read_callback.
    original_system_property_read_callback(pi, fake_read_callback_handler, &wrapper);
}

int fake_system_property_read(const prop_info *pi, char *name, char *value) {
    int res = original_system_property_read(pi, name, value);
    if (res > 0 && active_process) {
        const SubmoduleConfig *config = current_config();
        if (config != nullptr && config->enabled) {
            const std::string *replacement = replacement_for_property(*config, name);
            if (replacement != nullptr && !replacement->empty()) {
                std::strncpy(value, replacement->c_str(), PROP_VALUE_MAX);
                value[PROP_VALUE_MAX - 1] = '\0';
                return static_cast<int>(std::strlen(value));
            }
        }
    }
    return res;
}

int fake_execve(const char *pathname, char *const argv[], char *const envp[]) {
    if (active_process && pathname != nullptr) {
        std::string path(pathname);
        if (path.find("getprop") != std::string::npos) {
            log_info(std::string("intercepted execve: ") + pathname);
        }
    }
    return original_execve(pathname, argv, envp);
}

} // namespace

void install_system_property_spoofer(
    zygisk::Api *api,
    JNIEnv *env,
    const SubmoduleConfig &config,
    bool should_spoof_process
) {
    runtime_config = config;
    active_config = &runtime_config;
    active_process = should_spoof_process;

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
    original_native_get_with_default = reinterpret_cast<NativeGetWithDefault>(methods[0].fnPtr);
    original_native_get_long = reinterpret_cast<NativeGetLong>(methods[1].fnPtr);

    // Layer 2: native libraries can bypass android.os.SystemProperties and call
    // libc property APIs directly. Inline hooks cover the common bionic entry
    // points used by C/C++ code and command-line helpers inside the process.
    void *libc = dlopen("libc.so", RTLD_NOW);
    if (libc != nullptr) {
        void *get_fn = dlsym(libc, "__system_property_get");
        if (get_fn != nullptr) {
            inline_hook_install(get_fn, reinterpret_cast<void *>(fake_system_property_get),
                                reinterpret_cast<void **>(&original_system_property_get));
        }
        void *read_fn = dlsym(libc, "__system_property_read_callback");
        if (read_fn != nullptr) {
            inline_hook_install(read_fn, reinterpret_cast<void *>(fake_system_property_read_callback),
                                reinterpret_cast<void **>(&original_system_property_read_callback));
        }
        void *read_old_fn = dlsym(libc, "__system_property_read");
        if (read_old_fn != nullptr) {
            inline_hook_install(read_old_fn, reinterpret_cast<void *>(fake_system_property_read),
                                reinterpret_cast<void **>(&original_system_property_read));
        }
        void *execve_fn = dlsym(libc, "execve");
        if (execve_fn != nullptr) {
            inline_hook_install(execve_fn, reinterpret_cast<void *>(fake_execve),
                                reinterpret_cast<void **>(&original_execve));
        }
        dlclose(libc);
    }

    log_info("installed SystemProperties JNI and Libc hooks (expanded)");
}

} // namespace arirang
