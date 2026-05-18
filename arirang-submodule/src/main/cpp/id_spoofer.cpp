#include "id_spoofer.hpp"

#include "logging.hpp"

#include <cstdlib>
#include <cstring>
#include <string>
#include <time.h>
#include <vector>

namespace arirang {
namespace {

using GetPropertyByteArrayNative = jbyteArray (*)(JNIEnv *, jobject, jstring);
using GetPropertyStringNative = jstring (*)(JNIEnv *, jobject, jstring);

GetPropertyByteArrayNative original_get_property_byte_array = nullptr;
GetPropertyStringNative original_get_property_string = nullptr;
const SubmoduleConfig *active_config = nullptr;
SubmoduleConfig runtime_config;
bool active_process = false;
long long last_config_reload_ms = 0;

long long monotonic_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<long long>(ts.tv_sec) * 1000LL + ts.tv_nsec / 1000000LL;
}

// Helper to convert hex string to byte array
std::vector<uint8_t> hex_to_bytes(const std::string& hex) {
    std::vector<uint8_t> bytes;
    if (hex.empty() || hex.size() % 2 != 0) {
        return bytes;
    }
    for (unsigned int i = 0; i < hex.length(); i += 2) {
        std::string byteString = hex.substr(i, 2);
        uint8_t byte = (uint8_t) strtol(byteString.c_str(), nullptr, 16);
        bytes.push_back(byte);
    }
    return bytes;
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

jbyteArray get_property_byte_array(JNIEnv *env, jobject thiz, jstring jname) {
    const SubmoduleConfig *config = current_config();
    if (active_process && config != nullptr && config->enabled &&
        config->unique_identifier_enabled && jname != nullptr) {
        const char *name = env->GetStringUTFChars(jname, nullptr);
        if (name != nullptr) {
            if (std::strcmp(name, "deviceUniqueId") == 0 && !config->widevine_id.empty()) {
                env->ReleaseStringUTFChars(jname, name);
                std::vector<uint8_t> bytes = hex_to_bytes(config->widevine_id);
                if (bytes.empty()) {
                    log_warn(std::string("MediaDrm deviceUniqueId configured but invalid hex len=") +
                             std::to_string(config->widevine_id.size()));
                    if (original_get_property_byte_array != nullptr) {
                        return original_get_property_byte_array(env, thiz, jname);
                    }
                    return nullptr;
                }
                jbyteArray array = env->NewByteArray(bytes.size());
                env->SetByteArrayRegion(array, 0, bytes.size(), reinterpret_cast<const jbyte*>(bytes.data()));
                log_info(std::string("MediaDrm deviceUniqueId byte[] spoofed bytes=") + std::to_string(bytes.size()));
                return array;
            }
            log_info(std::string("MediaDrm getPropertyByteArray passthrough name=") + name);
            env->ReleaseStringUTFChars(jname, name);
        }
    }

    if (original_get_property_byte_array != nullptr) {
        return original_get_property_byte_array(env, thiz, jname);
    }
    return nullptr;
}

jstring get_property_string(JNIEnv *env, jobject thiz, jstring jname) {
    const SubmoduleConfig *config = current_config();
    if (active_process && config != nullptr && config->enabled &&
        config->unique_identifier_enabled && jname != nullptr) {
        const char *name = env->GetStringUTFChars(jname, nullptr);
        if (name != nullptr) {
            // Some plugins use property strings for IDs too
            if (std::strcmp(name, "deviceUniqueId") == 0 && !config->widevine_id.empty()) {
                env->ReleaseStringUTFChars(jname, name);
                log_info(std::string("MediaDrm deviceUniqueId string spoofed len=") +
                         std::to_string(config->widevine_id.size()));
                return env->NewStringUTF(config->widevine_id.c_str());
            }
            log_info(std::string("MediaDrm getPropertyString passthrough name=") + name);
            env->ReleaseStringUTFChars(jname, name);
        }
    }

    if (original_get_property_string != nullptr) {
        return original_get_property_string(env, thiz, jname);
    }
    return nullptr;
}

} // namespace

void install_id_spoofer(
    zygisk::Api *api,
    JNIEnv *env,
    const SubmoduleConfig &config,
    bool should_spoof_process
) {
    runtime_config = config;
    active_config = &runtime_config;
    active_process = should_spoof_process;

    log_info(
        std::string("install_id_spoofer activeProcess=") +
        (active_process ? "true" : "false") +
        " uniqueIdentifierEnabled=" + (runtime_config.unique_identifier_enabled ? "true" : "false") +
        " widevineLen=" + std::to_string(runtime_config.widevine_id.size())
    );

    if (!active_process) return;

    JNINativeMethod methods[] = {
        {
            const_cast<char *>("getPropertyByteArray"),
            const_cast<char *>("(Ljava/lang/String;)[B"),
            reinterpret_cast<void *>(get_property_byte_array),
        },
        {
            const_cast<char *>("getPropertyString"),
            const_cast<char *>("(Ljava/lang/String;)Ljava/lang/String;"),
            reinterpret_cast<void *>(get_property_string),
        },
    };

    api->hookJniNativeMethods(env, "android/media/MediaDrm", methods, 2);
    original_get_property_byte_array = reinterpret_cast<GetPropertyByteArrayNative>(methods[0].fnPtr);
    original_get_property_string = reinterpret_cast<GetPropertyStringNative>(methods[1].fnPtr);
    log_info(std::string("installed MediaDrm hooks logFile=") + log_file_path());
}

} // namespace arirang
