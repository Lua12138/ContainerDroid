//
// Created by Milk on 2021/5/5.
//

//
// Created by Milk on 5/5/21.
//

#include <cstring>
#include "VMClassLoaderHook.h"
#import "JniHook/JniHook.h"
#include "Utils/XorString.h"
static bool hideXposedClass = false;

HOOK_JNI(jobject, findLoadedClass, JNIEnv *env, jobject obj, jobject class_loader, jstring name) {
    const char * nameC = env->GetStringUTFChars(name, JNI_FALSE);
//     ALOGD("findLoadedClass: %s", nameC);
    if (hideXposedClass) {
        if (strstr(nameC, BB_CORE_STR("de/robv/android/xposed/")) ||
            strstr(nameC, BB_CORE_STR("me/weishu/epic")) ||
            strstr(nameC, BB_CORE_STR("me/weishu/exposed")) ||
            strstr(nameC, BB_CORE_STR("de.robv.android")) ||
            strstr(nameC, BB_CORE_STR("me.weishu.epic")) ||
            strstr(nameC, BB_CORE_STR("me.weishu.exposed"))) {
            env->ReleaseStringUTFChars(name, nameC);
            return nullptr;
        }
    }
    jobject result = orig_findLoadedClass(env, obj, class_loader, name);
    env->ReleaseStringUTFChars(name, nameC);
    return result;
}

void VMClassLoaderHook::init(JNIEnv *env) {
    const char *className = BB_CORE_STR("java/lang/VMClassLoader");
    JniHook::HookJniFun(env,
                        className,
                        BB_CORE_STR("findLoadedClass"),
                        BB_CORE_STR("(Ljava/lang/ClassLoader;Ljava/lang/String;)Ljava/lang/Class;"),
                        (void *) new_findLoadedClass,
                        (void **) (&orig_findLoadedClass), true);
}

void VMClassLoaderHook::hideXposed() {
    hideXposedClass = true;
}
