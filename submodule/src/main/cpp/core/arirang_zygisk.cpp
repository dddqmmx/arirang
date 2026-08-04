#include "zygisk.hpp"
#include "arirang_build_config.hpp"
#include "build_spoofer.hpp"
#include "jni_utils.hpp"
#include "logging.hpp"
#include "submodule_config.hpp"
#include "sensor_spoofer.hpp"
#include "system_property_spoofer.hpp"
#include "timezone_prop_cow.hpp"

#include <string>

namespace {

// AOSP Process.PHONE_UID / android.uid.phone. Process-name matching alone is
// insufficient because an untrusted APK can request a misleading nice name.
constexpr jint kAndroidPhoneUid = 1001;

} // namespace

class ArirangZygisk final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
        if (api_ == nullptr || env_ == nullptr) {
            arirang::log_warn("onLoad: Zygisk API or JNIEnv unavailable; module disabled for this process");
            return;
        }
        // Deliberately does no work here.
        //
        // onLoad runs in every forked process, which is why preAppSpecialize has
        // to DLCLOSE the module out of ordinary apps below. Reading the config
        // and JNI-writing android.os.Build here therefore did both in every
        // third-party app's VM before that unload decision was made: a disk read
        // and ~15 static field writes on every cold start, plus per-process
        // identity spoofing in apps this module is explicitly not allowed to
        // touch. See the MANDATORY DESIGN COMPLIANCE note in preAppSpecialize --
        // global Build/property identity is resetprop.sh's job, and it already
        // spoofs ro.product.* and ro.build.fingerprint at boot.
        //
        // Config loading and Build spoofing now happen only in the two processes
        // the module actually stays resident in: com.android.phone and
        // system_server.
    }

    /**
     * Loads the submodule config once per process.
     *
     * Prefers direct disk config because it is available even if the Zygisk
     * companion socket is unavailable in this implementation, falling back to
     * the companion where the module process cannot read the app-owned paths.
     */
    void ensure_config_loaded() {
        if (config_loaded_) return;
        config_loaded_ = true;
        if (!arirang::load_config_from_disk(config_)) {
            arirang::load_config_from_companion(api_, config_);
        }
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        // nice_name is the process name Zygisk is about to specialize into.
        // Copy it while the JNIEnv/string is still valid; later callbacks only
        // use the cached std::string.
        current_app_process_.clear();
        current_app_package_.clear();
        current_app_timezone_.clear();
        keep_module_loaded_in_app_ = false;
        if (env_ != nullptr && args != nullptr && args->nice_name != nullptr) {
            const char *nice_name = env_->GetStringUTFChars(args->nice_name, nullptr);
            if (nice_name != nullptr) {
                current_app_process_ = nice_name;
                env_->ReleaseStringUTFChars(args->nice_name, nice_name);
            } else if (env_->ExceptionCheck()) {
                env_->ExceptionClear();
            }
        }
        // The package name comes from the app data dir, NOT the process name:
        // background/isolated/multi-process come through as "<pkg>:<service>"
        // or "<pkg>:push", so matching `nice_name` alone would miss every
        // non-main process of a spoofed app. app_data_dir is "<user-dir>/<pkg>".
        if (env_ != nullptr && args != nullptr && args->app_data_dir != nullptr) {
            const char *data_dir = env_->GetStringUTFChars(args->app_data_dir, nullptr);
            if (data_dir != nullptr) {
                const std::string dir(data_dir);
                const size_t slash = dir.find_last_of('/');
                if (slash != std::string::npos && slash + 1 < dir.size()) {
                    current_app_package_ = dir.substr(slash + 1);
                }
                env_->ReleaseStringUTFChars(args->app_data_dir, data_dir);
            } else if (env_->ExceptionCheck()) {
                env_->ExceptionClear();
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
         *
         * The per-app time zone feature (§4) is the one deliberate exception whose
         * constraint is different: it does NOT inject any hook. It performs a
         * data-only property-area CoW during the specialize callback that fires in
         * every forked process anyway, then lets DLCLOSE remove the module. See
         * timezone_prop_cow.cpp and the research doc.
         */
        keep_module_loaded_in_app_ = args != nullptr &&
                                     args->uid == kAndroidPhoneUid &&
                                     current_app_process_ == "com.android.phone";
                                     
        if (!keep_module_loaded_in_app_) {
            // Ordinary app: load the config to decide whether THIS package has a
            // time zone override. In an ordinary app the disk read fails (the
            // app cannot open the manager's private config paths), so the
            // companion serves a trimmed, secret-free timezone view. DLCLOSE is
            // deferred to postAppSpecialize so the CoW can run against the final
            // credentialed address space before the module is unloaded.
            ensure_config_loaded();
            // Policy: skip isolated processes (webview/sandbox services, uid
            // >= AID_ISOLATED_START 90000). They do not own a meaningful
            // package identity and are not a real user-facing timezone consumer.
            constexpr jint kAidIsolatedStart = 90000;
            if (!current_app_package_.empty() && (args == nullptr || args->uid < kAidIsolatedStart)) {
                current_app_timezone_ = arirang::resolve_timezone_for_package(
                    config_, current_app_package_);
            }
            return;
        }
        // Only the phone process gets this far, so the config read happens there
        // rather than in every app.
        ensure_config_loaded();
    }

    void preServerSpecialize(zygisk::ServerSpecializeArgs *) override {
        ensure_config_loaded();
        // Some Zygisk implementations used by KernelSU Next keep the module
        // mapped in system_server but do not reliably call postServerSpecialize.
        // Install the SensorService vtable hooks before specialization instead.
        if (config_.sensor_config_enabled) {
            arirang::install_sensor_spoofer(api_, env_, config_, true);
        }
        arirang::log_info("preServerSpecialize: installed early system_server hooks");
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (keep_module_loaded_in_app_) {
            // The phone process owns several telephony property write/read paths.
            // Keep hooks here source-level so third-party apps observe spoofed data
            // through normal framework IPC rather than by being injected.
            ensure_config_loaded();
            arirang::spoof_build_fields(env_, config_);
            arirang::install_system_property_spoofer(api_, env_, config_, true);
            arirang::log_info(std::string("installed phone process native hooks"));
            return;
        }

        // Ordinary app: apply the per-process timezone illusion if this package
        // has an override, then always unload the module (unchanged policy).
        if (!current_app_timezone_.empty()) {
            arirang::install_timezone_illusion(env_, current_app_timezone_);
        }
        if (api_ != nullptr) {
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
        }
    }

    void postServerSpecialize(const zygisk::ServerSpecializeArgs *) override {
        ensure_config_loaded();
        arirang::log_info(std::string("postServerSpecialize: enter sensor_enabled=") +
                          (config_.sensor_config_enabled ? "true" : "false"));
        arirang::spoof_build_fields(env_, config_);
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
    std::string current_app_package_;
    std::string current_app_timezone_;
    bool keep_module_loaded_in_app_ = false;
    bool config_loaded_ = false;
};

REGISTER_ZYGISK_MODULE(ArirangZygisk)
REGISTER_ZYGISK_COMPANION(arirang::companion_handler)
