#pragma once

#include "submodule_config.hpp"

#include <memory>

namespace arirang {

// Process-wide immutable config cache shared by native hooks.
//
// IMPORTANT: this intentionally does NOT take or retain a zygisk::Api*. The
// Api handed to onLoad/preAppSpecialize/postAppSpecialize/preServerSpecialize/
// postServerSpecialize is only valid for the duration of that specialize
// callback -- calling api->connectCompanion() later (e.g. from inside a
// property/sensor hook that fires long after specialization finished) is an
// indirect call through a vtable that may already be invalid and crashes the
// host process. The snapshot is therefore seeded once during specialization
// and read-only afterwards; see runtime_config.cpp for why no runtime refresh
// is possible from a hooked process.
void initialize_runtime_config(const SubmoduleConfig &config);
std::shared_ptr<const SubmoduleConfig> current_runtime_config();

} // namespace arirang
