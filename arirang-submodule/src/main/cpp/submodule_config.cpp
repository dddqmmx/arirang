#include "submodule_config.hpp"

#include "arirang_build_config.hpp"
#include "io_utils.hpp"
#include "logging.hpp"

#include <cstdlib>
#include <unistd.h>

namespace arirang {
namespace {

constexpr size_t kMaxConfigSize = 65536;

std::string parse_json_string(const std::string &json, const char *key, const std::string &fallback) {
    const std::string pattern = std::string("\"") + key + "\"";
    size_t pos = json.find(pattern);
    if (pos == std::string::npos) return fallback;
    pos = json.find(':', pos + pattern.size());
    if (pos == std::string::npos) return fallback;
    pos = json.find('"', pos + 1);
    if (pos == std::string::npos) return fallback;

    std::string result;
    bool escaped = false;
    for (size_t i = pos + 1; i < json.size(); ++i) {
        char c = json[i];
        if (escaped) {
            switch (c) {
                case '"':
                case '\\':
                case '/':
                    result.push_back(c);
                    break;
                case 'n':
                    result.push_back('\n');
                    break;
                case 'r':
                    result.push_back('\r');
                    break;
                case 't':
                    result.push_back('\t');
                    break;
                default:
                    result.push_back(c);
                    break;
            }
            escaped = false;
        } else if (c == '\\') {
            escaped = true;
        } else if (c == '"') {
            return result;
        } else {
            result.push_back(c);
        }
    }
    return fallback;
}

bool parse_json_bool(const std::string &json, const char *key, bool fallback) {
    const std::string pattern = std::string("\"") + key + "\"";
    size_t pos = json.find(pattern);
    if (pos == std::string::npos) return fallback;
    pos = json.find(':', pos + pattern.size());
    if (pos == std::string::npos) return fallback;
    ++pos;
    while (pos < json.size() && (json[pos] == ' ' || json[pos] == '\n' || json[pos] == '\r' || json[pos] == '\t')) {
        ++pos;
    }
    if (json.compare(pos, 4, "true") == 0) return true;
    if (json.compare(pos, 5, "false") == 0) return false;
    return fallback;
}

jlong parse_json_long(const std::string &json, const char *key, jlong fallback) {
    const std::string pattern = std::string("\"") + key + "\"";
    size_t pos = json.find(pattern);
    if (pos == std::string::npos) return fallback;
    pos = json.find(':', pos + pattern.size());
    if (pos == std::string::npos) return fallback;
    ++pos;
    while (pos < json.size() && (json[pos] == ' ' || json[pos] == '\n' || json[pos] == '\r' || json[pos] == '\t')) {
        ++pos;
    }
    char *end = nullptr;
    long long value = strtoll(json.c_str() + pos, &end, 10);
    if (end == json.c_str() + pos) return fallback;
    return static_cast<jlong>(value);
}

} // namespace

void apply_json_config(SubmoduleConfig &config, const std::string &json) {
    if (json.empty()) {
        log_warn("submodule config not found; using defaults");
        return;
    }

    config.enabled = parse_json_bool(json, "enabled", config.enabled);
    config.device_info_enabled = parse_json_bool(json, "deviceInfoEnabled", config.device_info_enabled);
    config.build_brand = parse_json_string(json, "buildBrand", config.build_brand);
    config.build_manufacturer = parse_json_string(json, "buildManufacturer", config.build_manufacturer);
    config.build_model = parse_json_string(json, "buildModel", config.build_model);
    config.build_device = parse_json_string(json, "buildDevice", config.build_device);
    config.build_product = parse_json_string(json, "buildProduct", config.build_product);
    config.build_board = parse_json_string(json, "buildBoard", config.build_board);
    config.build_hardware = parse_json_string(json, "buildHardware", config.build_hardware);
    config.build_display = parse_json_string(json, "buildDisplay", config.build_display);
    config.build_host = parse_json_string(json, "buildHost", config.build_host);
    config.build_id = parse_json_string(json, "buildId", config.build_id);
    config.build_tags = parse_json_string(json, "buildTags", config.build_tags);
    config.build_type = parse_json_string(json, "buildType", config.build_type);
    config.build_user = parse_json_string(json, "buildUser", config.build_user);
    config.build_fingerprint = parse_json_string(json, "buildFingerprint", config.build_fingerprint);
    config.build_time = parse_json_long(json, "buildTime", config.build_time);
    config.gsm_sim_operator_iso_country =
        parse_json_string(json, "gsmSimOperatorIsoCountry", config.gsm_sim_operator_iso_country);
    config.gsm_operator_iso_country =
        parse_json_string(json, "gsmOperatorIsoCountry", config.gsm_operator_iso_country);
    config.gsm_sim_operator_numeric =
        parse_json_string(json, "gsmSimOperatorNumeric", config.gsm_sim_operator_numeric);
    config.gsm_operator_numeric =
        parse_json_string(json, "gsmOperatorNumeric", config.gsm_operator_numeric);
    config.gsm_sim_operator_alpha =
        parse_json_string(json, "gsmSimOperatorAlpha", config.gsm_sim_operator_alpha);
    config.gsm_operator_alpha = parse_json_string(json, "gsmOperatorAlpha", config.gsm_operator_alpha);
    log_info("loaded submodule config");
}

void load_config_from_companion(zygisk::Api *api, SubmoduleConfig &config) {
    int fd = api->connectCompanion();
    if (fd < 0) {
        log_warn("connectCompanion failed; using defaults");
        return;
    }

    uint32_t size = 0;
    if (!read_exact(fd, &size, sizeof(size)) || size > kMaxConfigSize) {
        close(fd);
        log_warn("failed to read submodule config size; using defaults");
        return;
    }

    std::string json(size, '\0');
    if (size > 0 && !read_exact(fd, json.data(), size)) {
        close(fd);
        log_warn("failed to read submodule config; using defaults");
        return;
    }
    close(fd);
    apply_json_config(config, json);
}

void companion_handler(int fd) {
    std::string content = read_file(kConfigPathDe, kMaxConfigSize);
    if (content.empty()) {
        content = read_file(kConfigPathCe, kMaxConfigSize);
    }
    uint32_t size = static_cast<uint32_t>(content.size());
    write_exact(fd, &size, sizeof(size));
    if (size > 0) {
        write_exact(fd, content.data(), content.size());
    }
}

} // namespace arirang
