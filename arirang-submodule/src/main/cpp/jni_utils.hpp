#pragma once

#include <jni.h>

namespace arirang {

bool jstring_equals(JNIEnv *env, jstring value, const char *expected);

} // namespace arirang
