#include "zygisk.hpp"

#include <android/log.h>
#include <cstring>

namespace {

constexpr const char *kLogTag = "ArirangZygisk";
constexpr const char *kSelfPackage = "asia.nana7mi.arirang";

using NativeGetWithDefault = jstring (*)(JNIEnv *, jclass, jstring, jstring);

NativeGetWithDefault original_native_get_with_default = nullptr;
bool should_spoof_process = false;

void log_info(const char *message) {
    __android_log_write(ANDROID_LOG_INFO, kLogTag, message);
}

bool jstring_equals(JNIEnv *env, jstring value, const char *expected) {
    if (value == nullptr) return false;
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return false;
    const bool result = std::strcmp(chars, expected) == 0;
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring override_property(JNIEnv *env, jstring key, jstring fallback) {
    if (!should_spoof_process || key == nullptr) {
        return original_native_get_with_default != nullptr
            ? original_native_get_with_default(env, nullptr, key, fallback)
            : fallback;
    }

    const char *chars = env->GetStringUTFChars(key, nullptr);
    if (chars == nullptr) return fallback;

    const char *replacement = nullptr;
    if (std::strcmp(chars, "gsm.sim.operator.iso-country") == 0 ||
        std::strcmp(chars, "gsm.operator.iso-country") == 0) {
        replacement = "kp,";
    } else if (std::strcmp(chars, "gsm.sim.operator.numeric") == 0 ||
               std::strcmp(chars, "gsm.operator.numeric") == 0) {
        replacement = "46705,";
    } else if (std::strcmp(chars, "gsm.sim.operator.alpha") == 0 ||
               std::strcmp(chars, "gsm.operator.alpha") == 0) {
        replacement = "Koryolink,";
    }

    env->ReleaseStringUTFChars(key, chars);
    if (replacement != nullptr) return env->NewStringUTF(replacement);

    return original_native_get_with_default != nullptr
        ? original_native_get_with_default(env, nullptr, key, fallback)
        : fallback;
}

jstring native_get_with_default(JNIEnv *env, jclass clazz, jstring key, jstring fallback) {
    if (!should_spoof_process) {
        return original_native_get_with_default != nullptr
            ? original_native_get_with_default(env, clazz, key, fallback)
            : fallback;
    }
    return override_property(env, key, fallback);
}

void set_static_string_field(JNIEnv *env, jclass clazz, const char *field_name, const char *value) {
    jfieldID field = env->GetStaticFieldID(clazz, field_name, "Ljava/lang/String;");
    if (field == nullptr) {
        env->ExceptionClear();
        return;
    }
    jstring java_value = env->NewStringUTF(value);
    if (java_value == nullptr) return;
    env->SetStaticObjectField(clazz, field, java_value);
    env->DeleteLocalRef(java_value);
}

void set_static_long_field(JNIEnv *env, jclass clazz, const char *field_name, jlong value) {
    jfieldID field = env->GetStaticFieldID(clazz, field_name, "J");
    if (field == nullptr) {
        env->ExceptionClear();
        return;
    }
    env->SetStaticLongField(clazz, field, value);
}

void spoof_build_fields(JNIEnv *env) {
    jclass build = env->FindClass("android/os/Build");
    if (build == nullptr) {
        env->ExceptionClear();
        return;
    }

    set_static_string_field(env, build, "BRAND", "google");
    set_static_string_field(env, build, "MANUFACTURER", "Google");
    set_static_string_field(env, build, "MODEL", "Pixel 9 Pro");
    set_static_string_field(env, build, "DEVICE", "caiman");
    set_static_string_field(env, build, "PRODUCT", "caiman");
    set_static_string_field(env, build, "BOARD", "caiman");
    set_static_string_field(env, build, "HARDWARE", "caiman");
    set_static_string_field(env, build, "DISPLAY", "BP4A.251205.006 release-keys");
    set_static_string_field(env, build, "HOST", "android-build");
    set_static_string_field(env, build, "ID", "BP4A.251205.006");
    set_static_string_field(env, build, "TAGS", "release-keys");
    set_static_string_field(env, build, "TYPE", "user");
    set_static_string_field(env, build, "USER", "android-build");
    set_static_string_field(env, build, "FINGERPRINT", "google/caiman/caiman:15/BP4A.251205.006/1234567:user/release-keys");
    set_static_long_field(env, build, "TIME", 1764892800000L);
    env->DeleteLocalRef(build);
}

} // namespace

class ArirangZygisk final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        should_spoof_process = jstring_equals(env_, args->nice_name, kSelfPackage);
        if (!should_spoof_process) {
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        JNINativeMethod methods[] = {
            {
                const_cast<char *>("native_get"),
                const_cast<char *>("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
                reinterpret_cast<void *>(native_get_with_default),
            },
        };
        api_->hookJniNativeMethods(env_, "android/os/SystemProperties", methods, 1);
        original_native_get_with_default = reinterpret_cast<NativeGetWithDefault>(methods[0].fnPtr);
        log_info("installed SystemProperties native_get hook");
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (!should_spoof_process) return;
        spoof_build_fields(env_);
        log_info("spoofed Build fields");
    }

private:
    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
};

REGISTER_ZYGISK_MODULE(ArirangZygisk)
