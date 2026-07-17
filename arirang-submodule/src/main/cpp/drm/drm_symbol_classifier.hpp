#pragma once

#include <cstring>
#include <string>

namespace drm {

enum class HookAbi {
    kUnsupported,
    kAidl,
    kHidl,
};

inline const char *path_basename(const char *path) {
    if (path == nullptr) return "";
    const char *slash = std::strrchr(path, '/');
    return slash == nullptr ? path : slash + 1;
}

inline HookAbi classify_get_property_byte_array(const char *library_path,
                                                const std::string &mangled) {
    const char *library = path_basename(library_path);
    const bool has_target_method = mangled.find("getPropertyByteArray") != std::string::npos;

    const bool hidl = has_target_method &&
                      mangled.find("11hidl_string") != std::string::npos &&
                      mangled.find("8function") != std::string::npos &&
                      mangled.find("8hidl_vec") != std::string::npos;

    const bool aidl = has_target_method &&
                      mangled.find("basic_string") != std::string::npos &&
                      mangled.find("vector") != std::string::npos;

    // A mangled name that matches both the HIDL and AIDL marker sets is not a
    // valid symbol from either ABI; classifying it would risk installing the
    // wrong hook shape on whichever branch happened to run first.
    if (hidl && aidl) return HookAbi::kUnsupported;

    if (hidl && std::strcmp(library, "libwvhidl.so") == 0) return HookAbi::kHidl;
    if (aidl && std::strcmp(library, "libwvaidl.so") == 0) return HookAbi::kAidl;

    return HookAbi::kUnsupported;
}

} // namespace drm
