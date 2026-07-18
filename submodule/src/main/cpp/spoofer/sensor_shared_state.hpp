#pragma once

#include <atomic>

namespace arirang {
namespace sensor {

// Shared "is this process the one we should spoof" flag, read by the rule
// engine (sensor_rules.cpp) and the precision hook (sensor_precision.cpp) and
// written by the orchestrator (sensor_spoofer.cpp) — three separate
// translation units. A function-local static inside an inline function is
// ODR-safe and shares a single instance across every TU that includes this
// header.
inline std::atomic<bool> &active_process_flag() {
    static std::atomic<bool> flag{false};
    return flag;
}

inline void set_active_process(bool active) {
    active_process_flag().store(active, std::memory_order_release);
}

inline bool is_active_process() {
    return active_process_flag().load(std::memory_order_relaxed);
}

} // namespace sensor
} // namespace arirang
