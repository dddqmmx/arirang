#include "system_property_spoofer.hpp"

#include "logging.hpp"

#include <cstring>

namespace arirang {
namespace {

using NativeGetWithDefault = jstring (*)(JNIEnv *, jclass, jstring, jstring);

NativeGetWithDefault original_native_get_with_default = nullptr;
const SubmoduleConfig *active_config = nullptr;
bool active_process = false;

const std::string *replacement_for_property(const SubmoduleConfig &config, const char *key) {
    if (std::strcmp(key, "gsm.sim.operator.iso-country") == 0) return &config.gsm_sim_operator_iso_country;
    if (std::strcmp(key, "gsm.operator.iso-country") == 0) return &config.gsm_operator_iso_country;
    if (std::strcmp(key, "gsm.sim.operator.numeric") == 0) return &config.gsm_sim_operator_numeric;
    if (std::strcmp(key, "gsm.operator.numeric") == 0) return &config.gsm_operator_numeric;
    if (std::strcmp(key, "gsm.sim.operator.alpha") == 0) return &config.gsm_sim_operator_alpha;
    if (std::strcmp(key, "gsm.operator.alpha") == 0) return &config.gsm_operator_alpha;
    return nullptr;
}

jstring call_original(JNIEnv *env, jclass clazz, jstring key, jstring fallback) {
    return original_native_get_with_default != nullptr
        ? original_native_get_with_default(env, clazz, key, fallback)
        : fallback;
}

jstring native_get_with_default(JNIEnv *env, jclass clazz, jstring key, jstring fallback) {
    if (!active_process || active_config == nullptr || !active_config->enabled || key == nullptr) {
        return call_original(env, clazz, key, fallback);
    }

    const char *chars = env->GetStringUTFChars(key, nullptr);
    if (chars == nullptr) return fallback;
    const std::string *replacement = replacement_for_property(*active_config, chars);
    env->ReleaseStringUTFChars(key, chars);
    if (replacement != nullptr && !replacement->empty()) {
        return env->NewStringUTF(replacement->c_str());
    }

    return call_original(env, clazz, key, fallback);
}

} // namespace

void install_system_property_spoofer(
    zygisk::Api *api,
    JNIEnv *env,
    const SubmoduleConfig &config,
    bool should_spoof_process
) {
    active_config = &config;
    active_process = should_spoof_process;

    JNINativeMethod methods[] = {
        {
            const_cast<char *>("native_get"),
            const_cast<char *>("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
            reinterpret_cast<void *>(native_get_with_default),
        },
    };
    api->hookJniNativeMethods(env, "android/os/SystemProperties", methods, 1);
    original_native_get_with_default = reinterpret_cast<NativeGetWithDefault>(methods[0].fnPtr);
    log_info("installed SystemProperties native_get hook");
}

} // namespace arirang
