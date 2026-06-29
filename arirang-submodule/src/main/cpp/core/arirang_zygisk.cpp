#include "zygisk.hpp"
#include "arirang_build_config.hpp"
#include "build_spoofer.hpp"
#include "jni_utils.hpp"
#include "logging.hpp"
#include "submodule_config.hpp"
#include "sensor_spoofer.hpp"
#include "system_property_spoofer.hpp"

#include <string>

class ArirangZygisk final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
        // Prefer direct disk config because it is available even if the Zygisk
        // companion socket is unavailable in this implementation. Fall back to
        // the companion for environments where the module process cannot read
        // the app-owned config paths.
        if (!arirang::load_config_from_disk(config_)) {
            arirang::load_config_from_companion(api_, config_);
        }
        // Build.* static fields are initialized in zygote and inherited by app
        // forks, so spoof them at module load before specialization decisions.
        arirang::spoof_build_fields(env_, config_);
        arirang::log_info("installed zygote inherited Build field spoofing");
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        // nice_name is the process name Zygisk is about to specialize into.
        // Copy it while the JNIEnv/string is still valid; later callbacks only
        // use the cached std::string.
        current_app_process_.clear();
        if (args != nullptr && args->nice_name != nullptr) {
            const char *nice_name = env_->GetStringUTFChars(args->nice_name, nullptr);
            if (nice_name != nullptr) {
                current_app_process_ = nice_name;
                env_->ReleaseStringUTFChars(args->nice_name, nice_name);
            }
        }
        
        /* 
         * MANDATORY DESIGN COMPLIANCE: Arirang is a system-level privacy model.
         * 
         * 1. DO NOT inject hooks into arbitrary third-party applications. This avoids
         *    unnecessary performance impact and runtime behavior interference.
         * 2. Global property protection (e.g. build info, serials) MUST be handled via 
         *    system-level modifications (like resetprop in post-fs-data.sh) rather than 
         *    per-process hooks.
         * 3. Hooks are reserved EXCLUSIVELY for framework-level components that serve
         *    as data providers (e.g., com.android.phone for SIM/IMEI data).
         */
        keep_module_loaded_in_app_ = (current_app_process_ == "com.android.phone");
                                     
        if (!keep_module_loaded_in_app_) {
            // Unload from ordinary app processes immediately after specialization
            // so no native hooks, globals, or background work remain mapped there.
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
        }
    }

    void preServerSpecialize(zygisk::ServerSpecializeArgs *) override {
        // Some Zygisk implementations used by KernelSU Next keep the module
        // mapped in system_server but do not reliably call postServerSpecialize.
        // Install the SensorService vtable hooks before specialization instead.
        if (config_.sensor_config_enabled) {
            arirang::install_sensor_spoofer(api_, env_, config_, true);
        }
        arirang::log_info("preServerSpecialize: installed early system_server hooks");
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (!keep_module_loaded_in_app_) return;
        // The phone process owns several telephony property write/read paths.
        // Keep hooks here source-level so third-party apps observe spoofed data
        // through normal framework IPC rather than by being injected.
        arirang::install_system_property_spoofer(api_, env_, config_, true);
        arirang::log_info(std::string("installed phone process native hooks"));
    }

    void postServerSpecialize(const zygisk::ServerSpecializeArgs *) override {
        arirang::log_info(std::string("postServerSpecialize: enter sensor_enabled=") +
                          (config_.sensor_config_enabled ? "true" : "false"));
        arirang::install_system_property_spoofer(api_, env_, config_, true);
        if (config_.sensor_config_enabled) {
            // SensorService lives in system_server on current target builds.
            // Installing here makes sensor-list and sensor-event spoofing apply
            // to every app through the normal SensorManager service.
            arirang::install_sensor_spoofer(api_, env_, config_, true);
        } else {
            arirang::log_info("postServerSpecialize: sensor disabled by config, skipping");
        }
        arirang::log_info("installed system_server native hooks");
    }

private:
    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
    arirang::SubmoduleConfig config_;
    std::string current_app_process_;
    bool keep_module_loaded_in_app_ = false;
};

REGISTER_ZYGISK_MODULE(ArirangZygisk)
REGISTER_ZYGISK_COMPANION(arirang::companion_handler)
