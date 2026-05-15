#pragma once

#include "zygisk.hpp"

#include <cstdint>
#include <string>

namespace arirang {

struct SubmoduleConfig {
    bool enabled = true;
    bool device_info_enabled = true;
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
};

void apply_json_config(SubmoduleConfig &config, const std::string &json);
void load_config_from_companion(zygisk::Api *api, SubmoduleConfig &config);
void companion_handler(int fd);

} // namespace arirang
