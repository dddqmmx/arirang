#include "sensor_spoofer.hpp"

#include "logging.hpp"
#include "runtime_config.hpp"
#include "sensor_aosp_bridge.hpp"
#include "sensor_precision.hpp"
#include "sensor_shared_state.hpp"
#include "sensor_vtable_patch.hpp"
#include "submodule_config.hpp"

#include <dlfcn.h>
#include <mutex>
#include <string>

namespace arirang {

namespace {

std::mutex s_install_mutex;
bool s_hooks_installed = false;

} // namespace

void install_sensor_spoofer(
    zygisk::Api *api,
    JNIEnv * /*env*/,
    const SubmoduleConfig &config,
    bool should_spoof_system_server
) {
    sensor::set_active_process(should_spoof_system_server);

    log_info(std::string("install_sensor_spoofer: called enabled=") +
             (config.sensor_config_enabled ? "true" : "false") +
             " system_server=" + (should_spoof_system_server ? "true" : "false"));

    if (!should_spoof_system_server) {
        log_info("sensor_spoofer: not system_server, skipping");
        return;
    }
    if (api == nullptr) {
        log_warn("sensor_spoofer: missing Zygisk API, skipping");
        return;
    }
    initialize_runtime_config(config);
    if (!config.sensor_config_enabled) {
        log_info("sensor_spoofer: disabled by config");
        return;
    }

    std::lock_guard<std::mutex> install_lock(s_install_mutex);
    if (s_hooks_installed) {
        log_info("sensor_spoofer: hooks already installed");
        return;
    }

    if (!sensor::init_aosp_helpers()) {
        log_warn("sensor_spoofer: AOSP helper initialization failed");
        return;
    }

    void *libsensor = dlopen("libsensor.so", RTLD_NOW | RTLD_GLOBAL);
    if (libsensor == nullptr) {
        log_warn(std::string("sensor_spoofer: dlopen libsensor.so failed: ") + dlerror());
        return;
    }

    // Precision reduction hooks BitTube::sendObjects (the sensor-event write
    // path) and is independent of the sensor-list onTransact/vtable hook below.
    install_send_objects_hook(libsensor);

    if (!install_on_transact_hook(libsensor)) {
        return;
    }

    s_hooks_installed = true;
}

} // namespace arirang
