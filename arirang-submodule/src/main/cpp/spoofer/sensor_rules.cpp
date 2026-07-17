#include "sensor_rules.hpp"

#include "logging.hpp"
#include "runtime_config.hpp"
#include "sensor_aosp_types.hpp"
#include "sensor_shared_state.hpp"
#include "submodule_config.hpp"

#include <algorithm>
#include <cctype>
#include <string>

namespace arirang {

using namespace arirang::sensor;

namespace {

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
        // Vector::push_back copies the item bytes through VectorImpl::add, so
        // the stack-local Sensor may be destroyed immediately after insertion.
        list->push_back(&injection);
    }
}

} // namespace

void apply_rules(Vector *list) {
    const auto config = current_runtime_config();
    if (config == nullptr || !config->enabled || list == nullptr ||
        !sensor::is_active_process()) return;

    if (list->item_size() != sizeof(Sensor)) {
        // A size mismatch means our local mirror in sensor_aosp_types.hpp no
        // longer matches the framework build. Mutating the vector would be
        // unsafe, so fail open and leave the original list untouched.
        log_warn(std::string("sensor_spoofer: size mismatch, expected=") +
                 std::to_string(sizeof(Sensor)) + " got=" +
                 std::to_string(list->item_size()) + ", skipping");
        return;
    }

    if (config->sensor_hide_all) {
        while (!list->empty()) {
            if (!list->remove_index(list->size() - 1)) {
                log_warn("sensor_spoofer: failed to remove sensor while hiding list");
                break;
            }
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

} // namespace arirang
