#include "jni_utils.hpp"

#include <cstring>

namespace arirang {

bool jstring_equals(JNIEnv *env, jstring value, const char *expected) {
    if (value == nullptr) return false;
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return false;
    const bool result = std::strcmp(chars, expected) == 0;
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

} // namespace arirang
