#include "timezone_prop_cow.hpp"

#include "jni_utils.hpp"
#include "logging.hpp"

#include <cerrno>
#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sys/mman.h>
#include <sys/system_properties.h>
#include <unistd.h>

namespace arirang {
namespace {

constexpr const char *kTimezoneKey = "persist.sys.timezone";
// strlen("persist.sys.timezone") computed at compile time; see `raw + kPropNameOffset` below.
constexpr size_t kTimezoneKeyLen = sizeof("persist.sys.timezone") - 1;

// prop_info layout (bionic, sizeof == 96): serial@0 (u32), value[92]@4, name@96.
// The name check below (and the bounds check in install_timezone_illusion) both
// encode this layout; keep them in step with each other if it ever changes.
constexpr size_t kPropNameOffset = 96;
constexpr size_t kPropValueOffset = 4;
constexpr size_t kPropValueMax = PROP_VALUE_MAX; // 92

// prop_area header (bionic, sizeof == 128): bytes_used_@0, serial@4, magic@8,
// version@12. Mirrors the serialized layout in bionic's prop_area.h.
constexpr size_t kPropAreaHeaderSize = 128;
constexpr uint32_t kPropAreaMagic = 0x504f5250;   // "PROP" little-endian ("PRO P")
constexpr uint32_t kPropAreaVersion = 0xfc6ed0ab;

// bionic's property areas are 128 KiB (256 KiB total across u:r:ph:s0), or 1 MiB
// per file when the build defines LARGE_SYSTEM_PROPERTY_NODE. Anything else
// means the mapping we found is not a property area.
constexpr size_t kMinPropAreaSize = 128 * 1024;
constexpr size_t kMaxPropAreaSize = 1024 * 1024;

// Validates [id] against the same grammar the manager app enforces
// (SystemSettingPrefs.TIME_ZONE_ID: ^[A-Za-z][A-Za-z0-9_+/-]*$, max 64 chars),
// so a hand-edited config.json and the app UI judge ids identically, and that
// it fits in the value field WITH its NUL terminator — bionic's writer rejects
// anything where len >= PROP_VALUE_MAX for exactly this reason.
bool is_valid_timezone_id(const std::string &id) {
    if (id.empty() || id.size() > 64 || id.size() >= kPropValueMax) return false;
    const char first = id[0];
    if (!((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z'))) return false;
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
// before modifying bytes: the name at offset 96 must be EXACTLY the timezone
// key (prefix-equal alone would let a "persist.sys.timezone_extra" prop_info
// pass and be corrupted).
bool patch_prop_info(prop_info *pi, const char *new_value) {
    if (pi == nullptr || new_value == nullptr) return false;
    const size_t len = strlen(new_value);
    if (len == 0 || len >= kPropValueMax) return false;

    auto *raw = reinterpret_cast<uint8_t *>(const_cast<prop_info *>(pi));

    // Structural self-check: name at offset 96 must equal kTimezoneKey exactly
    // (prefix AND terminator), so a longer prop sharing the prefix never matches.
    const char *actual_name = reinterpret_cast<const char *>(raw + kPropNameOffset);
    if (strncmp(actual_name, kTimezoneKey, kTimezoneKeyLen) != 0 ||
        actual_name[kTimezoneKeyLen] != '\0') {
        log_warn("patch_prop_info: bionic layout mismatch (name at offset 96 != persist.sys.timezone)");
        return false;
    }

    uint32_t old_serial = 0;
    memcpy(&old_serial, raw, sizeof(old_serial));

    char *value_field = reinterpret_cast<char *>(raw + kPropValueOffset);
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
    // android.icu.util.TimeZone.setDefault(null) treats null as a valid "clear
    // the cached default" input (it routes through setICUDefault(null)), so the
    // next ICU getDefault() rebuilds from java.util, which in turn re-reads the
    // patched property. The two methods live on unrelated classes, so each
    // needs its own parameter signature.
    const struct {
        const char *class_name;
        const char *signature;
    } targets[] = {
        {"java/util/TimeZone", "(Ljava/util/TimeZone;)V"},
        {"android/icu/util/TimeZone", "(Landroid/icu/util/TimeZone;)V"},
    };
    for (const auto &target : targets) {
        jclass tz_class = env->FindClass(target.class_name);
        if (tz_class == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            continue;
        }
        jmethodID set_default = env->GetStaticMethodID(tz_class, "setDefault", target.signature);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (set_default == nullptr) {
            log_warn(std::string("clear_java_default_timezone: setDefault not found for ") +
                     target.class_name);
            env->DeleteLocalRef(tz_class);
            continue;
        }
        env->CallStaticVoidMethod(tz_class, set_default, nullptr);
        if (env->ExceptionCheck()) {
            log_warn(std::string("clear_java_default_timezone: setDefault(null) threw for ") +
                     target.class_name);
            env->ExceptionClear();
        }
        env->DeleteLocalRef(tz_class);
    }
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
        // Skip rather than spoof: a value that cannot be written safely into the
        // property area must not degrade into a partial illusion. The manager
        // app already enforces the same charset, so this normally means a
        // hand-edited config.json carrying something like "GMT+05:30".
        log_info("install_timezone_illusion: empty, invalid or oversized timezone, skipping");
        return false;
    }

    // Helper for every failure path after this point. There is deliberately no
    // Java TimeZone.setDefault fallback here: it cannot produce a consistent
    // illusion (see §12.1/§12.4 of the research doc — the property stays real,
    // handleBindApplication's own setDefault(null) reverts the java.util half,
    // and the ICU half freezes). A partial spoof is a stronger fingerprint
    // than failing closed, so failure stays failure.
    auto fail = [](const char *reason) -> bool {
        log_warn(std::string("install_timezone_illusion: ") + reason);
        return false;
    };

    // Fault in the property. The prop area for persist.sys.timezone is mapped
    // once in zygote and inherited; __system_property_find materialises the
    // context node into that inherited mapping, so the pointer is valid inside
    // the app process at postAppSpecialize.
    prop_info *pi = const_cast<prop_info *>(__system_property_find(kTimezoneKey));
    if (pi == nullptr) {
        return fail("__system_property_find failed; leaving the real zone in place");
    }

    uintptr_t start = 0;
    uintptr_t end = 0;
    if (!find_containing_mapping(pi, start, end)) {
        return fail("could not locate property VMA in /proc/self/maps");
    }
    // find_containing_mapping only returns true for the range that contains pi,
    // so pi is inside [start, end) by construction; no re-check needed.

    const size_t len = end - start;
    // Structural validation instead of a length guess: the VMA must begin with
    // bionic's prop_area header (magic+version) and be sized like a real property
    // area (128 KiB, or 1 MiB for LARGE_SYSTEM_PROPERTY_NODE builds). This both
    // proves the mapping is a property area and anchors the expected size, so a
    // legitimate size outside any guessed band is no longer silently rejected.
    if (len < kPropAreaHeaderSize || len < kMinPropAreaSize || len > kMaxPropAreaSize) {
        return fail("anomalous property-area VMA length; refusing to patch");
    }
    const auto *header = reinterpret_cast<const uint8_t *>(start);
    uint32_t magic = 0;
    uint32_t version = 0;
    uint32_t bytes_used = 0;
    memcpy(&magic, header + 8, sizeof(magic));
    memcpy(&version, header + 12, sizeof(version));
    memcpy(&bytes_used, header + 0, sizeof(bytes_used));
    if (magic != kPropAreaMagic || version != kPropAreaVersion) {
        return fail("property-area header mismatch; refusing to patch");
    }
    // bytes_used_ is an offset from data_ (which starts at the 128-byte header),
    // so the whole payload must fit inside the mapping.
    if (bytes_used > len - kPropAreaHeaderSize) {
        return fail("property-area bytes_used exceeds mapping size; refusing to patch");
    }

    const uintptr_t pi_addr = reinterpret_cast<uintptr_t>(pi);
    const size_t pi_offset = pi_addr - start;
    // Ensure prop_info (96 bytes) and its NUL-terminated name fit within the VMA.
    if (pi_offset + kPropNameOffset + kTimezoneKeyLen + 1 > len) {
        return fail("prop_info extends beyond VMA bounds");
    }

    void *snapshot = malloc(len);
    if (snapshot == nullptr) {
        return fail("allocation failed");
    }

    // 1. Snapshot the inherited (read-only) contents before replacing the VMA.
    memcpy(snapshot, reinterpret_cast<void *>(start), len);

    // 2. Pre-validate structure and patch directly in the snapshot buffer FIRST.
    // This ensures that if layout validation fails, the original VMA remains untouched.
    prop_info *snapshot_pi = reinterpret_cast<prop_info *>(
        reinterpret_cast<uint8_t *>(snapshot) + pi_offset);
    if (!patch_prop_info(snapshot_pi, timezone_id.c_str())) {
        free(snapshot);
        return fail("patch_prop_info validation or write failed");
    }

    // 3. Replace the file-backed shared mapping with a private anonymous one at
    //    the SAME address, then restore the pre-patched snapshot.
    void *mapped = mmap(reinterpret_cast<void *>(start), len, PROT_READ | PROT_WRITE,
                        MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
    if (mapped == MAP_FAILED) {
        free(snapshot);
        return fail("mmap MAP_ANONYMOUS MAP_FIXED failed");
    }
    memcpy(mapped, snapshot, len);
    free(snapshot);

    // 4. Back to read-only like stock. The VMA is already patched at this point,
    //    so a failure here cannot be rolled back — but returning false lets the
    //    caller know the process is left in a non-stock state instead of
    //    reporting success over a writable anonymous mapping where every
    //    subsequent write could corrupt the property area.
    if (mprotect(reinterpret_cast<void *>(start), len, PROT_READ) != 0) {
        log_warn("install_timezone_illusion: mprotect PROT_READ failed; leaving patched VMA writable");
        return false;
    }

    clear_java_default_timezone(env);
    log_info(std::string("install_timezone_illusion: process time zone -> ") + timezone_id);
    return true;
}

} // namespace arirang
