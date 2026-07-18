#include "jni_utils.hpp"

#include <cstring>
#include <limits>
#include <new>
#include <vector>

namespace arirang {

bool jstring_equals(JNIEnv *env, jstring value, const char *expected) {
    if (env == nullptr || value == nullptr || expected == nullptr) return false;
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return false;
    const bool result = std::strcmp(chars, expected) == 0;
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring new_jstring_utf8(JNIEnv *env, const std::string &value) {
    if (env == nullptr || value.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }

    try {
        std::vector<jchar> utf16;
        utf16.reserve(value.size());
        for (size_t i = 0; i < value.size();) {
            const auto first = static_cast<uint8_t>(value[i]);
            uint32_t code_point = 0;
            size_t sequence_size = 0;
            uint32_t minimum = 0;
            if (first <= 0x7f) {
                code_point = first;
                sequence_size = 1;
            } else if ((first & 0xe0U) == 0xc0U) {
                code_point = first & 0x1fU;
                sequence_size = 2;
                minimum = 0x80;
            } else if ((first & 0xf0U) == 0xe0U) {
                code_point = first & 0x0fU;
                sequence_size = 3;
                minimum = 0x800;
            } else if ((first & 0xf8U) == 0xf0U) {
                code_point = first & 0x07U;
                sequence_size = 4;
                minimum = 0x10000;
            } else {
                return nullptr;
            }

            if (sequence_size > value.size() - i) return nullptr;
            for (size_t offset = 1; offset < sequence_size; ++offset) {
                const auto next = static_cast<uint8_t>(value[i + offset]);
                if ((next & 0xc0U) != 0x80U) return nullptr;
                code_point = (code_point << 6U) | (next & 0x3fU);
            }
            if (code_point < minimum || code_point > 0x10ffffU ||
                (code_point >= 0xd800U && code_point <= 0xdfffU)) {
                return nullptr;
            }

            if (code_point <= 0xffffU) {
                utf16.push_back(static_cast<jchar>(code_point));
            } else {
                code_point -= 0x10000U;
                utf16.push_back(static_cast<jchar>(0xd800U + (code_point >> 10U)));
                utf16.push_back(static_cast<jchar>(0xdc00U + (code_point & 0x3ffU)));
            }
            i += sequence_size;
        }

        const jchar empty = 0;
        const jchar *chars = utf16.empty() ? &empty : utf16.data();
        return env->NewString(chars, static_cast<jsize>(utf16.size()));
    } catch (const std::bad_alloc &) {
        return nullptr;
    }
}

} // namespace arirang
