// drm_inline_hook.cpp
// Instruction-level inline hook fallback for libarirang_drm_hook.so.
//
// This is the FALLBACK strategy. When vtable-based hooking fails (no
// usable vtable slots found in the target library's data sections), we
// fall back to patching the function prologue with a jump to our hook.
//
// This approach:
//   - Rewrites the first 4 instructions of the target function
//   - Requires the target prologue to be free of PC-relative instructions
//   - May fail on ARMv8.3+ with PAC enabled (signed return addresses)
//   - Needs nearby memory for the trampoline (±128MB for B instruction)
//
// Every downgrade from vtable to inline is logged as a warning so that
// deployments with PAC or hardened linker configurations are traceable.
//
// ARM64-ONLY: This file is hardcoded for AArch64 instruction encodings
// and will NOT assemble or work on any other architecture.

#include "drm_hook_entry.hpp"
#include "drm_symbol_classifier.hpp"
#include "inline_hook.hpp"
#include "logging.hpp"
#include "symbol_resolver.hpp"

#include <android/log.h>
#include <string>

namespace {

constexpr const char *kSymbolNeedle = "getPropertyByteArray";

} // anonymous namespace

extern "C" void *g_trampoline;
extern "C" void *g_hidl_trampoline;

namespace drm_inline {

bool install_hook_in_library(const char *library_path) {
    auto symbols = arirang::resolve_symbols_by_substring(library_path, kSymbolNeedle);
    if (symbols.empty()) return false;

    // Downgrade warning: vtable approach was already attempted and failed.
    arirang::log_warn(std::string("drm_inline: DOWNGRADE — vtable hook failed, "
                                  "attempting instruction-level patching in ") +
                      library_path);
    __android_log_print(ANDROID_LOG_WARN, "ArirangDrmHook",
                        "DOWNGRADE: vtable hook failed on %s, falling back to "
                        "inline instruction patching", library_path);

    for (auto &sym : symbols) {
        void *target = sym.address;
        if (target == nullptr) continue;

        void *hook = nullptr;
        void **trampoline_slot = nullptr;
        const char *flavor = nullptr;

        const drm::HookAbi abi =
            drm::classify_get_property_byte_array(library_path, sym.name);
        const bool is_aidl = abi == drm::HookAbi::kAidl;
        const bool is_hidl = abi == drm::HookAbi::kHidl;

        if (is_aidl) {
            hook = arirang_drm_aidl_entry_get();
            trampoline_slot = &g_trampoline;
            flavor = "AIDL";
        } else if (is_hidl) {
            hook = arirang_drm_hidl_hook_get();
            trampoline_slot = &g_hidl_trampoline;
            flavor = "HIDL";
        }
        if (hook == nullptr) continue;

        // Publish a callable original before atomically replacing one entry
        // instruction with a branch to a nearby relay. The hook helper writes
        // trampoline_slot with release ordering before the live CAS.
        if (arirang::inline_hook_branch(target, hook, trampoline_slot)) {
            arirang_drm_publish_original(is_hidl,
                                         __atomic_load_n(trampoline_slot, __ATOMIC_ACQUIRE));
            arirang_drm_publish_hook_method("inline_branch");
            arirang::log_info(std::string("drm_inline: B-instruction trampoline hooked ") +
                              flavor + " " + sym.name);
            __android_log_print(ANDROID_LOG_INFO, "ArirangDrmHook",
                                "inline hook success (branch) for %s %s",
                                flavor, sym.name.c_str());
            return true;
        }

        arirang::log_warn(std::string("drm_inline: atomic branch strategy failed for ") +
                          flavor + " " + sym.name);
    }

    arirang::log_warn(std::string("drm_inline: giving up on ") + library_path);
    return false;
}

} // namespace drm_inline
