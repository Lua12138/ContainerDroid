//
// Created by Milk on 4/9/21.
//

#ifndef VIRTUALM_VMCORE_H
#define VIRTUALM_VMCORE_H

#include <jni.h>
#include "Utils/XorString.h"

#define VMCORE_CLASS BB_CORE_STR("top/niunaijun/blackbox/core/NativeCore")

class BoxCore {
public:
    static JavaVM *getJavaVM();
    static int getApiLevel();
    static int getCallingUid(JNIEnv *env, int orig);
    static jstring redirectPathString(JNIEnv *env, jstring path);
    static jobject redirectPathFile(JNIEnv *env, jobject path);
    static jlongArray loadEmptyDex(JNIEnv *env);
    static jclass getNativeCoreClass();
    static jobject getFileSystemClass(JNIEnv *env);
    static jobject findMethod(JNIEnv *env, jclass clazz, const char *name, const char *desc);
};


#endif //VIRTUALM_VMCORE_H
