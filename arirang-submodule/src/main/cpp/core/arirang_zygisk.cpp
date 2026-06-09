#include "zygisk.hpp"
#include "arirang_build_config.hpp"
#include "build_spoofer.hpp"
#include "jni_utils.hpp"
#include "logging.hpp"
#include "submodule_config.hpp"
#include "system_property_spoofer.hpp"

#include <string>

class ArirangZygisk final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
        if (!arirang::load_config_from_disk(config_)) {
            arirang::load_config_from_companion(api_, config_);
        }
        arirang::spoof_build_fields(env_, config_);
        arirang::log_info("installed zygote inherited Build field spoofing");
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
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
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
        }
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (!keep_module_loaded_in_app_) return;
        arirang::install_system_property_spoofer(api_, env_, config_, true);
        arirang::log_info(std::string("installed phone process native hooks"));
    }

    void postServerSpecialize(const zygisk::ServerSpecializeArgs *) override {
        arirang::install_system_property_spoofer(api_, env_, config_, true);
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
