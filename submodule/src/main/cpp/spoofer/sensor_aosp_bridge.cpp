#include "sensor_aosp_bridge.hpp"

#include "logging.hpp"
#include "sensor_aosp_types.hpp"

#include <cstddef>
#include <cstdint>
#include <dlfcn.h>
#include <string>

namespace arirang {

namespace {

using String8Ctor = void (*)(void *self);
using String8Dtor = void (*)(void *self);
using String8SetTo = int32_t (*)(void *self, const char *value);
using VectorImplRemoveItemsAt = void *(*)(void *self, size_t index, size_t count);
using VectorImplAdd = void *(*)(void *self, const void *item);

void *s_libutils = nullptr;
String8Ctor s_string8_ctor = nullptr;
String8Dtor s_string8_dtor = nullptr;
String8SetTo s_string8_set_to = nullptr;
VectorImplRemoveItemsAt s_vector_remove_items_at = nullptr;
VectorImplAdd s_vector_add = nullptr;

} // namespace

namespace sensor {

bool init_aosp_helpers() {
    static bool initialized = false;
    static bool success = false;
    if (initialized) return success;
    initialized = true;

    // Sensor and Vector are private framework C++ types. Instead of re-
    // implementing android::String8 / VectorImpl ownership rules, delegate the
    // few constructors/mutators we need to the real libutils symbols.
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
} // namespace arirang
