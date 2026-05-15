#pragma once

#include "submodule_config.hpp"
#include "zygisk.hpp"

namespace arirang {

void install_system_property_spoofer(
    zygisk::Api *api,
    JNIEnv *env,
    const SubmoduleConfig &config,
    bool should_spoof_process
);

} // namespace arirang
