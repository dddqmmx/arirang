#pragma once

#include <cstddef>
#include <string>

namespace arirang {

bool read_exact(int fd, void *buf, size_t len);
bool write_exact(int fd, const void *buf, size_t len);
std::string read_file(const char *path, size_t max_size);

} // namespace arirang
