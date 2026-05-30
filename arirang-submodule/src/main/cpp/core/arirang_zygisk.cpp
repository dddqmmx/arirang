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
        keep_module_loaded_in_app_ = current_app_process_ == "com.android.phone";
        if (!keep_module_loaded_in_app_) {
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
        }
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (!keep_module_loaded_in_app_) return;
        arirang::install_system_property_spoofer(api_, env_, config_, true);
        arirang::log_info(std::string("installed service app native hooks process=") + current_app_process_);
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
