#include "zygisk.hpp"
#include "arirang_build_config.hpp"
#include "build_spoofer.hpp"
#include "id_spoofer.hpp"
#include "jni_utils.hpp"
#include "logging.hpp"
#include "submodule_config.hpp"
#include "system_property_spoofer.hpp"

class ArirangZygisk final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *) override {
        arirang::load_config_from_companion(api_, config_);
        active_process_ = true;
        arirang::install_system_property_spoofer(api_, env_, config_, active_process_);
        arirang::install_id_spoofer(api_, env_, config_, active_process_);
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (!active_process_) return;
        arirang::spoof_build_fields(env_, config_);
        arirang::log_info("spoofed Build fields");
    }

private:
    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
    arirang::SubmoduleConfig config_;
    bool active_process_ = false;
};

REGISTER_ZYGISK_MODULE(ArirangZygisk)
REGISTER_ZYGISK_COMPANION(arirang::companion_handler)
