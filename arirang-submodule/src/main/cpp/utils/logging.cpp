#include "logging.hpp"

#include "arirang_build_config.hpp"
#include "io_utils.hpp"

#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

namespace arirang {

namespace {
constexpr const char *kLogTag = "ArirangZygisk";
constexpr off_t kMaxLogFileSize = 1024 * 1024;
constexpr size_t kMaxLogLineSize = 4096;

const std::string &build_log_path() {
    static const std::string path = std::string(kConfigPathDe) + ".log";
    return path;
}

void write_file_log(const char *level, const char *message) {
    const char *path = log_file_path();
    int fd = open_file_no_symlinks(
        path, O_WRONLY | O_CREAT | O_APPEND | O_NONBLOCK, 0600);
    if (fd < 0) return;

    struct stat metadata {};
    if (fstat(fd, &metadata) != 0 || !S_ISREG(metadata.st_mode) ||
        metadata.st_nlink != 1 || (metadata.st_mode & (S_IWGRP | S_IWOTH)) != 0 ||
        metadata.st_size < 0 || metadata.st_size >= kMaxLogFileSize) {
        close(fd);
        return;
    }

    timespec ts{};
    clock_gettime(CLOCK_REALTIME, &ts);
    std::string line = std::to_string(static_cast<long long>(ts.tv_sec));
    line += ".";
    line += std::to_string(ts.tv_nsec / 1000000);
    line += " ";
    line += level;
    line += " ";
    const size_t remaining = line.size() < kMaxLogLineSize
        ? kMaxLogLineSize - line.size() - 1
        : 0;
    line.append(message, std::min(std::strlen(message), remaining));
    line += "\n";
    if (metadata.st_size <= kMaxLogFileSize - static_cast<off_t>(line.size())) {
        write_exact(fd, line.data(), line.size());
    }
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
    return build_log_path().c_str();
}

} // namespace arirang
