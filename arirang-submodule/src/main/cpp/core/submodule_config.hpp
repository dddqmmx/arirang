#pragma once

#include "zygisk.hpp"

#include <cstdint>
#include <string>

namespace arirang {

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
};

void apply_json_config(SubmoduleConfig &config, const std::string &json);
bool load_config_from_disk(SubmoduleConfig &config);
void load_config_from_companion(zygisk::Api *api, SubmoduleConfig &config);
void companion_handler(int fd);

} // namespace arirang
