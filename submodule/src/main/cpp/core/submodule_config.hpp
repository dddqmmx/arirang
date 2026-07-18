#pragma once

#include "zygisk.hpp"

#include <cstdint>
#include <string>
#include <vector>

namespace arirang {

struct SensorBlockRule {
    int32_t type = -1;
    std::string name_contains;
    std::string vendor_contains;
};

struct SensorOverrideRule {
    int32_t match_type = -1;
    std::string match_name_contains;
    std::string match_vendor_contains;
    std::string new_name;
    std::string new_vendor;
    int32_t new_type = -1;
    int32_t new_handle = -1;
};

struct SensorInjectEntry {
    std::string name;
    std::string vendor;
    int32_t type = 0;
    int32_t handle = 0;
};

// Per-sensor-type precision reduction. `level` mirrors the values defined in
// SensorConfigPrefs: 1 = 1 decimal place, 2 = 2 decimal places, 3 = integer
// only. Level 0 (PRECISION_ORIGINAL) is never emitted into the config.
struct SensorPrecisionRule {
    int32_t type = -1;
    int32_t level = 0;
};

struct SubmoduleConfig {
    bool enabled = false;
    bool device_info_enabled = false;
    std::string build_brand = "google";
    std::string build_manufacturer = "Google";
    std::string build_model = "Pixel 9 Pro";
    std::string build_device = "caiman";
    std::string build_product = "caiman";
    std::string build_board = "caiman";
    std::string build_hardware = "caiman";
    std::string build_display = "BP4A.251205.006 release-keys";
    std::string build_host = "android-build";
    std::string build_id = "BP4A.251205.006";
    std::string build_tags = "release-keys";
    std::string build_type = "user";
    std::string build_user = "android-build";
    std::string build_fingerprint = "google/caiman/caiman:15/BP4A.251205.006/1234567:user/release-keys";
    jlong build_time = 1764892800000L;
    std::string gsm_sim_operator_iso_country = "kp,";
    std::string gsm_operator_iso_country = "kp,";
    std::string gsm_sim_operator_numeric = "46705,";
    std::string gsm_operator_numeric = "46705,";
    std::string gsm_sim_operator_alpha = "Koryolink,";
    std::string gsm_operator_alpha = "Koryolink,";
    bool unique_identifier_enabled = false;
    std::string android_id;
    std::string gaid;
    std::string gsf_id;
    std::string widevine_id;
    std::string app_set_id;
    std::string serial;
    jlong sim_config_version = 0;
    std::string sim_config_snapshot;
    jlong unique_identifier_config_version = 0;
    std::string unique_identifier_config_snapshot;
    jlong hook_log_config_version = 0;
    std::string hook_log_config_snapshot;
    jlong wifi_config_version = 0;
    std::string wifi_config_snapshot;
    jlong location_config_version = 0;
    std::string location_config_snapshot;

    // Sensor spoofing configuration.
    bool sensor_config_enabled = false;
    bool sensor_hide_all = false;
    std::string sensor_global_vendor_replacement;
    std::vector<std::string> sensor_vendor_keywords;
    std::vector<SensorBlockRule> sensor_blacklist;
    std::vector<SensorOverrideRule> sensor_overrides;
    std::vector<SensorInjectEntry> sensor_injections;
    std::vector<SensorPrecisionRule> sensor_precision_rules;
    jlong sensor_config_version = 0;
    std::string sensor_config_snapshot;
};

bool apply_json_config(SubmoduleConfig &config, const std::string &json);
bool load_config_from_disk(SubmoduleConfig &config);
bool load_config_from_companion(zygisk::Api *api, SubmoduleConfig &config);
void companion_handler(int fd);

} // namespace arirang
