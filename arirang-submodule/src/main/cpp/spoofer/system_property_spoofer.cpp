#include "system_property_spoofer.hpp"

#include "logging.hpp"

#include <ctime>
#include <cstring>

namespace arirang {
namespace {

using NativeGetWithDefault = jstring (*)(JNIEnv *, jclass, jstring, jstring);
using NativeGetLong = jlong (*)(JNIEnv *, jclass, jstring, jlong);

NativeGetWithDefault original_native_get_with_default = nullptr;
NativeGetLong original_native_get_long = nullptr;
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
        if (load_config_from_disk(runtime_config)) {
            active_config = &runtime_config;
        }
        last_config_reload_ms = now;
    }
    return active_config;
}

const std::string *replacement_for_property(const SubmoduleConfig &config, const char *key) {
    if (config.device_info_enabled) {
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
    }

    if (std::strcmp(key, "gsm.sim.operator.iso-country") == 0) return &config.gsm_sim_operator_iso_country;
    if (std::strcmp(key, "gsm.operator.iso-country") == 0) return &config.gsm_operator_iso_country;
    if (std::strcmp(key, "gsm.sim.operator.numeric") == 0) return &config.gsm_sim_operator_numeric;
    if (std::strcmp(key, "gsm.operator.numeric") == 0) return &config.gsm_operator_numeric;
    if (std::strcmp(key, "gsm.sim.operator.alpha") == 0) return &config.gsm_sim_operator_alpha;
    if (std::strcmp(key, "gsm.operator.alpha") == 0) return &config.gsm_operator_alpha;
    return nullptr;
}

bool replacement_for_long_property(const SubmoduleConfig &config, const char *key, jlong *value) {
    if (config.device_info_enabled && std::strcmp(key, "ro.build.date.utc") == 0) {
        *value = config.build_time / 1000;
        return true;
    }
    return false;
}

jstring call_original(JNIEnv *env, jclass clazz, jstring key, jstring fallback) {
    return original_native_get_with_default != nullptr
        ? original_native_get_with_default(env, clazz, key, fallback)
        : fallback;
}

jstring native_get_with_default(JNIEnv *env, jclass clazz, jstring key, jstring fallback) {
    const SubmoduleConfig *config = current_config();
    if (!active_process || config == nullptr || !config->enabled || key == nullptr) {
        return call_original(env, clazz, key, fallback);
    }

    const char *chars = env->GetStringUTFChars(key, nullptr);
    if (chars == nullptr) return fallback;
    const std::string *replacement = replacement_for_property(*config, chars);
    env->ReleaseStringUTFChars(key, chars);
    if (replacement != nullptr && !replacement->empty()) {
        return env->NewStringUTF(replacement->c_str());
    }

    return call_original(env, clazz, key, fallback);
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
    log_info("installed SystemProperties native_get/native_get_long hook");
}

} // namespace arirang
