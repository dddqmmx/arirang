#include "runtime_config.hpp"

#include <atomic>
#include <memory>

namespace arirang {
namespace {

// Immutable process-wide config snapshot. It is seeded once per hooked process
// during specialization (initialize_runtime_config, called from the install_*
// paths while the Zygisk Api/companion is still valid) and then only read.
//
// There is deliberately no periodic refresh: the only processes that retain
// this module past specialization are system_server and com.android.phone, both
// of which run in the zygote SELinux domain and cannot read the app-private
// config directory, and the root companion socket is only safe to use during
// the specialize window (using a cached Api* later crashes the host process).
// So config is fixed for a hooked process's lifetime; the app applies changes
// by having the framework re-fork the affected process.
//
// Accessed through the legacy std::atomic_*(std::shared_ptr*) free functions
// rather than std::atomic<std::shared_ptr<>> because the NDK r23b libc++ does
// not provide the C++20 atomic<shared_ptr> specialization.
std::shared_ptr<const SubmoduleConfig> config_snapshot;

} // namespace

void initialize_runtime_config(const SubmoduleConfig &config) {
    std::shared_ptr<const SubmoduleConfig> snapshot =
        std::make_shared<SubmoduleConfig>(config);
    std::atomic_store_explicit(&config_snapshot, std::move(snapshot),
                               std::memory_order_release);
}

std::shared_ptr<const SubmoduleConfig> current_runtime_config() {
    return std::atomic_load_explicit(&config_snapshot, std::memory_order_acquire);
}

} // namespace arirang
