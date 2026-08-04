#pragma once

#include "submodule_config.hpp"

#include <jni.h>
#include <string>

namespace arirang {

// Per-app default-time-zone illusion (see submodule/doc/timezone_per_app_research.md).
//
// The default time zone is resolved in-process from the globally-shared
// `persist.sys.timezone` property, so unlike sensors/locale it cannot be served
// from system_server. This helper instead gives a single specializing process a
// private copy-on-write view of the property area by:
//   1. `__system_property_find` to fault in and locate the timezone VMA,
//   2. replacing that VMA with MAP_ANONYMOUS|MAP_PRIVATE|MAP_FIXED,
//   3. restoring the bytes and patching the `prop_info` value + serial,
//   4. `mprotect` back to PROT_READ,
//   5. clearing the Java/ICU default so the next read hits the patched property.
//
// After it returns the only residue is an anonymous private data page --
// indistinguishable from a benign mapping state, with no Arirang code or hook
// left behind. The caller must still `DLCLOSE_MODULE_LIBRARY` out of the app.

// Resolves the effective time zone id for [package_name] from [config].
// Returns an empty string when the package must keep the real zone (feature
// disabled, package exempted, or no override). Empty-string map entries mean
// "exempt from the global"; an absent entry falls back to time_zone_global.
std::string resolve_timezone_for_package(const SubmoduleConfig &config,
                                         const std::string &package_name);

// Applies the per-process timezone illusion so this process's default time zone
// becomes [timezone_id], then clears the Java/ICU default cache. Returns true on
// success. Safe to call at postAppSpecialize for ordinary apps only.
bool install_timezone_illusion(JNIEnv *env, const std::string &timezone_id);

} // namespace arirang