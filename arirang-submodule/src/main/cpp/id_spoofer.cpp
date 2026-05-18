#include "id_spoofer.hpp"

#include "logging.hpp"

#include <cstdlib>
#include <cstring>
#include <vector>

namespace arirang {
namespace {

using GetPropertyByteArrayNative = jbyteArray (*)(JNIEnv *, jobject, jstring);
using GetPropertyStringNative = jstring (*)(JNIEnv *, jobject, jstring);

GetPropertyByteArrayNative original_get_property_byte_array = nullptr;
GetPropertyStringNative original_get_property_string = nullptr;
const SubmoduleConfig *active_config = nullptr;
bool active_process = false;

// Helper to convert hex string to byte array
std::vector<uint8_t> hex_to_bytes(const std::string& hex) {
    std::vector<uint8_t> bytes;
    for (unsigned int i = 0; i < hex.length(); i += 2) {
        std::string byteString = hex.substr(i, 2);
        uint8_t byte = (uint8_t) strtol(byteString.c_str(), nullptr, 16);
        bytes.push_back(byte);
    }
    return bytes;
}

jbyteArray get_property_byte_array(JNIEnv *env, jobject thiz, jstring jname) {
    if (active_process && active_config != nullptr && active_config->enabled &&
        active_config->unique_identifier_enabled && jname != nullptr) {
        const char *name = env->GetStringUTFChars(jname, nullptr);
        if (name != nullptr) {
            if (std::strcmp(name, "deviceUniqueId") == 0 && !active_config->widevine_id.empty()) {
                env->ReleaseStringUTFChars(jname, name);
                std::vector<uint8_t> bytes = hex_to_bytes(active_config->widevine_id);
                jbyteArray array = env->NewByteArray(bytes.size());
                env->SetByteArrayRegion(array, 0, bytes.size(), reinterpret_cast<const jbyte*>(bytes.data()));
                return array;
            }
            env->ReleaseStringUTFChars(jname, name);
        }
    }

    if (original_get_property_byte_array != nullptr) {
        return original_get_property_byte_array(env, thiz, jname);
    }
    return nullptr;
}

jstring get_property_string(JNIEnv *env, jobject thiz, jstring jname) {
    if (active_process && active_config != nullptr && active_config->enabled &&
        active_config->unique_identifier_enabled && jname != nullptr) {
        const char *name = env->GetStringUTFChars(jname, nullptr);
        if (name != nullptr) {
            // Some plugins use property strings for IDs too
            if (std::strcmp(name, "deviceUniqueId") == 0 && !active_config->widevine_id.empty()) {
                env->ReleaseStringUTFChars(jname, name);
                return env->NewStringUTF(active_config->widevine_id.c_str());
            }
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
    active_config = &config;
    active_process = should_spoof_process;

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
    log_info("installed MediaDrm hooks");
}

} // namespace arirang
