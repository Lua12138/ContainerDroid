//
// Created by canyie on 2020/3/18.
//

#include "jni_bridge.h"
#include "utils/macros.h"
#include "utils/log.h"

void Ruler_m1(JNIEnv*, jclass) {
    LOGI("Don't call me...");
}

bool register_Ruler(JNIEnv* env, jclass Ruler) {
    JNINativeMethod methods[] = {
            {PINE_STR("m1"), PINE_STR("()V"), reinterpret_cast<void*>(Ruler_m1)}
    };
    return LIKELY(env->RegisterNatives(Ruler, methods, NELEM(methods)) == JNI_OK);
}
