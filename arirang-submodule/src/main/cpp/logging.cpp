#include "logging.hpp"

#include "arirang_build_config.hpp"

#include <android/log.h>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

namespace arirang {

namespace {
constexpr const char *kLogTag = "ArirangZygisk";

std::string log_path_storage;

std::string build_log_path() {
    std::string path = kConfigPathDe;
    path += ".log";
    return path;
}

void ensure_parent_dir(const std::string &path) {
    const size_t slash = path.find_last_of('/');
    if (slash == std::string::npos || slash == 0) return;
    const std::string parent = path.substr(0, slash);
    mkdir(parent.c_str(), 0700);
}

void write_file_log(const char *level, const char *message) {
    const char *path = log_file_path();
    ensure_parent_dir(path);

    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (fd < 0) return;

    timespec ts{};
    clock_gettime(CLOCK_REALTIME, &ts);
    std::string line = std::to_string(static_cast<long long>(ts.tv_sec));
    line += ".";
    line += std::to_string(ts.tv_nsec / 1000000);
    line += " ";
    line += level;
    line += " ";
    line += message;
    line += "\n";
    write(fd, line.data(), line.size());
    close(fd);
}
}

void log_info(const char *message) {
    __android_log_write(ANDROID_LOG_INFO, kLogTag, message);
    write_file_log("I", message);
}

void log_warn(const char *message) {
    __android_log_write(ANDROID_LOG_WARN, kLogTag, message);
    write_file_log("W", message);
}

void log_info(const std::string &message) {
    log_info(message.c_str());
}

void log_warn(const std::string &message) {
    log_warn(message.c_str());
}

const char *log_file_path() {
    if (log_path_storage.empty()) {
        log_path_storage = build_log_path();
    }
    return log_path_storage.c_str();
}

} // namespace arirang
