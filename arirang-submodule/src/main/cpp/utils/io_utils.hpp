#pragma once

#include <cstddef>
#include <string>
#include <sys/types.h>

namespace arirang {

bool read_exact(int fd, void *buf, size_t len);
bool write_exact(int fd, const void *buf, size_t len);
int open_file_no_symlinks(const char *path, int flags, mode_t mode = 0);
std::string read_file(const char *path, size_t max_size);

} // namespace arirang
