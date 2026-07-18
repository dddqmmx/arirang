#pragma once

#include <jni.h>

#include <string>

namespace arirang {

bool jstring_equals(JNIEnv *env, jstring value, const char *expected);
jstring new_jstring_utf8(JNIEnv *env, const std::string &value);

} // namespace arirang
