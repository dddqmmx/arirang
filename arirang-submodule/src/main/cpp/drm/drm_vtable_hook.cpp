// drm_vtable_hook.cpp
// Vtable-based DRM hook approach for libarirang_drm_hook.so.
//
// This is the PRIMARY hooking strategy. It scans the target library's
// data sections (.data.rel.ro, .data) for absolute or relative function
// pointers matching the HIDL/AIDL getPropertyByteArray symbols, and
// rewrites those pointer slots to redirect to our spoofing shim.
//
// Unlike instruction-level patching, this approach:
//   - Avoids fragile prologue analysis and PC-relative instruction checks
//   - Is immune to ARMv8.3+ PAC (Pointer Authentication)
//   - Does not require allocating trampoline memory near the target
//   - Leaves code pages untouched (only data pages are modified)
//
// ARM64-ONLY: This entire module assumes AArch64 pointer sizes and
// instruction encodings. It will NOT work on x86, x86_64, or ARM32.

#include "drm_hook_entry.hpp"
#include "drm_symbol_classifier.hpp"
#include "logging.hpp"
#include "symbol_resolver.hpp"
#include "vtable_hook.hpp"

#include <android/log.h>
#include <dlfcn.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace {

constexpr const char *kSymbolNeedle = "getPropertyByteArray";

const char *const kCandidateLibraries[] = {
    "/vendor/lib64/mediadrm/libwvdrmengine.so",
    "/vendor/lib64/libwvhidl.so",
    "/vendor/lib64/libwvaidl.so",
    "/system/lib64/mediadrm/libwvdrmengine.so",
    nullptr,
};

} // anonymous namespace

namespace drm_vtable {

bool install_hook_in_library(const char *library_path) {
    auto symbols = arirang::resolve_symbols_by_substring(library_path, kSymbolNeedle);
    if (symbols.empty()) {
        arirang::log_warn(std::string("drm_vtable: no ") + kSymbolNeedle +
                          " symbols in " + library_path);
        return false;
    }

    for (auto &sym : symbols) {
        arirang::log_info(std::string("drm_vtable: candidate ") + sym.name);
    }

    for (auto &sym : symbols) {
        void *hook = nullptr;
        int hidl = 0;
        const char *flavor = nullptr;
        const drm::HookAbi abi =
            drm::classify_get_property_byte_array(library_path, sym.name);
        if (abi == drm::HookAbi::kAidl) {
            hook = arirang_drm_aidl_entry_get();
            flavor = "AIDL";
        } else if (abi == drm::HookAbi::kHidl) {
            hook = arirang_drm_hidl_hook_get();
            hidl = 1;
            flavor = "HIDL";
        }
        if (hook == nullptr) continue;

        // The vtable store can become visible to another HAL thread before
        // vtable_hook_install returns. Publish the original first so the hook
        // can always call through safely.
        arirang_drm_publish_original(hidl, sym.address);

        std::vector<arirang::VtablePatch> patches;
        if (!arirang::vtable_hook_install(library_path, sym.name.c_str(),
                                          hook, &patches)) {
            arirang::log_warn(std::string("drm_vtable: vtable hook failed for ") +
                              flavor + " " + sym.name);
            continue;
        }

        if (!patches.empty() && patches[0].original_function != sym.address) {
            arirang::vtable_hook_uninstall(patches);
            arirang::log_warn("drm_vtable: original slot did not match resolved symbol");
            continue;
        }
        arirang_drm_publish_hook_method("vtable");
        __android_log_print(ANDROID_LOG_INFO, "ArirangDrmHook",
                            "vtable hook success for %s %s", flavor, sym.name.c_str());
        arirang::log_info(std::string("drm_vtable: hooked ") + flavor +
                          " " + sym.name + " in " + library_path);
        return true;
    }

    arirang::log_warn(std::string("drm_vtable: no matching AIDL/HIDL symbol in ") +
                      library_path);
    return false;
}

bool poll_libraries() {
    for (int attempt = 0; attempt < 120; ++attempt) {
        for (const char *const *p = kCandidateLibraries; *p != nullptr; ++p) {
            const char *path = *p;
            struct stat st{};
            if (stat(path, &st) != 0) continue;
            void *handle = dlopen(path, RTLD_NOW | RTLD_NOLOAD);
            if (handle == nullptr) continue;
            dlclose(handle);

            if (install_hook_in_library(path)) {
                return true;
            }
        }
        usleep(500 * 1000);
    }
    arirang::log_warn("drm_vtable: Widevine plugin .so never appeared; giving up");
    return false;
}

} // namespace drm_vtable
