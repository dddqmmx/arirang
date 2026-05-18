#pragma once

#include "submodule_config.hpp"
#include "zygisk.hpp"

#include <jni.h>

namespace arirang {

void install_id_spoofer(zygisk::Api *api, JNIEnv *env, const SubmoduleConfig &config, bool should_spoof_process);

} // namespace arirang
