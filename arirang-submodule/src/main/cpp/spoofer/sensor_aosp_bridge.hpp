#pragma once

namespace arirang {
namespace sensor {

// Resolves the real libutils.so String8/VectorImpl symbols that the local
// String8 and FakeVector<Sensor> wrappers (sensor_aosp_types.hpp) delegate
// to. Must succeed before any Sensor/Vector mutation is attempted; safe to
// call more than once (result is cached after the first call).
bool init_aosp_helpers();

} // namespace sensor
} // namespace arirang
