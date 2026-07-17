#include "submodule_config.hpp"

#include "arirang_build_config.hpp"
#include "io_utils.hpp"
#include "logging.hpp"

#include <limits>
#include <new>
#include <stdexcept>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include "json.hpp"

namespace arirang {
namespace {

constexpr size_t kMaxConfigSize = 65536;
constexpr int kMaxJsonDepth = 64;
constexpr size_t kMaxScalarStringSize = 4096;
constexpr size_t kMaxRuleStringSize = 512;
constexpr size_t kMaxRuleEntries = 256;
constexpr suseconds_t kCompanionTimeoutMicros = 250000;

using Json = nlohmann::json;

class ScopedFd final {
public:
    explicit ScopedFd(int fd) : fd_(fd) {}
    ~ScopedFd() {
        if (fd_ >= 0) close(fd_);
    }

    ScopedFd(const ScopedFd &) = delete;
    ScopedFd &operator=(const ScopedFd &) = delete;

    int get() const { return fd_; }

private:
    int fd_;
};

bool configure_companion_socket(int fd) {
    timeval timeout{};
    timeout.tv_usec = kCompanionTimeoutMicros;
    return setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == 0 &&
           setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) == 0;
}

// The companion process runs as root in the module manager's daemon (outside
// the Zygisk sandbox), so the peer end of this AF_UNIX socket is whichever
// Zygisk-loaded process connected. Anyone who can reach the companion socket
// path could otherwise read the on-disk config -- including any secrets an
// app embeds in it -- so the peer must be root, system_server (AID_SYSTEM),
// or the calling process's own uid before it is served anything.
bool companion_peer_is_authorized(int fd) {
    ucred peer{};
    socklen_t peer_len = sizeof(peer);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &peer_len) != 0 ||
        peer_len != sizeof(peer)) {
        log_warn("companion_handler: failed to read SO_PEERCRED");
        return false;
    }

    // Peers Zygisk legitimately connects during specialization:
    //   root (0)           — zygote before specialize
    //   system (1000)      — system_server
    //   radio/phone (1001) — com.android.phone (telephony property hooks)
    //   own uid            — same process as the companion (defensive)
    constexpr uid_t kAidRoot = 0;
    constexpr uid_t kAidSystem = 1000;
    constexpr uid_t kAidRadio = 1001;
    if (peer.uid == kAidRoot || peer.uid == kAidSystem || peer.uid == kAidRadio ||
        peer.uid == getuid()) {
        return true;
    }

    log_warn(std::string("companion_handler: rejecting untrusted peer uid=") +
             std::to_string(peer.uid));
    return false;
}

void validate_string(const std::string &value, size_t max_size, const char *key) {
    if (value.size() > max_size || value.find('\0') != std::string::npos) {
        throw std::invalid_argument(std::string("invalid string value for ") + key);
    }
}

void read_string(const Json &object, const char *key, std::string &field,
                 size_t max_size = kMaxScalarStringSize) {
    const auto it = object.find(key);
    if (it == object.end() || !it->is_string()) return;
    std::string value = it->get<std::string>();
    validate_string(value, max_size, key);
    field = std::move(value);
}

void read_bool(const Json &object, const char *key, bool &field) {
    const auto it = object.find(key);
    if (it != object.end() && it->is_boolean()) {
        field = it->get<bool>();
    }
}

bool read_int32_value(const Json &value, int32_t &out) {
    if (value.is_number_unsigned()) {
        const uint64_t parsed = value.get<uint64_t>();
        if (parsed > static_cast<uint64_t>(std::numeric_limits<int32_t>::max())) return false;
        out = static_cast<int32_t>(parsed);
        return true;
    }
    if (!value.is_number_integer()) return false;
    const int64_t parsed = value.get<int64_t>();
    if (parsed < std::numeric_limits<int32_t>::min() ||
        parsed > std::numeric_limits<int32_t>::max()) return false;
    out = static_cast<int32_t>(parsed);
    return true;
}

void read_int32(const Json &object, const char *key, int32_t &field) {
    const auto it = object.find(key);
    if (it == object.end()) return;
    int32_t value = 0;
    if (read_int32_value(*it, value)) field = value;
}

void read_jlong(const Json &object, const char *key, jlong &field) {
    const auto it = object.find(key);
    if (it == object.end()) return;
    if (it->is_number_unsigned()) {
        const uint64_t value = it->get<uint64_t>();
        if (value <= static_cast<uint64_t>(std::numeric_limits<jlong>::max())) {
            field = static_cast<jlong>(value);
        }
    } else if (it->is_number_integer()) {
        field = static_cast<jlong>(it->get<int64_t>());
    }
}

const Json *bounded_array(const Json &object, const char *key) {
    const auto it = object.find(key);
    if (it == object.end() || !it->is_array()) return nullptr;
    if (it->size() > kMaxRuleEntries) {
        throw std::length_error(std::string("too many entries in ") + key);
    }
    return &*it;
}

} // namespace

bool apply_json_config(SubmoduleConfig &config, const std::string &json_str) {
    if (json_str.empty()) {
        log_warn("submodule config not found; using defaults");
        return false;
    }
    if (json_str.size() > kMaxConfigSize) {
        log_warn("submodule config exceeds the maximum encoded size");
        return false;
    }

    try {
        auto depth_limiter = [](int depth, Json::parse_event_t event, Json &) {
            if ((event == Json::parse_event_t::object_start ||
                 event == Json::parse_event_t::array_start) && depth > kMaxJsonDepth) {
                throw std::length_error("submodule config nesting is too deep");
            }
            return true;
        };
        const Json j = Json::parse(json_str, depth_limiter);
        if (!j.is_object()) {
            log_warn("submodule config root is not an object");
            return false;
        }

        // Parse into a copy and publish only after every bounded field has been
        // validated. A malformed late field must never leave a half-new config.
        SubmoduleConfig parsed = config;

        // Keep parsing intentionally tolerant: unknown keys are ignored and
        // malformed values leave the existing default in place. The app and the
        // native submodule can then roll forward independently without making
        // old modules fail closed on new config snapshots.
        read_bool(j, "enabled", parsed.enabled);
        read_bool(j, "deviceInfoEnabled", parsed.device_info_enabled);
        read_string(j, "buildBrand", parsed.build_brand);
        read_string(j, "buildManufacturer", parsed.build_manufacturer);
        read_string(j, "buildModel", parsed.build_model);
        read_string(j, "buildDevice", parsed.build_device);
        read_string(j, "buildProduct", parsed.build_product);
        read_string(j, "buildBoard", parsed.build_board);
        read_string(j, "buildHardware", parsed.build_hardware);
        read_string(j, "buildDisplay", parsed.build_display);
        read_string(j, "buildHost", parsed.build_host);
        read_string(j, "buildId", parsed.build_id);
        read_string(j, "buildTags", parsed.build_tags);
        read_string(j, "buildType", parsed.build_type);
        read_string(j, "buildUser", parsed.build_user);
        read_string(j, "buildFingerprint", parsed.build_fingerprint);
        read_jlong(j, "buildTime", parsed.build_time);
        
        // These are mirrored from the app-side SIM config because native code
        // can intercept libc property reads before framework hooks run.
        read_string(j, "gsmSimOperatorIsoCountry", parsed.gsm_sim_operator_iso_country);
        read_string(j, "gsmOperatorIsoCountry", parsed.gsm_operator_iso_country);
        read_string(j, "gsmSimOperatorNumeric", parsed.gsm_sim_operator_numeric);
        read_string(j, "gsmOperatorNumeric", parsed.gsm_operator_numeric);
        read_string(j, "gsmSimOperatorAlpha", parsed.gsm_sim_operator_alpha);
        read_string(j, "gsmOperatorAlpha", parsed.gsm_operator_alpha);
        
        read_bool(j, "uniqueIdentifierEnabled", parsed.unique_identifier_enabled);
        read_string(j, "androidId", parsed.android_id);
        read_string(j, "gaid", parsed.gaid);
        read_string(j, "gsfId", parsed.gsf_id);
        read_string(j, "widevineDrmId", parsed.widevine_id);
        read_string(j, "appSetId", parsed.app_set_id);
        read_string(j, "serial", parsed.serial);
        
        // Raw config snapshots are embedded so Zygisk-loaded code can serve
        // app/runtime hooks through the companion socket without requiring the
        // target process to read the app private directory directly.
        read_jlong(j, "simConfigVersion", parsed.sim_config_version);
        read_string(j, "simConfigSnapshot", parsed.sim_config_snapshot, kMaxConfigSize);
        read_jlong(j, "uniqueIdentifierConfigVersion", parsed.unique_identifier_config_version);
        read_string(j, "uniqueIdentifierConfigSnapshot", parsed.unique_identifier_config_snapshot,
                    kMaxConfigSize);
        read_jlong(j, "hookLogConfigVersion", parsed.hook_log_config_version);
        read_string(j, "hookLogConfigSnapshot", parsed.hook_log_config_snapshot, kMaxConfigSize);
        read_jlong(j, "wifiConfigVersion", parsed.wifi_config_version);
        read_string(j, "wifiConfigSnapshot", parsed.wifi_config_snapshot, kMaxConfigSize);
        read_jlong(j, "locationConfigVersion", parsed.location_config_version);
        read_string(j, "locationConfigSnapshot", parsed.location_config_snapshot, kMaxConfigSize);

        // Sensor spoofing configuration.
        read_bool(j, "sensorConfigEnabled", parsed.sensor_config_enabled);
        read_bool(j, "sensorHideAll", parsed.sensor_hide_all);
        read_string(j, "sensorGlobalVendorReplacement", parsed.sensor_global_vendor_replacement,
                    kMaxRuleStringSize);

        if (const Json *items = bounded_array(j, "sensorVendorKeywords")) {
            parsed.sensor_vendor_keywords.clear();
            for (const auto& item : *items) {
                if (item.is_string()) {
                    std::string value = item.get<std::string>();
                    validate_string(value, kMaxRuleStringSize, "sensorVendorKeywords");
                    parsed.sensor_vendor_keywords.push_back(std::move(value));
                }
            }
        }

        if (const Json *items = bounded_array(j, "sensorBlacklist")) {
            parsed.sensor_blacklist.clear();
            for (const auto& item : *items) {
                if (!item.is_object()) continue;
                SensorBlockRule rule;
                read_int32(item, "type", rule.type);
                read_string(item, "nameContains", rule.name_contains, kMaxRuleStringSize);
                read_string(item, "vendorContains", rule.vendor_contains, kMaxRuleStringSize);
                parsed.sensor_blacklist.push_back(std::move(rule));
            }
        }

        if (const Json *items = bounded_array(j, "sensorOverrides")) {
            parsed.sensor_overrides.clear();
            for (const auto& item : *items) {
                if (!item.is_object()) continue;
                SensorOverrideRule rule;
                read_int32(item, "matchType", rule.match_type);
                read_string(item, "matchNameContains", rule.match_name_contains, kMaxRuleStringSize);
                read_string(item, "matchVendorContains", rule.match_vendor_contains,
                            kMaxRuleStringSize);
                read_string(item, "newName", rule.new_name, kMaxRuleStringSize);
                read_string(item, "newVendor", rule.new_vendor, kMaxRuleStringSize);
                read_int32(item, "newType", rule.new_type);
                read_int32(item, "newHandle", rule.new_handle);
                parsed.sensor_overrides.push_back(std::move(rule));
            }
        }

        if (const Json *items = bounded_array(j, "sensorInjections")) {
            parsed.sensor_injections.clear();
            for (const auto& item : *items) {
                if (!item.is_object()) continue;
                SensorInjectEntry entry;
                read_string(item, "name", entry.name, kMaxRuleStringSize);
                read_string(item, "vendor", entry.vendor, kMaxRuleStringSize);
                read_int32(item, "type", entry.type);
                read_int32(item, "handle", entry.handle);
                parsed.sensor_injections.push_back(std::move(entry));
            }
        }

        if (const Json *items = bounded_array(j, "sensorPrecisionRules")) {
            parsed.sensor_precision_rules.clear();
            for (const auto& item : *items) {
                if (!item.is_object()) continue;
                SensorPrecisionRule rule;
                read_int32(item, "type", rule.type);
                read_int32(item, "level", rule.level);
                if (rule.type >= 0 && rule.level >= 1 && rule.level <= 3) {
                    parsed.sensor_precision_rules.push_back(rule);
                }
            }
        }

        read_jlong(j, "sensorConfigVersion", parsed.sensor_config_version);
        read_string(j, "sensorConfigSnapshot", parsed.sensor_config_snapshot, kMaxConfigSize);

        config = std::move(parsed);
    } catch (const Json::parse_error &) {
        log_warn("failed to parse config json: invalid syntax or encoding");
        return false;
    } catch (const std::length_error &) {
        log_warn("failed to parse config json: configured limit exceeded");
        return false;
    } catch (const std::invalid_argument &) {
        log_warn("failed to parse config json: invalid bounded value");
        return false;
    } catch (const std::bad_alloc &) {
        log_warn("failed to parse config json: allocation failed");
        return false;
    } catch (const std::exception &) {
        log_warn("failed to parse config json: validation failed");
        return false;
    }
    log_info(
        std::string("loaded submodule config uniqueIdentifierEnabled=") +
        (config.unique_identifier_enabled ? "true" : "false") +
        " widevineLen=" + std::to_string(config.widevine_id.size()) +
        " locationConfigVersion=" + std::to_string(config.location_config_version) +
        " wifiConfigVersion=" + std::to_string(config.wifi_config_version) +
        " sensorConfigEnabled=" + (config.sensor_config_enabled ? "true" : "false") +
        " sensorBlacklist=" + std::to_string(config.sensor_blacklist.size()) +
        " sensorOverrides=" + std::to_string(config.sensor_overrides.size()) +
        " sensorInjections=" + std::to_string(config.sensor_injections.size())
    );
    return true;
}

bool load_config_from_disk(SubmoduleConfig &config) {
    // Device-encrypted storage is available earlier during boot. Credential-
    // encrypted storage becomes available after user unlock. Try DE first so
    // post-fs-data/service paths can work before unlock, then fall back to CE
    // for older installs or manual debug copies.
    std::string json = read_file(kConfigPathDe, kMaxConfigSize);
    const char *path = kConfigPathDe;
    if (json.empty()) {
        json = read_file(kConfigPathCe, kMaxConfigSize);
        path = kConfigPathCe;
    }
    if (json.empty()) {
        log_warn("submodule config disk reload found no config file");
        return false;
    }
    if (!apply_json_config(config, json)) return false;
    log_info(std::string("submodule config reloaded from ") + path);
    return true;
}

bool load_config_from_companion(zygisk::Api *api, SubmoduleConfig &config) {
    // Zygisk modules cannot safely assume the target process can open the app's
    // private config files. The companion runs in the module manager context
    // and sends a length-prefixed JSON snapshot over the Zygisk socket.
    if (api == nullptr) return false;
    ScopedFd fd(api->connectCompanion());
    if (fd.get() < 0) {
        log_warn("connectCompanion failed; using defaults");
        return false;
    }
    if (!configure_companion_socket(fd.get())) {
        log_warn("failed to configure companion socket timeout");
        return false;
    }

    uint32_t size = 0;
    if (!read_exact(fd.get(), &size, sizeof(size)) || size > kMaxConfigSize) {
        log_warn("failed to read submodule config size; using defaults");
        return false;
    }

    try {
        std::string json(size, '\0');
        if (size > 0 && !read_exact(fd.get(), json.data(), size)) {
            log_warn("failed to read submodule config; using defaults");
            return false;
        }
        return apply_json_config(config, json);
    } catch (const std::bad_alloc &) {
        log_warn("failed to allocate companion config buffer");
        return false;
    }
}

void companion_handler(int fd) {
    // Protocol: uint32 byte length followed by exactly that many UTF-8 JSON
    // bytes. A zero length is a valid "no config" response and lets callers use
    // defaults without blocking module load.
    if (fd < 0 || !configure_companion_socket(fd)) return;
    if (!companion_peer_is_authorized(fd)) return;

    try {
        std::string content = read_file(kConfigPathDe, kMaxConfigSize);
        if (content.empty()) {
            content = read_file(kConfigPathCe, kMaxConfigSize);
        }
        const uint32_t size = static_cast<uint32_t>(content.size());
        if (!write_exact(fd, &size, sizeof(size))) return;
        if (size > 0) {
            write_exact(fd, content.data(), content.size());
        }
    } catch (const std::bad_alloc &) {
        const uint32_t empty_size = 0;
        write_exact(fd, &empty_size, sizeof(empty_size));
    }
}

} // namespace arirang
