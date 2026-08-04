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
bool patch_prop_info(prop_info *pi, const char *new_value) {
    if (pi == nullptr || new_value == nullptr) return false;
    const size_t len = strlen(new_value);
    if (len == 0 || len > kPropValueMax) return false;

    auto *raw = reinterpret_cast<uint8_t *>(const_cast<prop_info *>(pi));
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
    if (timezone_id.empty() || timezone_id.size() > kPropValueMax) {
        log_info("install_timezone_illusion: empty or oversized timezone, skipping");
        return false;
    }

    // Fault in the property. The prop area for persist.sys.timezone is mapped
    // once in zygote and inherited; __system_property_find materialises the
    // context node into that inherited mapping, so the pointer is valid inside
    // the app process at postAppSpecialize.
    prop_info *pi = const_cast<prop_info *>(__system_property_find(kTimezoneKey));
    if (pi == nullptr) {
        log_warn("install_timezone_illusion: __system_property_find failed");
        return false;
    }

    uintptr_t start = 0;
    uintptr_t end = 0;
    if (!find_containing_mapping(pi, start, end)) {
        log_warn("install_timezone_illusion: could not locate property VMA");
        return false;
    }
    const size_t len = end - start;
    if (len == 0) return false;

    void *snapshot = nullptr;
    try {
        snapshot = malloc(len);
    } catch (const std::bad_alloc &) {
        snapshot = nullptr;
    }
    if (snapshot == nullptr) {
        log_warn("install_timezone_illusion: allocation failed");
        return false;
    }

    // 1. Snapshot the inherited (read-only) contents before replacing the VMA.
    memcpy(snapshot, reinterpret_cast<void *>(start), len);

    // 2. Replace the file-backed shared mapping with a private anonymous one at
    //    the SAME address, then restore + patch. No file open, no SELinux file
    //    perms, strictly process-local (verified in the field, §17.2).
    void *mapped = mmap(reinterpret_cast<void *>(start), len, PROT_READ | PROT_WRITE,
                        MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
    if (mapped == MAP_FAILED) {
        log_warn("install_timezone_illusion: mmap MAP_ANONYMOUS MAP_FIXED failed");
        free(snapshot);
        return false;
    }
    memcpy(mapped, snapshot, len);
    free(snapshot);

    if (!patch_prop_info(const_cast<prop_info *>(pi), timezone_id.c_str())) {
        log_warn("install_timezone_illusion: patch failed");
        return false;
    }

    // 4. Back to read-only like stock.
    if (mprotect(reinterpret_cast<void *>(start), len, PROT_READ) != 0) {
        log_warn("install_timezone_illusion: mprotect PROT_READ failed");
        return false;
    }

    clear_java_default_timezone(env);
    log_info(std::string("install_timezone_illusion: process time zone -> ") + timezone_id);
    return true;
}

} // namespace arirang