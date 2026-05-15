#include "zygisk.hpp"

#include <android/log.h>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

namespace {

constexpr const char *kLogTag = "ArirangZygisk";
constexpr const char *kSelfPackage = "asia.nana7mi.arirang";
constexpr const char *kConfigPathDe = "/data/user_de/0/asia.nana7mi.arirang/files/arirang-submodule/config.json";
constexpr const char *kConfigPathCe = "/data/user/0/asia.nana7mi.arirang/files/arirang-submodule/config.json";

using NativeGetWithDefault = jstring (*)(JNIEnv *, jclass, jstring, jstring);

NativeGetWithDefault original_native_get_with_default = nullptr;
bool should_spoof_process = false;

struct SubmoduleConfig {
    bool enabled = true;
    bool device_info_enabled = true;
    std::string build_brand = "google";
    std::string build_manufacturer = "Google";
    std::string build_model = "Pixel 9 Pro";
    std::string build_device = "caiman";
    std::string build_product = "caiman";
    std::string build_board = "caiman";
    std::string build_hardware = "caiman";
    std::string build_display = "BP4A.251205.006 release-keys";
    std::string build_host = "android-build";
    std::string build_id = "BP4A.251205.006";
    std::string build_tags = "release-keys";
    std::string build_type = "user";
    std::string build_user = "android-build";
    std::string build_fingerprint = "google/caiman/caiman:15/BP4A.251205.006/1234567:user/release-keys";
    jlong build_time = 1764892800000L;
    std::string gsm_sim_operator_iso_country = "kp,";
    std::string gsm_operator_iso_country = "kp,";
    std::string gsm_sim_operator_numeric = "46705,";
    std::string gsm_operator_numeric = "46705,";
    std::string gsm_sim_operator_alpha = "Koryolink,";
    std::string gsm_operator_alpha = "Koryolink,";
};

SubmoduleConfig config;

void log_info(const char *message) {
    __android_log_write(ANDROID_LOG_INFO, kLogTag, message);
}

void log_warn(const char *message) {
    __android_log_write(ANDROID_LOG_WARN, kLogTag, message);
}

bool read_exact(int fd, void *buf, size_t len) {
    auto *ptr = static_cast<char *>(buf);
    while (len > 0) {
        ssize_t read_count = read(fd, ptr, len);
        if (read_count < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (read_count == 0) return false;
        ptr += read_count;
        len -= static_cast<size_t>(read_count);
    }
    return true;
}

bool write_exact(int fd, const void *buf, size_t len) {
    const auto *ptr = static_cast<const char *>(buf);
    while (len > 0) {
        ssize_t write_count = write(fd, ptr, len);
        if (write_count < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        ptr += write_count;
        len -= static_cast<size_t>(write_count);
    }
    return true;
}

std::string read_file(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return {};

    std::string content;
    char buffer[4096];
    while (true) {
        ssize_t read_count = read(fd, buffer, sizeof(buffer));
        if (read_count < 0) {
            if (errno == EINTR) continue;
            content.clear();
            break;
        }
        if (read_count == 0) break;
        content.append(buffer, static_cast<size_t>(read_count));
        if (content.size() > 65536) {
            content.clear();
            break;
        }
    }
    close(fd);
    return content;
}

void companion_handler(int fd) {
    std::string content = read_file(kConfigPathDe);
    if (content.empty()) {
        content = read_file(kConfigPathCe);
    }
    uint32_t size = static_cast<uint32_t>(content.size());
    write_exact(fd, &size, sizeof(size));
    if (size > 0) {
        write_exact(fd, content.data(), content.size());
    }
}

std::string parse_json_string(const std::string &json, const char *key, const std::string &fallback) {
    const std::string pattern = std::string("\"") + key + "\"";
    size_t pos = json.find(pattern);
    if (pos == std::string::npos) return fallback;
    pos = json.find(':', pos + pattern.size());
    if (pos == std::string::npos) return fallback;
    pos = json.find('"', pos + 1);
    if (pos == std::string::npos) return fallback;

    std::string result;
    bool escaped = false;
    for (size_t i = pos + 1; i < json.size(); ++i) {
        char c = json[i];
        if (escaped) {
            switch (c) {
                case '"':
                case '\\':
                case '/':
                    result.push_back(c);
                    break;
                case 'n':
                    result.push_back('\n');
                    break;
                case 'r':
                    result.push_back('\r');
                    break;
                case 't':
                    result.push_back('\t');
                    break;
                default:
                    result.push_back(c);
                    break;
            }
            escaped = false;
        } else if (c == '\\') {
            escaped = true;
        } else if (c == '"') {
            return result;
        } else {
            result.push_back(c);
        }
    }
    return fallback;
}

bool parse_json_bool(const std::string &json, const char *key, bool fallback) {
    const std::string pattern = std::string("\"") + key + "\"";
    size_t pos = json.find(pattern);
    if (pos == std::string::npos) return fallback;
    pos = json.find(':', pos + pattern.size());
    if (pos == std::string::npos) return fallback;
    ++pos;
    while (pos < json.size() && (json[pos] == ' ' || json[pos] == '\n' || json[pos] == '\r' || json[pos] == '\t')) {
        ++pos;
    }
    if (json.compare(pos, 4, "true") == 0) return true;
    if (json.compare(pos, 5, "false") == 0) return false;
    return fallback;
}

jlong parse_json_long(const std::string &json, const char *key, jlong fallback) {
    const std::string pattern = std::string("\"") + key + "\"";
    size_t pos = json.find(pattern);
    if (pos == std::string::npos) return fallback;
    pos = json.find(':', pos + pattern.size());
    if (pos == std::string::npos) return fallback;
    ++pos;
    while (pos < json.size() && (json[pos] == ' ' || json[pos] == '\n' || json[pos] == '\r' || json[pos] == '\t')) {
        ++pos;
    }
    char *end = nullptr;
    long long value = strtoll(json.c_str() + pos, &end, 10);
    if (end == json.c_str() + pos) return fallback;
    return static_cast<jlong>(value);
}

void apply_json_config(const std::string &json) {
    if (json.empty()) {
        log_warn("submodule config not found; using defaults");
        return;
    }

    config.enabled = parse_json_bool(json, "enabled", config.enabled);
    config.device_info_enabled = parse_json_bool(json, "deviceInfoEnabled", config.device_info_enabled);
    config.build_brand = parse_json_string(json, "buildBrand", config.build_brand);
    config.build_manufacturer = parse_json_string(json, "buildManufacturer", config.build_manufacturer);
    config.build_model = parse_json_string(json, "buildModel", config.build_model);
    config.build_device = parse_json_string(json, "buildDevice", config.build_device);
    config.build_product = parse_json_string(json, "buildProduct", config.build_product);
    config.build_board = parse_json_string(json, "buildBoard", config.build_board);
    config.build_hardware = parse_json_string(json, "buildHardware", config.build_hardware);
    config.build_display = parse_json_string(json, "buildDisplay", config.build_display);
    config.build_host = parse_json_string(json, "buildHost", config.build_host);
    config.build_id = parse_json_string(json, "buildId", config.build_id);
    config.build_tags = parse_json_string(json, "buildTags", config.build_tags);
    config.build_type = parse_json_string(json, "buildType", config.build_type);
    config.build_user = parse_json_string(json, "buildUser", config.build_user);
    config.build_fingerprint = parse_json_string(json, "buildFingerprint", config.build_fingerprint);
    config.build_time = parse_json_long(json, "buildTime", config.build_time);
    config.gsm_sim_operator_iso_country = parse_json_string(json, "gsmSimOperatorIsoCountry", config.gsm_sim_operator_iso_country);
    config.gsm_operator_iso_country = parse_json_string(json, "gsmOperatorIsoCountry", config.gsm_operator_iso_country);
    config.gsm_sim_operator_numeric = parse_json_string(json, "gsmSimOperatorNumeric", config.gsm_sim_operator_numeric);
    config.gsm_operator_numeric = parse_json_string(json, "gsmOperatorNumeric", config.gsm_operator_numeric);
    config.gsm_sim_operator_alpha = parse_json_string(json, "gsmSimOperatorAlpha", config.gsm_sim_operator_alpha);
    config.gsm_operator_alpha = parse_json_string(json, "gsmOperatorAlpha", config.gsm_operator_alpha);
    log_info("loaded submodule config");
}

void load_config_from_companion(zygisk::Api *api) {
    int fd = api->connectCompanion();
    if (fd < 0) {
        log_warn("connectCompanion failed; using defaults");
        return;
    }

    uint32_t size = 0;
    if (!read_exact(fd, &size, sizeof(size)) || size > 65536) {
        close(fd);
        log_warn("failed to read submodule config size; using defaults");
        return;
    }

    std::string json(size, '\0');
    if (size > 0 && !read_exact(fd, json.data(), size)) {
        close(fd);
        log_warn("failed to read submodule config; using defaults");
        return;
    }
    close(fd);
    apply_json_config(json);
}

bool jstring_equals(JNIEnv *env, jstring value, const char *expected) {
    if (value == nullptr) return false;
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return false;
    const bool result = std::strcmp(chars, expected) == 0;
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

const std::string *replacement_for_property(const char *key) {
    if (std::strcmp(key, "gsm.sim.operator.iso-country") == 0) return &config.gsm_sim_operator_iso_country;
    if (std::strcmp(key, "gsm.operator.iso-country") == 0) return &config.gsm_operator_iso_country;
    if (std::strcmp(key, "gsm.sim.operator.numeric") == 0) return &config.gsm_sim_operator_numeric;
    if (std::strcmp(key, "gsm.operator.numeric") == 0) return &config.gsm_operator_numeric;
    if (std::strcmp(key, "gsm.sim.operator.alpha") == 0) return &config.gsm_sim_operator_alpha;
    if (std::strcmp(key, "gsm.operator.alpha") == 0) return &config.gsm_operator_alpha;
    return nullptr;
}

jstring override_property(JNIEnv *env, jstring key, jstring fallback) {
    if (!should_spoof_process || !config.enabled || key == nullptr) {
        return original_native_get_with_default != nullptr
            ? original_native_get_with_default(env, nullptr, key, fallback)
            : fallback;
    }

    const char *chars = env->GetStringUTFChars(key, nullptr);
    if (chars == nullptr) return fallback;
    const std::string *replacement = replacement_for_property(chars);
    env->ReleaseStringUTFChars(key, chars);
    if (replacement != nullptr && !replacement->empty()) return env->NewStringUTF(replacement->c_str());

    return original_native_get_with_default != nullptr
        ? original_native_get_with_default(env, nullptr, key, fallback)
        : fallback;
}

jstring native_get_with_default(JNIEnv *env, jclass clazz, jstring key, jstring fallback) {
    if (!should_spoof_process || !config.enabled) {
        return original_native_get_with_default != nullptr
            ? original_native_get_with_default(env, clazz, key, fallback)
            : fallback;
    }
    return override_property(env, key, fallback);
}

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

void spoof_build_fields(JNIEnv *env) {
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
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY)F;
            return;
        }

        load_config_from_companion(api_);

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
REGISTER_ZYGISK_COMPANION(companion_handler)
