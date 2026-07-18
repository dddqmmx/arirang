#include "io_utils.hpp"

#include <cerrno>
#include <cstdint>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <limits>
#include <new>
#include <string_view>

namespace arirang {

int open_file_no_symlinks(const char *path, int flags, mode_t mode) {
    if (path == nullptr || path[0] != '/') {
        errno = EINVAL;
        return -1;
    }

    int directory_fd = open("/", O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (directory_fd < 0) return -1;

    std::string_view remaining(path + 1);
    while (!remaining.empty()) {
        const size_t slash = remaining.find('/');
        const std::string_view component = remaining.substr(0, slash);
        remaining = slash == std::string_view::npos
            ? std::string_view{}
            : remaining.substr(slash + 1);
        if (component.empty()) continue;
        if (component == "." || component == "..") {
            close(directory_fd);
            errno = EINVAL;
            return -1;
        }

        const std::string name(component);
        if (remaining.empty()) {
            const int result = openat(directory_fd, name.c_str(),
                                      flags | O_NOFOLLOW | O_CLOEXEC, mode);
            close(directory_fd);
            return result;
        }

        const int next_fd = openat(directory_fd, name.c_str(),
                                   O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        close(directory_fd);
        if (next_fd < 0) return -1;
        directory_fd = next_fd;
    }

    close(directory_fd);
    errno = EINVAL;
    return -1;
}

bool read_exact(int fd, void *buf, size_t len) {
    if (fd < 0 || (buf == nullptr && len != 0)) return false;
    auto *ptr = static_cast<char *>(buf);
    while (len > 0) {
        const size_t chunk = std::min(
            len, static_cast<size_t>(std::numeric_limits<ssize_t>::max()));
        ssize_t read_count = read(fd, ptr, chunk);
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
    if (fd < 0 || (buf == nullptr && len != 0)) return false;
    const auto *ptr = static_cast<const char *>(buf);
    while (len > 0) {
        const size_t chunk = std::min(
            len, static_cast<size_t>(std::numeric_limits<ssize_t>::max()));
        ssize_t write_count = send(fd, ptr, chunk, MSG_NOSIGNAL);
        if (write_count < 0 && errno == ENOTSOCK) {
            write_count = write(fd, ptr, chunk);
        }
        if (write_count < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (write_count == 0) return false;
        ptr += write_count;
        len -= static_cast<size_t>(write_count);
    }
    return true;
}

std::string read_file(const char *path, size_t max_size) {
    if (path == nullptr || max_size == 0) return {};

    // The manager app owns this path while Zygisk may read it as root. Never
    // follow an app-controlled symlink or block on a substituted FIFO/device.
    int fd = open_file_no_symlinks(path, O_RDONLY | O_NONBLOCK);
    if (fd < 0) return {};

    struct stat metadata {};
    if (fstat(fd, &metadata) != 0 || !S_ISREG(metadata.st_mode) ||
        metadata.st_nlink != 1 || (metadata.st_mode & (S_IWGRP | S_IWOTH)) != 0 ||
        metadata.st_size < 0 || static_cast<uint64_t>(metadata.st_size) > max_size) {
        close(fd);
        return {};
    }

    try {
        const size_t expected_size = static_cast<size_t>(metadata.st_size);
        std::string content(expected_size, '\0');
        if (expected_size > 0 && !read_exact(fd, content.data(), expected_size)) {
            close(fd);
            return {};
        }

        char extra = 0;
        ssize_t extra_count;
        do {
            extra_count = read(fd, &extra, sizeof(extra));
        } while (extra_count < 0 && errno == EINTR);

        struct stat after_read {};
        const bool stable = extra_count == 0 && fstat(fd, &after_read) == 0 &&
            metadata.st_dev == after_read.st_dev && metadata.st_ino == after_read.st_ino &&
            metadata.st_mode == after_read.st_mode && metadata.st_uid == after_read.st_uid &&
            metadata.st_gid == after_read.st_gid && metadata.st_nlink == after_read.st_nlink &&
            metadata.st_size == after_read.st_size &&
            metadata.st_mtim.tv_sec == after_read.st_mtim.tv_sec &&
            metadata.st_mtim.tv_nsec == after_read.st_mtim.tv_nsec &&
            metadata.st_ctim.tv_sec == after_read.st_ctim.tv_sec &&
            metadata.st_ctim.tv_nsec == after_read.st_ctim.tv_nsec;
        close(fd);
        if (!stable) return {};
        return content;
    } catch (const std::bad_alloc &) {
        close(fd);
        return {};
    }
}

} // namespace arirang
