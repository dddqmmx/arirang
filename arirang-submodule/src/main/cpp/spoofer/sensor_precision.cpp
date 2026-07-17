#include "sensor_precision.hpp"

#include "logging.hpp"
#include "runtime_config.hpp"
#include "sensor_shared_state.hpp"
#include "submodule_config.hpp"
#include "hook/inline_hook.hpp"

#include <cerrno>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <dlfcn.h>
#include <new>
#include <vector>

namespace arirang {

namespace {

// ---------------------------------------------------------------------------
// Sensor event precision reduction.
//
// Sensor sample data is NOT delivered through any binder transaction or
// exported/virtual SensorService method — SensorService::SensorEventConnection
// ::sendEvents() is local and stripped from the on-device symbol table. It
// does, however, funnel every outgoing batch through the exported
// android::BitTube::sendObjects(tube, events, count, objSize) in libsensor.so
// (SensorEventQueue::write tail-calls it with objSize = sizeof(ASensorEvent)).
// Hooking that one function in system_server rewrites readings for every app
// at the source, with no per-app injection.
// ---------------------------------------------------------------------------

// Mirror of sensors_event_t / ASensorEvent (frameworks/native, NDK android/sensor.h).
// We only read `type` and rewrite the leading float vector; the rest is opaque.
struct SensorEvent {
    int32_t version;
    int32_t sensor;
    int32_t type;
    int32_t reserved0;
    int64_t timestamp;
    float data[16];
    uint32_t flags;
    uint32_t reserved1[3];
};

static_assert(sizeof(SensorEvent) == 104, "SensorEvent must match sensors_event_t (104 bytes)");
static_assert(offsetof(SensorEvent, type) == 8, "SensorEvent.type offset mismatch");
static_assert(offsetof(SensorEvent, data) == 24, "SensorEvent.data offset mismatch");

constexpr size_t kSensorEventSize = sizeof(SensorEvent);
constexpr size_t kMaxSensorEventsPerBatch = 4096;

using SendObjectsFn = ssize_t (*)(void *tube, const void *events, size_t count, size_t object_size);
void *s_orig_send_objects = nullptr;

int precision_level_for_type(const SubmoduleConfig &config, int32_t type) {
    for (const auto &rule : config.sensor_precision_rules) {
        if (rule.type == type) return rule.level;
    }
    return 0;
}

// level: 1 = 1 decimal place, 2 = 2 decimal places, 3 = integer only.
float round_to_level(float value, int level) {
    if (!std::isfinite(value)) return value;
    switch (level) {
        case 1: return static_cast<float>(std::round(static_cast<double>(value) * 10.0) / 10.0);
        case 2: return static_cast<float>(std::round(static_cast<double>(value) * 100.0) / 100.0);
        case 3: return std::roundf(value);
        default: return value;
    }
}

ssize_t hook_send_objects(void *tube, const void *events, size_t count, size_t object_size) {
    const auto original = reinterpret_cast<SendObjectsFn>(
        __atomic_load_n(&s_orig_send_objects, __ATOMIC_ACQUIRE));
    if (original == nullptr) return -ENOSYS;

    if (object_size == kSensorEventSize && events != nullptr && count > 0 &&
        count <= kMaxSensorEventsPerBatch && count <= SIZE_MAX / object_size) {
        const auto config = current_runtime_config();
        if (sensor::is_active_process() && config != nullptr &&
            config->enabled && config->sensor_config_enabled &&
            !config->sensor_precision_rules.empty()) {
            try {
                const auto *source = static_cast<const SensorEvent *>(events);
                std::vector<SensorEvent> sanitized(source, source + count);
                for (SensorEvent &event : sanitized) {
                    const int level = precision_level_for_type(*config, event.type);
                    if (level <= 0) continue;
                    // Only the first three float channels are rounded. That covers
                    // accelerometer/gyro/magnetic/vector-style sensors while keeping
                    // event metadata, timestamps, and flags unchanged.
                    event.data[0] = round_to_level(event.data[0], level);
                    event.data[1] = round_to_level(event.data[1], level);
                    event.data[2] = round_to_level(event.data[2], level);
                }
                return original(tube, sanitized.data(), count, object_size);
            } catch (const std::bad_alloc &) {
                log_warn("sensor_spoofer: allocation failed; sending original sensor batch");
            }
        }
    }
    return original(tube, events, count, object_size);
}

} // namespace

void install_send_objects_hook(void *libsensor) {
    if (__atomic_load_n(&s_orig_send_objects, __ATOMIC_ACQUIRE) != nullptr) return;
    if (libsensor == nullptr) {
        log_warn("sensor_spoofer: libsensor handle null, skipping precision hook");
        return;
    }

    void *send_objects = dlsym(libsensor, "_ZN7android7BitTube11sendObjectsERKNS_2spIS0_EEPKvmm");
    if (send_objects == nullptr) {
        log_warn("sensor_spoofer: failed to resolve BitTube::sendObjects, precision disabled");
        return;
    }

    log_info("sensor_spoofer: resolved BitTube::sendObjects");

    if (!inline_hook_branch(send_objects, reinterpret_cast<void *>(hook_send_objects),
                            &s_orig_send_objects)) {
        log_warn("sensor_spoofer: failed to inline-hook BitTube::sendObjects");
        return;
    }

    log_info("sensor_spoofer: installed BitTube::sendObjects hook for precision reduction");
}

} // namespace arirang
