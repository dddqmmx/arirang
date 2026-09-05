#include "timezone_prop_cow.hpp"

#include "jni_utils.hpp"
#include "logging.hpp"

#include <cerrno>
#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <new>
#include <sys/mman.h>
#include <sys/system_properties.h>
#include <unistd.h>

namespace arirang {
namespace {

constexpr const char *kTimezoneKey = "persist.sys.timezone";

// prop_info layout (bionic, sizeof == 96): serial@0 (u32), value[92]@4, name@96.
constexpr size_t kPropValueMax = PROP_VALUE_MAX; // 92

// Validates that [id] contains only standard Olson identifier characters
// ([A-Za-z0-9_/+-]) and fits within PROP_VALUE_MAX.
bool is_valid_timezone_id(const std::string &id) {
    if (id.empty() || id.size() > kPropValueMax) return false;
    for (char c : id) {
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
              (c >= '0' && c <= '9') || c == '/' || c == '_' || c == '-' || c == '+')) {
            return false;
        }
    }
    return true;
}

// Locates the /proc/self/maps range [start, end) containing the given pointer.
// Used to find the shared prop-area VMA that holds the timezone prop_info.
bool find_containing_mapping(const void *p, uintptr_t &start, uintptr_t &end) {
    FILE *f = fopen("/proc/self/maps", "re");
    if (f == nullptr) return false;
    const uintptr_t addr = reinterpret_cast<uintptr_t>(p);
    bool found = false;
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        uintptr_t s = 0;
        uintptr_t e = 0;
        char perms[8] = {0};
        // Only the address range matters; the trailing path/inode are ignored.
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR " %7s", &s, &e, perms) >= 3) {
            if (addr >= s && addr < e) {
                start = s;
                end = e;
                found = true;
                break;
            }
        }
    }
    fclose(f);
    return found;
}

// Patches the prop_info at [pi] to carry [new_value] following bionic's own
// writer recipe (§13.3): value[] NUL-terminated + serial top byte = length,
// dirty bit (bit 0) clear, low 24 bits bumped by 2 to keep parity.
//
// Performs structural self-validation to confirm [pi] conforms to bionic's layout
// (name starting at offset 96 equals "persist.sys.timezone") before modifying bytes.
bool patch_prop_info(prop_info *pi, const char *new_value) {
    if (pi == nullptr || new_value == nullptr) return false;
    const size_t len = strlen(new_value);
    if (len == 0 || len > kPropValueMax) return false;

    auto *raw = reinterpret_cast<uint8_t *>(const_cast<prop_info *>(pi));

    // Structural self-check: verify prop_info::name offset matches kTimezoneKey.
    const char *actual_name = reinterpret_cast<const char *>(raw + 96);
    if (strncmp(actual_name, kTimezoneKey, strlen(kTimezoneKey)) != 0) {
        log_warn("patch_prop_info: bionic layout mismatch (name at offset 96 != persist.sys.timezone)");
        return false;
    }

    uint32_t old_serial = 0;
    memcpy(&old_serial, raw, sizeof(old_serial));

    char *value_field = reinterpret_cast<char *>(raw + 4);
    memset(value_field, 0, kPropValueMax);
    memcpy(value_field, new_value, len);

    // value length in the top byte; bit 0 (dirty) clear; keep low-24 parity.
    const uint32_t new_serial =
        (static_cast<uint32_t>(len) << 24) | (((old_serial & 0x00ffffffU) + 2U) & 0x00fffffeU);
    memcpy(raw, &new_serial, sizeof(new_serial));
    return true;
}

// Tier 2 Fallback: if native anonymous CoW cannot be performed, apply the timezone
// illusion directly via Java/ICU TimeZone.setDefault(...) APIs so standard date/time
// APIs still observe the spoofed timezone rather than leaking the real timezone.
bool fallback_java_set_default_timezone(JNIEnv *env, const std::string &timezone_id) {
    if (env == nullptr || timezone_id.empty()) return false;

    jstring jtz_id = env->NewStringUTF(timezone_id.c_str());
    if (jtz_id == nullptr) {
        env->ExceptionClear();
        return false;
    }

    bool java_ok = false;
    // 1. java.util.TimeZone.setDefault(TimeZone.getTimeZone(id))
    jclass time_zone = env->FindClass("java/util/TimeZone");
    if (time_zone != nullptr) {
        jmethodID get_tz = env->GetStaticMethodID(
            time_zone, "getTimeZone", "(Ljava/lang/String;)Ljava/util/TimeZone;");
        jmethodID set_default = env->GetStaticMethodID(
            time_zone, "setDefault", "(Ljava/util/TimeZone;)V");
        if (get_tz != nullptr && set_default != nullptr) {
            jobject tz_obj = env->CallStaticObjectMethod(time_zone, get_tz, jtz_id);
            if (tz_obj != nullptr) {
                env->CallStaticVoidMethod(time_zone, set_default, tz_obj);
                env->DeleteLocalRef(tz_obj);
                java_ok = true;
            }
        }
        env->DeleteLocalRef(time_zone);
    }
    env->ExceptionClear();

    // 2. android.icu.util.TimeZone.setDefault(TimeZone.getTimeZone(id))
    jclass icu_time_zone = env->FindClass("android/icu/util/TimeZone");
    if (icu_time_zone != nullptr) {
        jmethodID icu_get_tz = env->GetStaticMethodID(
            icu_time_zone, "getTimeZone", "(Ljava/lang/String;)Landroid/icu/util/TimeZone;");
        jmethodID icu_set_default = env->GetStaticMethodID(
            icu_time_zone, "setDefault", "(Landroid/icu/util/TimeZone;)V");
        if (icu_get_tz != nullptr && icu_set_default != nullptr) {
            jobject icu_tz_obj = env->CallStaticObjectMethod(icu_time_zone, icu_get_tz, jtz_id);
            if (icu_tz_obj != nullptr) {
                env->CallStaticVoidMethod(icu_time_zone, icu_set_default, icu_tz_obj);
                env->DeleteLocalRef(icu_tz_obj);
            }
        }
        env->DeleteLocalRef(icu_time_zone);
    }
    env->ExceptionClear();

    env->DeleteLocalRef(jtz_id);
    if (java_ok) {
        log_info(std::string("fallback_java_set_default_timezone: applied for ") + timezone_id);
    } else {
        log_warn("fallback_java_set_default_timezone: failed to invoke Java setDefault");
    }
    return java_ok;
}

void clear_java_default_timezone(JNIEnv *env) {
    if (env == nullptr) return;

    // java.util.TimeZone.setDefault(null) clears the process-local static so the
    // next getDefault() re-reads RuntimeHooks' supplier -> the patched property.
    jclass time_zone = env->FindClass("java/util/TimeZone");
    if (time_zone != nullptr) {
        jmethodID set_default = env->GetStaticMethodID(time_zone, "setDefault",
                                                       "(Ljava/util/TimeZone;)V");
        if (set_default != nullptr) {
            env->CallStaticVoidMethod(time_zone, set_default, nullptr);
        }
        env->DeleteLocalRef(time_zone);
    }
    env->ExceptionClear();

    // android.icu.util.TimeZone.setDefault(null) drops the frozen ICU default
    // that follows java.util.TimeZone; cleared so ZoneId / ICU read fresh.
    jclass icu_time_zone = env->FindClass("android/icu/util/TimeZone");
    if (icu_time_zone != nullptr) {
        jmethodID set_default = env->GetStaticMethodID(icu_time_zone, "setDefault",
                                                       "(Landroid/icu/util/TimeZone;)V");
        if (set_default != nullptr) {
            env->CallStaticVoidMethod(icu_time_zone, set_default, nullptr);
        }
        env->DeleteLocalRef(icu_time_zone);
    }
    env->ExceptionClear();
}

} // namespace

std::string resolve_timezone_for_package(const SubmoduleConfig &config,
                                         const std::string &package_name) {
    if (!config.enabled || !config.system_setting_enabled) return {};

    const auto it = config.time_zone_by_package.find(package_name);
    if (it != config.time_zone_by_package.end()) {
        // Explicit entry: either this package's override or an empty-string
        // "exempt from the global" marker.
        return it->second;
    }
    return config.time_zone_global;
}

bool install_timezone_illusion(JNIEnv *env, const std::string &timezone_id) {
    if (!is_valid_timezone_id(timezone_id)) {
        log_info("install_timezone_illusion: empty, invalid or oversized timezone, skipping");
        return false;
    }

    // Helper to invoke Tier 2 Java fallback whenever native CoW fails.
    auto fail_with_fallback = [&](const char *reason) -> bool {
        log_warn(std::string("install_timezone_illusion: native CoW failed (") + reason +
                 "), falling back to Java");
        fallback_java_set_default_timezone(env, timezone_id);
        return false;
    };

    // Fault in the property. The prop area for persist.sys.timezone is mapped
    // once in zygote and inherited; __system_property_find materialises the
    // context node into that inherited mapping, so the pointer is valid inside
    // the app process at postAppSpecialize.
    prop_info *pi = const_cast<prop_info *>(__system_property_find(kTimezoneKey));
    if (pi == nullptr) {
        return fail_with_fallback("__system_property_find failed");
    }

    uintptr_t start = 0;
    uintptr_t end = 0;
    if (!find_containing_mapping(pi, start, end)) {
        return fail_with_fallback("could not locate property VMA in /proc/self/maps");
    }

    const size_t len = end - start;
    // Sanity check: Bionic property nodes are typically 128 KiB (or 1 MiB for LARGE nodes).
    // Reject anomalous ranges (< 4 KiB or > 16 MiB) to avoid memory corruption.
    if (len < 4096 || len > 16 * 1024 * 1024) {
        return fail_with_fallback("anomalous VMA length");
    }

    const uintptr_t pi_addr = reinterpret_cast<uintptr_t>(pi);
    if (pi_addr < start || pi_addr >= end) {
        return fail_with_fallback("pi pointer outside mapping range");
    }

    const size_t pi_offset = pi_addr - start;
    // Ensure prop_info (96 bytes) and its name fit within the VMA.
    if (pi_offset + 96 + strlen(kTimezoneKey) + 1 > len) {
        return fail_with_fallback("prop_info extends beyond VMA bounds");
    }

    void *snapshot = nullptr;
    try {
        snapshot = malloc(len);
    } catch (const std::bad_alloc &) {
        snapshot = nullptr;
    }
    if (snapshot == nullptr) {
        return fail_with_fallback("allocation failed");
    }

    // 1. Snapshot the inherited (read-only) contents before replacing the VMA.
    memcpy(snapshot, reinterpret_cast<void *>(start), len);

    // 2. Pre-validate structure and patch directly in the snapshot buffer FIRST.
    // This ensures that if layout validation fails, the original VMA remains untouched.
    prop_info *snapshot_pi = reinterpret_cast<prop_info *>(
        reinterpret_cast<uint8_t *>(snapshot) + pi_offset);
    if (!patch_prop_info(snapshot_pi, timezone_id.c_str())) {
        free(snapshot);
        return fail_with_fallback("patch_prop_info validation or write failed");
    }

    // 3. Replace the file-backed shared mapping with a private anonymous one at
    //    the SAME address, then restore the pre-patched snapshot.
    void *mapped = mmap(reinterpret_cast<void *>(start), len, PROT_READ | PROT_WRITE,
                        MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
    if (mapped == MAP_FAILED) {
        free(snapshot);
        return fail_with_fallback("mmap MAP_ANONYMOUS MAP_FIXED failed");
    }
    memcpy(mapped, snapshot, len);
    free(snapshot);

    // 4. Back to read-only like stock.
    if (mprotect(reinterpret_cast<void *>(start), len, PROT_READ) != 0) {
        log_warn("install_timezone_illusion: mprotect PROT_READ failed");
    }

    clear_java_default_timezone(env);
    log_info(std::string("install_timezone_illusion: process time zone -> ") + timezone_id);
    return true;
}

} // namespace arirang