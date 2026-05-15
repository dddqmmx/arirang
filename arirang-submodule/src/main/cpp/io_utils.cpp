#include "io_utils.hpp"

#include <cerrno>
#include <fcntl.h>
#include <unistd.h>

namespace arirang {

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

std::string read_file(const char *path, size_t max_size) {
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
        if (content.size() > max_size) {
            content.clear();
            break;
        }
    }
    close(fd);
    return content;
}

} // namespace arirang
