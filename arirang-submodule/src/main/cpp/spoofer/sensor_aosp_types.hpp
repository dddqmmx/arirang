#pragma once

#include <cstdint>
#include <cstddef>

namespace arirang {
namespace sensor {

// Forward declarations matching AOSP types.
namespace android {
class SensorService;
class String16;
} // namespace android

// Helpers implemented in sensor_spoofer.cpp; used by the local String8 class
// to delegate to the real AOSP String8 implementation.
void real_string8_default_ctor(void *self);
void real_string8_dtor(void *self);
bool real_string8_set_to(void *self, const char *value);

// Minimal AOSP String8 layout mirror. The real String8 has no virtual table
// and a single `const char* mString` member. We route operations through the
// real libutils symbols so reference counting stays correct.
class String8 {
public:
    String8();
    ~String8();

    String8(const String8&) = delete;
    String8& operator=(const String8&) = delete;

    void setTo(const char *value);
    const char* c_str() const { return mString; }

private:
    const char *mString = nullptr;
};

// Minimal AOSP Sensor layout mirror. Order, types and sizes must match the
// AOSP definition in frameworks/native/libs/sensor/include/sensor/Sensor.h.
class Sensor {
public:
    enum : int32_t {
        TYPE_ACCELEROMETER  = 1,
        TYPE_MAGNETIC_FIELD = 2,
        TYPE_ORIENTATION    = 3,
        TYPE_GYROSCOPE      = 4,
        TYPE_LIGHT          = 5,
        TYPE_PROXIMITY      = 8,
    };

    String8& name() { return mName; }
    const String8& name() const { return mName; }
    String8& vendor() { return mVendor; }
    const String8& vendor() const { return mVendor; }

    int32_t getHandle() const { return mHandle; }
    void setHandle(int32_t handle) { mHandle = handle; }
    int32_t getType() const { return mType; }
    void setType(int32_t type) { mType = type; }

    int32_t getMaxDelay() const { return mMaxDelay; }

    uint32_t getFlags() const { return mFlags; }

private:
    String8 mName;
    String8 mVendor;
    [[maybe_unused]] int32_t mHandle = 0;
    int32_t mType = 0;
    [[maybe_unused]] float mMinValue = 0.0f;
    [[maybe_unused]] float mMaxValue = 0.0f;
    [[maybe_unused]] float mResolution = 0.0f;
    [[maybe_unused]] float mPower = 0.0f;
    [[maybe_unused]] int32_t mMinDelay = 0;
    [[maybe_unused]] int32_t mVersion = 0;
    [[maybe_unused]] uint32_t mFifoReservedEventCount = 0;
    [[maybe_unused]] uint32_t mFifoMaxEventCount = 0;
    String8 mStringType;
    String8 mRequiredPermission;
    [[maybe_unused]] bool mRequiredPermissionRuntime = false;
    [[maybe_unused]] int32_t mRequiredAppOp = 0;
    int32_t mMaxDelay = 0;
    uint32_t mFlags = 0;
    [[maybe_unused]] struct Uuid { uint64_t data[2]; } mUuid{};
    [[maybe_unused]] int32_t mId = 0;
};

static_assert(sizeof(Sensor) == 112, "arirang Sensor layout does not match AOSP");
static_assert(alignof(Sensor) == 8, "arirang Sensor alignment does not match AOSP");

// Helpers implemented in sensor_spoofer.cpp; used by FakeVector to delegate
// to the real VectorImpl implementation in libutils.
bool real_vector_remove_items_at(void *vec, size_t index, size_t count);
bool real_vector_add(void *vec, const void *item);

// Minimal AOSP Vector<Sensor> layout mirror. Vector<T> privately inherits
// VectorImpl, so its first data member is the real vtable pointer, followed by
// VectorImpl's fields.
//
// This class intentionally has no virtual destructor and no non-trivial copy
// or move semantics. The SensorService object owns the real Vector vtable, so
// destruction/cleanup is dispatched through that vtable. Our wrapper only ever
// reads or modifies the POD fields and delegates modifications to the real
// VectorImpl implementation in libutils.
template <typename T>
class FakeVector {
public:
    FakeVector() = default;
    FakeVector(const FakeVector&) = default;
    FakeVector& operator=(const FakeVector&) = default;
    ~FakeVector() = default;

    size_t size() const { return mCount; }
    bool empty() const { return mCount == 0; }

    T* data() { return reinterpret_cast<T*>(mStorage); }
    const T* data() const { return reinterpret_cast<const T*>(mStorage); }

    T& operator[](size_t index) { return data()[index]; }
    const T& operator[](size_t index) const { return data()[index]; }

    size_t item_size() const { return mItemSize; }

    bool remove_index(size_t index) {
        if (index >= mCount) return false;
        return real_vector_remove_items_at(this, index, 1);
    }

    bool push_back(const T *item) {
        if (item == nullptr) return false;
        return real_vector_add(this, item);
    }

private:
    // Real vtable pointer (not used directly, but must be at offset 0).
    [[maybe_unused]] void *mVtableDummy = nullptr;
    void *mStorage = nullptr;
    size_t mCount = 0;
    uint32_t mFlags = 0;
    size_t mItemSize = 0;
};

static_assert(sizeof(FakeVector<Sensor>) == 40, "arirang FakeVector layout does not match AOSP");
static_assert(alignof(FakeVector<Sensor>) == 8, "arirang FakeVector alignment does not match AOSP");

using Vector = FakeVector<Sensor>;

} // namespace sensor
} // namespace arirang
