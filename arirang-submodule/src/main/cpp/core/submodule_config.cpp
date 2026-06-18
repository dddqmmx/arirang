#include "submodule_config.hpp"

#include "arirang_build_config.hpp"
#include "io_utils.hpp"
#include "logging.hpp"

#include <cstdlib>
#include <unistd.h>

#include "json.hpp"

namespace arirang {
namespace {

constexpr size_t kMaxConfigSize = 65536;

} // namespace

void apply_json_config(SubmoduleConfig &config, const std::string &json_str) {
    if (json_str.empty()) {
        log_warn("submodule config not found; using defaults");
        return;
    }

    try {
        auto j = nlohmann::json::parse(json_str);

        auto read_string = [&](const char* key, std::string& field) {
            if (j.contains(key) && j[key].is_string()) {
                field = j[key].get<std::string>();
            }
        };
        auto read_bool = [&](const char* key, bool& field) {
            if (j.contains(key) && j[key].is_boolean()) {
                field = j[key].get<bool>();
            }
        };
        auto read_long = [&](const char* key, jlong& field) {
            if (j.contains(key) && j[key].is_number()) {
                field = j[key].get<jlong>();
            }
        };

        read_bool("enabled", config.enabled);
        read_bool("deviceInfoEnabled", config.device_info_enabled);
        read_string("buildBrand", config.build_brand);
        read_string("buildManufacturer", config.build_manufacturer);
        read_string("buildModel", config.build_model);
        read_string("buildDevice", config.build_device);
        read_string("buildProduct", config.build_product);
        read_string("buildBoard", config.build_board);
        read_string("buildHardware", config.build_hardware);
        read_string("buildDisplay", config.build_display);
        read_string("buildHost", config.build_host);
        read_string("buildId", config.build_id);
        read_string("buildTags", config.build_tags);
        read_string("buildType", config.build_type);
        read_string("buildUser", config.build_user);
        read_string("buildFingerprint", config.build_fingerprint);
        read_long("buildTime", config.build_time);
        
        read_string("gsmSimOperatorIsoCountry", config.gsm_sim_operator_iso_country);
        read_string("gsmOperatorIsoCountry", config.gsm_operator_iso_country);
        read_string("gsmSimOperatorNumeric", config.gsm_sim_operator_numeric);
        read_string("gsmOperatorNumeric", config.gsm_operator_numeric);
        read_string("gsmSimOperatorAlpha", config.gsm_sim_operator_alpha);
        read_string("gsmOperatorAlpha", config.gsm_operator_alpha);
        
        read_bool("uniqueIdentifierEnabled", config.unique_identifier_enabled);
        read_string("androidId", config.android_id);
        read_string("gaid", config.gaid);
        read_string("gsfId", config.gsf_id);
        read_string("widevineDrmId", config.widevine_id);
        read_string("appSetId", config.app_set_id);
        read_string("serial", config.serial);
        
        read_long("simConfigVersion", config.sim_config_version);
        read_string("simConfigSnapshot", config.sim_config_snapshot);
        read_long("uniqueIdentifierConfigVersion", config.unique_identifier_config_version);
        read_string("uniqueIdentifierConfigSnapshot", config.unique_identifier_config_snapshot);
        read_long("hookLogConfigVersion", config.hook_log_config_version);
        read_string("hookLogConfigSnapshot", config.hook_log_config_snapshot);
        read_long("wifiConfigVersion", config.wifi_config_version);
        read_string("wifiConfigSnapshot", config.wifi_config_snapshot);
        read_long("locationConfigVersion", config.location_config_version);
        read_string("locationConfigSnapshot", config.location_config_snapshot);

        // Sensor spoofing configuration.
        read_bool("sensorConfigEnabled", config.sensor_config_enabled);
        read_bool("sensorHideAll", config.sensor_hide_all);
        read_string("sensorGlobalVendorReplacement", config.sensor_global_vendor_replacement);

        if (j.contains("sensorVendorKeywords") && j["sensorVendorKeywords"].is_array()) {
            config.sensor_vendor_keywords.clear();
            for (const auto& item : j["sensorVendorKeywords"]) {
                if (item.is_string()) {
                    config.sensor_vendor_keywords.push_back(item.get<std::string>());
                }
            }
        }

        if (j.contains("sensorBlacklist") && j["sensorBlacklist"].is_array()) {
            config.sensor_blacklist.clear();
            for (const auto& item : j["sensorBlacklist"]) {
                if (!item.is_object()) continue;
                SensorBlockRule rule;
                if (item.contains("type") && item["type"].is_number()) {
                    rule.type = item["type"].get<int32_t>();
                }
                if (item.contains("nameContains") && item["nameContains"].is_string()) {
                    rule.name_contains = item["nameContains"].get<std::string>();
                }
                if (item.contains("vendorContains") && item["vendorContains"].is_string()) {
                    rule.vendor_contains = item["vendorContains"].get<std::string>();
                }
                config.sensor_blacklist.push_back(std::move(rule));
            }
        }

        if (j.contains("sensorOverrides") && j["sensorOverrides"].is_array()) {
            config.sensor_overrides.clear();
            for (const auto& item : j["sensorOverrides"]) {
                if (!item.is_object()) continue;
                SensorOverrideRule rule;
                if (item.contains("matchType") && item["matchType"].is_number()) {
                    rule.match_type = item["matchType"].get<int32_t>();
                }
                if (item.contains("matchNameContains") && item["matchNameContains"].is_string()) {
                    rule.match_name_contains = item["matchNameContains"].get<std::string>();
                }
                if (item.contains("matchVendorContains") && item["matchVendorContains"].is_string()) {
                    rule.match_vendor_contains = item["matchVendorContains"].get<std::string>();
                }
                if (item.contains("newName") && item["newName"].is_string()) {
                    rule.new_name = item["newName"].get<std::string>();
                }
                if (item.contains("newVendor") && item["newVendor"].is_string()) {
                    rule.new_vendor = item["newVendor"].get<std::string>();
                }
                if (item.contains("newType") && item["newType"].is_number()) {
                    rule.new_type = item["newType"].get<int32_t>();
                }
                if (item.contains("newHandle") && item["newHandle"].is_number()) {
                    rule.new_handle = item["newHandle"].get<int32_t>();
                }
                config.sensor_overrides.push_back(std::move(rule));
            }
        }

        if (j.contains("sensorInjections") && j["sensorInjections"].is_array()) {
            config.sensor_injections.clear();
            for (const auto& item : j["sensorInjections"]) {
                if (!item.is_object()) continue;
                SensorInjectEntry entry;
                if (item.contains("name") && item["name"].is_string()) {
                    entry.name = item["name"].get<std::string>();
                }
                if (item.contains("vendor") && item["vendor"].is_string()) {
                    entry.vendor = item["vendor"].get<std::string>();
                }
                if (item.contains("type") && item["type"].is_number()) {
                    entry.type = item["type"].get<int32_t>();
                }
                if (item.contains("handle") && item["handle"].is_number()) {
                    entry.handle = item["handle"].get<int32_t>();
                }
                config.sensor_injections.push_back(std::move(entry));
            }
        }

        if (j.contains("sensorPrecisionRules") && j["sensorPrecisionRules"].is_array()) {
            config.sensor_precision_rules.clear();
            for (const auto& item : j["sensorPrecisionRules"]) {
                if (!item.is_object()) continue;
                SensorPrecisionRule rule;
                if (item.contains("type") && item["type"].is_number()) {
                    rule.type = item["type"].get<int32_t>();
                }
                if (item.contains("level") && item["level"].is_number()) {
                    rule.level = item["level"].get<int32_t>();
                }
                if (rule.type >= 0 && rule.level > 0) {
                    config.sensor_precision_rules.push_back(rule);
                }
            }
        }

        read_long("sensorConfigVersion", config.sensor_config_version);
        read_string("sensorConfigSnapshot", config.sensor_config_snapshot);

    } catch (const nlohmann::json::exception& e) {
        log_warn(std::string("failed to parse config json: ") + e.what());
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
}

bool load_config_from_disk(SubmoduleConfig &config) {
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
    apply_json_config(config, json);
    log_info(std::string("submodule config reloaded from ") + path);
    return true;
}

void load_config_from_companion(zygisk::Api *api, SubmoduleConfig &config) {
    int fd = api->connectCompanion();
    if (fd < 0) {
        log_warn("connectCompanion failed; using defaults");
        return;
    }

    uint32_t size = 0;
    if (!read_exact(fd, &size, sizeof(size)) || size > kMaxConfigSize) {
        close(fd);
        log_warn("failed to read submodule config size; using defaults");
        return;
    }

    std::string json(size, '\0');
    if (size > 0 && !read_exact(fd, json.data(), size)) {
        close(fd);
        log_warn("failed to read submodule config; using defaults");
        return;
    }
    close(fd);
    apply_json_config(config, json);
}

void companion_handler(int fd) {
    std::string content = read_file(kConfigPathDe, kMaxConfigSize);
    if (content.empty()) {
        content = read_file(kConfigPathCe, kMaxConfigSize);
    }
    uint32_t size = static_cast<uint32_t>(content.size());
    write_exact(fd, &size, sizeof(size));
    if (size > 0) {
        write_exact(fd, content.data(), content.size());
    }
}

} // namespace arirang
