#include "build_spoofer.hpp"

namespace arirang {
namespace {

void set_static_string_field(JNIEnv *env, jclass clazz, const char *field_name, const std::string &value) {
    jfieldID field = env->GetStaticFieldID(clazz, field_name, "Ljava/lang/String;");
    if (field == nullptr) {
        env->ExceptionClear();
        return;
    }
    jstring java_value = env->NewStringUTF(value.c_str());
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

} // namespace

void spoof_build_fields(JNIEnv *env, const SubmoduleConfig &config) {
    if (!config.enabled || !config.device_info_enabled) return;

    jclass build = env->FindClass("android/os/Build");
    if (build == nullptr) {
        env->ExceptionClear();
        return;
    }

    set_static_string_field(env, build, "BRAND", config.build_brand);
    set_static_string_field(env, build, "MANUFACTURER", config.build_manufacturer);
    set_static_string_field(env, build, "MODEL", config.build_model);
    set_static_string_field(env, build, "DEVICE", config.build_device);
    set_static_string_field(env, build, "PRODUCT", config.build_product);
    set_static_string_field(env, build, "BOARD", config.build_board);
    set_static_string_field(env, build, "HARDWARE", config.build_hardware);
    set_static_string_field(env, build, "DISPLAY", config.build_display);
    set_static_string_field(env, build, "HOST", config.build_host);
    set_static_string_field(env, build, "ID", config.build_id);
    set_static_string_field(env, build, "TAGS", config.build_tags);
    set_static_string_field(env, build, "TYPE", config.build_type);
    set_static_string_field(env, build, "USER", config.build_user);
    set_static_string_field(env, build, "FINGERPRINT", config.build_fingerprint);
    set_static_long_field(env, build, "TIME", config.build_time);
    env->DeleteLocalRef(build);
}

} // namespace arirang
