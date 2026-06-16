#include "sensor_spoofer.hpp"

#include "logging.hpp"
#include "sensor_aosp_types.hpp"
#include "submodule_config.hpp"
#include "hook/inline_hook.hpp"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstring>
#include <ctime>
#include <dlfcn.h>
#include <mutex>
#include <string>
#include <sys/mman.h>
#include <unistd.h>

namespace arirang {

namespace {

using namespace arirang::sensor;

// ---------------------------------------------------------------------------
// AOSP symbol helpers (delegated to real libutils implementations).
// ---------------------------------------------------------------------------

using String8Ctor = void (*)(void *self);
using String8Dtor = void (*)(void *self);
using String8SetTo = int32_t (*)(void *self, const char *value);
using VectorImplRemoveItemsAt = void *(*)(void *self, size_t index, size_t count);
using VectorImplAdd = void *(*)(void *self, const void *item);

static void *s_libutils = nullptr;
static String8Ctor s_string8_ctor = nullptr;
static String8Dtor s_string8_dtor = nullptr;
static String8SetTo s_string8_set_to = nullptr;
static VectorImplRemoveItemsAt s_vector_remove_items_at = nullptr;
static VectorImplAdd s_vector_add = nullptr;

bool init_aosp_helpers() {
    static bool initialized = false;
    static bool success = false;
    if (initialized) return success;
    initialized = true;

    s_libutils = dlopen("libutils.so", RTLD_NOW);
    if (s_libutils == nullptr) {
        log_warn(std::string("sensor_spoofer: dlopen libutils.so failed: ") + dlerror());
        return false;
    }

    s_string8_ctor = reinterpret_cast<String8Ctor>(dlsym(s_libutils, "_ZN7android7String8C1Ev"));
    s_string8_dtor = reinterpret_cast<String8Dtor>(dlsym(s_libutils, "_ZN7android7String8D1Ev"));
    s_string8_set_to = reinterpret_cast<String8SetTo>(dlsym(s_libutils, "_ZN7android7String85setToEPKc"));
    s_vector_remove_items_at = reinterpret_cast<VectorImplRemoveItemsAt>(
        dlsym(s_libutils, "_ZN7android10VectorImpl13removeItemsAtEmm"));
    s_vector_add = reinterpret_cast<VectorImplAdd>(
        dlsym(s_libutils, "_ZN7android10VectorImpl3addEPKv"));

    if (s_string8_ctor == nullptr || s_string8_dtor == nullptr || s_string8_set_to == nullptr ||
        s_vector_remove_items_at == nullptr || s_vector_add == nullptr) {
        log_warn("sensor_spoofer: missing one or more AOSP symbols, disabling");
        return false;
    }

    success = true;
    return true;
}

} // namespace

namespace sensor {

void real_string8_default_ctor(void *self) {
    if (s_string8_ctor != nullptr) s_string8_ctor(self);
}

void real_string8_dtor(void *self) {
    if (s_string8_dtor != nullptr) s_string8_dtor(self);
}

bool real_string8_set_to(void *self, const char *value) {
    if (s_string8_set_to == nullptr || value == nullptr) return false;
    return s_string8_set_to(self, value) == 0;
}

bool real_vector_remove_items_at(void *vec, size_t index, size_t count) {
    if (s_vector_remove_items_at == nullptr || vec == nullptr) return false;
    s_vector_remove_items_at(vec, index, count);
    return true;
}

bool real_vector_add(void *vec, const void *item) {
    if (s_vector_add == nullptr || vec == nullptr || item == nullptr) return false;
    s_vector_add(vec, item);
    return true;
}

String8::String8() { real_string8_default_ctor(this); }
String8::~String8() { real_string8_dtor(this); }
void String8::setTo(const char *value) { real_string8_set_to(this, value); }

} // namespace sensor

namespace {

// ---------------------------------------------------------------------------
// Config cache (throttled reload from disk).
// ---------------------------------------------------------------------------

static const SubmoduleConfig *s_active_config = nullptr;
static SubmoduleConfig s_runtime_config;
static bool s_active_process = false;
static long long s_last_config_reload_ms = 0;
static std::mutex s_config_mutex;
static std::mutex s_install_mutex;
static bool s_hooks_installed = false;

long long monotonic_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<long long>(ts.tv_sec) * 1000LL + ts.tv_nsec / 1000000LL;
}

const SubmoduleConfig *current_sensor_config() {
    if (s_active_config == nullptr) return nullptr;

    const long long now = monotonic_ms();
    if (now - s_last_config_reload_ms <= 1000) {
        return s_active_config;
    }

    std::lock_guard<std::mutex> lock(s_config_mutex);
    if (now - s_last_config_reload_ms <= 1000) {
        return s_active_config;
    }

    if (load_config_from_disk(s_runtime_config)) {
        s_active_config = &s_runtime_config;
    }
    s_last_config_reload_ms = now;
    return s_active_config;
}

// ---------------------------------------------------------------------------
// String helpers.
// ---------------------------------------------------------------------------

std::string to_lower(const std::string &s) {
    std::string out = s;
    std::transform(out.begin(), out.end(), out.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return out;
}

bool contains_ci(const char *haystack, const std::string &needle) {
    if (needle.empty()) return true;
    if (haystack == nullptr) return false;
    std::string h = haystack;
    return to_lower(h).find(to_lower(needle)) != std::string::npos;
}

std::string replace_first_ci(const std::string &base, const std::string &keyword,
                             const std::string &replacement) {
    if (keyword.empty()) return base;
    const std::string lower_base = to_lower(base);
    const std::string lower_key = to_lower(keyword);
    size_t pos = lower_base.find(lower_key);
    if (pos == std::string::npos) return base;
    std::string out = base;
    out.replace(pos, keyword.size(), replacement);
    return out;
}

// ---------------------------------------------------------------------------
// Rule application on a Vector<Sensor>.
// ---------------------------------------------------------------------------

bool should_block(const Sensor &sensor, const SubmoduleConfig &config) {
    for (const auto &rule : config.sensor_blacklist) {
        bool type_match = (rule.type >= 0 && sensor.getType() == rule.type);
        bool name_match = !rule.name_contains.empty() &&
                          contains_ci(sensor.name().c_str(), rule.name_contains);
        bool vendor_match = !rule.vendor_contains.empty() &&
                            contains_ci(sensor.vendor().c_str(), rule.vendor_contains);

        bool matched = false;
        if (rule.type >= 0 && !rule.name_contains.empty() && !rule.vendor_contains.empty()) {
            matched = type_match && name_match && vendor_match;
        } else if (rule.type >= 0 && !rule.name_contains.empty()) {
            matched = type_match && name_match;
        } else if (rule.type >= 0 && !rule.vendor_contains.empty()) {
            matched = type_match && vendor_match;
        } else if (!rule.name_contains.empty() && !rule.vendor_contains.empty()) {
            matched = name_match && vendor_match;
        } else if (rule.type >= 0) {
            matched = type_match;
        } else if (!rule.name_contains.empty()) {
            matched = name_match;
        } else if (!rule.vendor_contains.empty()) {
            matched = vendor_match;
        }

        if (matched) return true;
    }
    return false;
}

void apply_global_vendor_replacement(Sensor &sensor, const SubmoduleConfig &config) {
    if (config.sensor_global_vendor_replacement.empty()) return;
    if (config.sensor_vendor_keywords.empty()) return;

    std::string name = sensor.name().c_str();
    std::string vendor = sensor.vendor().c_str();
    bool changed = false;

    for (const auto &keyword : config.sensor_vendor_keywords) {
        if (keyword.empty()) continue;
        std::string old_name = name;
        std::string old_vendor = vendor;
        name = replace_first_ci(name, keyword, config.sensor_global_vendor_replacement);
        vendor = replace_first_ci(vendor, keyword, config.sensor_global_vendor_replacement);
        if (name != old_name || vendor != old_vendor) changed = true;
    }

    if (changed) {
        sensor.name().setTo(name.c_str());
        sensor.vendor().setTo(vendor.c_str());
    }
}

void apply_overrides(Sensor &sensor, const SubmoduleConfig &config) {
    for (const auto &rule : config.sensor_overrides) {
        bool type_match = rule.match_type < 0 || sensor.getType() == rule.match_type;
        bool name_match = rule.match_name_contains.empty() ||
                          contains_ci(sensor.name().c_str(), rule.match_name_contains);
        bool vendor_match = rule.match_vendor_contains.empty() ||
                            contains_ci(sensor.vendor().c_str(), rule.match_vendor_contains);

        if (!type_match || !name_match || !vendor_match) continue;

        if (!rule.new_name.empty()) {
            sensor.name().setTo(rule.new_name.c_str());
        }
        if (!rule.new_vendor.empty()) {
            sensor.vendor().setTo(rule.new_vendor.c_str());
        }
        if (rule.new_type >= 0) {
            sensor.setType(rule.new_type);
        }
        if (rule.new_handle >= 0) {
            sensor.setHandle(rule.new_handle);
        }
    }
}

void inject_sensors(Vector *list, const SubmoduleConfig &config) {
    for (const auto &entry : config.sensor_injections) {
        Sensor injection;
        injection.setType(entry.type);
        injection.setHandle(entry.handle);
        if (!entry.name.empty()) {
            injection.name().setTo(entry.name.c_str());
        }
        if (!entry.vendor.empty()) {
            injection.vendor().setTo(entry.vendor.c_str());
        }
        list->push_back(&injection);
    }
}

void apply_rules(Vector *list) {
    const auto *config = current_sensor_config();
    if (config == nullptr || !config->enabled || list == nullptr || !s_active_process) return;

    if (list->item_size() != sizeof(Sensor)) {
        log_warn(std::string("sensor_spoofer: size mismatch, expected=") +
                 std::to_string(sizeof(Sensor)) + " got=" +
                 std::to_string(list->item_size()) + ", skipping");
        return;
    }

    if (config->sensor_hide_all) {
        while (!list->empty()) {
            list->remove_index(list->size() - 1);
        }
        return;
    }

    for (size_t i = list->size(); i-- > 0; ) {
        if (should_block((*list)[i], *config)) {
            list->remove_index(i);
        }
    }

    for (size_t i = 0; i < list->size(); ++i) {
        Sensor &sensor = (*list)[i];
        apply_global_vendor_replacement(sensor, *config);
        apply_overrides(sensor, *config);
    }

    inject_sensors(list, *config);
}

// ---------------------------------------------------------------------------
// Vtable slot patching.
//
// BnSensorServer::onTransact calls getSensorList via vtable[4] (offset 0x20)
// and getDynamicSensorList via vtable[5] (offset 0x28).
// We hook onTransact to capture the vtable pointer and patch these slots.
// ---------------------------------------------------------------------------

constexpr size_t kGetSensorListSlot = 0x20;
constexpr size_t kGetDynamicSensorListSlot = 0x28;

// Forward declarations for hooks used in patch_vtable.
Vector hook_get_sensor_list(void *self, const void *opPackageName);
Vector hook_get_dynamic_sensor_list(void *self, const void *opPackageName);

using GetSensorListFn = Vector (*)(void *, const void *);
using OnTransactFn = int32_t (*)(void *, uint32_t, const void *, void *, uint32_t);

static GetSensorListFn s_orig_get_sensor_list = nullptr;
static GetSensorListFn s_orig_get_dynamic_sensor_list = nullptr;
static OnTransactFn s_orig_on_transact = nullptr;
static std::mutex s_vtable_mutex;
static bool s_vtable_patched = false;

bool make_page_writable(uintptr_t addr) {
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return false;
    const uintptr_t aligned = addr & ~static_cast<uintptr_t>(page_size - 1);
    return mprotect(reinterpret_cast<void *>(aligned),
                    static_cast<size_t>(page_size), PROT_READ | PROT_WRITE) == 0;
}

bool make_page_readonly(uintptr_t addr) {
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return false;
    const uintptr_t aligned = addr & ~static_cast<uintptr_t>(page_size - 1);
    return mprotect(reinterpret_cast<void *>(aligned),
                    static_cast<size_t>(page_size), PROT_READ) == 0;
}

void patch_vtable(void *sensor_service) {
    if (sensor_service == nullptr) return;
    std::lock_guard<std::mutex> lock(s_vtable_mutex);
    if (s_vtable_patched) return;

    void **vtable = *reinterpret_cast<void ***>(sensor_service);
    if (vtable == nullptr) {
        log_warn("sensor_spoofer: vtable pointer is null");
        return;
    }

    uintptr_t slot1 = reinterpret_cast<uintptr_t>(&vtable[kGetSensorListSlot / sizeof(void *)]);
    uintptr_t slot2 = reinterpret_cast<uintptr_t>(&vtable[kGetDynamicSensorListSlot / sizeof(void *)]);

    s_orig_get_sensor_list = reinterpret_cast<GetSensorListFn>(vtable[kGetSensorListSlot / sizeof(void *)]);
    s_orig_get_dynamic_sensor_list = reinterpret_cast<GetSensorListFn>(vtable[kGetDynamicSensorListSlot / sizeof(void *)]);

    log_info(std::string("sensor_spoofer: vtable @ ") +
             std::to_string(reinterpret_cast<uintptr_t>(vtable)) +
             " getSensorList=" + std::to_string(reinterpret_cast<uintptr_t>(s_orig_get_sensor_list)) +
             " getDynamicSensorList=" + std::to_string(reinterpret_cast<uintptr_t>(s_orig_get_dynamic_sensor_list)));

    bool writable1 = make_page_writable(slot1);
    bool writable2 = make_page_writable(slot2);
    if (!writable1 || !writable2) {
        log_warn("sensor_spoofer: failed to make vtable page writable");
        return;
    }

    vtable[kGetSensorListSlot / sizeof(void *)] = reinterpret_cast<void *>(hook_get_sensor_list);
    vtable[kGetDynamicSensorListSlot / sizeof(void *)] = reinterpret_cast<void *>(hook_get_dynamic_sensor_list);

    make_page_readonly(slot1);
    make_page_readonly(slot2);

    s_vtable_patched = true;
    log_info("sensor_spoofer: vtable slots patched");
}

// ---------------------------------------------------------------------------
// Hook functions.
// ---------------------------------------------------------------------------

Vector hook_get_sensor_list(void *self, const void *opPackageName) {
    if (s_orig_get_sensor_list == nullptr) {
        return Vector();
    }
    Vector result = s_orig_get_sensor_list(self, opPackageName);
    apply_rules(&result);
    return result;
}

Vector hook_get_dynamic_sensor_list(void *self, const void *opPackageName) {
    if (s_orig_get_dynamic_sensor_list == nullptr) {
        return Vector();
    }
    Vector result = s_orig_get_dynamic_sensor_list(self, opPackageName);
    apply_rules(&result);
    return result;
}

int32_t hook_on_transact(void *self, uint32_t code, const void *data, void *reply, uint32_t flags) {
    if (!s_vtable_patched) {
        patch_vtable(self);
    }
    return s_orig_on_transact(self, code, data, reply, flags);
}

} // namespace

void install_sensor_spoofer(
    zygisk::Api * /*api*/,
    JNIEnv * /*env*/,
    const SubmoduleConfig &config,
    bool should_spoof_system_server
) {
    s_runtime_config = config;
    s_active_config = &s_runtime_config;
    s_active_process = should_spoof_system_server;

    log_info(std::string("install_sensor_spoofer: called enabled=") +
             (config.sensor_config_enabled ? "true" : "false") +
             " system_server=" + (should_spoof_system_server ? "true" : "false"));

    if (!should_spoof_system_server) {
        log_info("sensor_spoofer: not system_server, skipping");
        return;
    }
    if (!config.sensor_config_enabled) {
        log_info("sensor_spoofer: disabled by config");
        return;
    }

    std::lock_guard<std::mutex> install_lock(s_install_mutex);
    if (s_hooks_installed) {
        log_info("sensor_spoofer: hooks already installed");
        return;
    }

    if (!init_aosp_helpers()) {
        log_warn("sensor_spoofer: AOSP helper initialization failed");
        return;
    }

    void *libsensor = dlopen("libsensor.so", RTLD_NOW | RTLD_GLOBAL);
    if (libsensor == nullptr) {
        log_warn(std::string("sensor_spoofer: dlopen libsensor.so failed: ") + dlerror());
        return;
    }

    void *on_transact = dlsym(libsensor, "_ZN7android14BnSensorServer10onTransactEjRKNS_6ParcelEPS1_j");
    if (on_transact == nullptr) {
        log_warn("sensor_spoofer: failed to resolve BnSensorServer::onTransact");
        return;
    }

    log_info(std::string("sensor_spoofer: onTransact @ ") +
             std::to_string(reinterpret_cast<uintptr_t>(on_transact)));

    void *trampoline = nullptr;
    if (!inline_hook_install(on_transact, reinterpret_cast<void *>(hook_on_transact), &trampoline)) {
        log_warn("sensor_spoofer: failed to inline-hook BnSensorServer::onTransact");
        return;
    }

    s_orig_on_transact = reinterpret_cast<OnTransactFn>(trampoline);
    s_hooks_installed = true;
    log_info("sensor_spoofer: installed BnSensorServer::onTransact hook, waiting for vtable patch");
}

} // namespace arirang
