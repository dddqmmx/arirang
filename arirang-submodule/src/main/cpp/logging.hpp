#pragma once

#include <string>

namespace arirang {

void log_info(const char *message);
void log_warn(const char *message);
void log_info(const std::string &message);
void log_warn(const std::string &message);
const char *log_file_path();

} // namespace arirang
