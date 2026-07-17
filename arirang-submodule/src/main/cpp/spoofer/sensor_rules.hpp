#pragma once

#include "sensor_aosp_types.hpp"

namespace arirang {

// Applies the sensor blacklist/override/injection/global-vendor-replacement
// rules from the current runtime config to a getSensorList()/
// getDynamicSensorList() result. No-op if spoofing is disabled, the runtime
// config failed to load, or this process is not the one being spoofed.
void apply_rules(arirang::sensor::Vector *list);

} // namespace arirang
