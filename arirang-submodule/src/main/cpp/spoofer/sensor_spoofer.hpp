#pragma once

#include "submodule_config.hpp"
#include "zygisk.hpp"

namespace arirang {

// Install SensorService::getSensorList hooks in system_server.
// Should only be called from postServerSpecialize.
void install_sensor_spoofer(
    zygisk::Api *api,
    JNIEnv *env,
    const SubmoduleConfig &config,
    bool should_spoof_system_server
);

} // namespace arirang
