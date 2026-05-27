//
// Created by canyie on 2020/4/1.
//

#include "well_known_classes.h"
#include "../android.h"

using namespace pine;

jclass WellKnownClasses::java_lang_reflect_ArtMethod = nullptr;
jfieldID WellKnownClasses::java_lang_reflect_Executable_artMethod = nullptr;
void WellKnownClasses::Init(JNIEnv* env) {
    java_lang_reflect_ArtMethod = FindClass(env, PINE_STR("java/lang/reflect/ArtMethod"));
    if (UNLIKELY(Android::version >= Android::kR)) {
        java_lang_reflect_Executable_artMethod = RequireNonStaticFieldID(env,
                PINE_STR("java/lang/reflect/Executable"), PINE_STR("artMethod"), PINE_STR("J"));
    }
}
