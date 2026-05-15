#include "logging.hpp"

#include <android/log.h>

namespace arirang {

namespace {
constexpr const char *kLogTag = "ArirangZygisk";
}

void log_info(const char *message) {
    __android_log_write(ANDROID_LOG_INFO, kLogTag, message);
}

void log_warn(const char *message) {
    __android_log_write(ANDROID_LOG_WARN, kLogTag, message);
}

} // namespace arirang
