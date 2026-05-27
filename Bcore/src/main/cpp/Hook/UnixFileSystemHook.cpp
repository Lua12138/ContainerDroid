//
// Created by Milk on 4/9/21.
//

#include <IO.h>
#include "BoxCore.h"
#include "UnixFileSystemHook.h"
#import "JniHook/JniHook.h"
#include "BaseHook.h"

/*
 * Class:     java_io_UnixFileSystem
 * Method:    canonicalize0
 * Signature: (Ljava/lang/String;)Ljava/lang/String;
 */
HOOK_JNI(jstring, canonicalize0, JNIEnv *env, jobject obj, jstring path) {
    jstring redirect = IO::redirectPath(env, path);
    jstring canonical = orig_canonicalize0(env, obj, redirect);
    return IO::reverseRedirectPath(env, canonical);
}

HOOK_JNI(jstring, canonicalize0_v35, JNIEnv *env, jobject obj, jstring path, jboolean isAtLeastTargetSdk35) {
    jstring redirect = IO::redirectPath(env, path);
    jstring canonical = orig_canonicalize0_v35(env, obj, redirect, isAtLeastTargetSdk35);
    return IO::reverseRedirectPath(env, canonical);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    getBooleanAttributes0
 * Signature: (Ljava/lang/String;)I
 */
HOOK_JNI(jint, getBooleanAttributes0, JNIEnv *env, jobject obj, jstring abspath) {
    jstring redirect = IO::redirectPath(env, abspath);
    return orig_getBooleanAttributes0(env, obj, redirect);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    getLastModifiedTime0
 * Signature: (Ljava/io/File;)J
 */
HOOK_JNI(jlong, getLastModifiedTime0, JNIEnv *env, jobject obj, jobject path) {
    jobject redirect = IO::redirectPath(env, path);
    return orig_getLastModifiedTime0(env, obj, redirect);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    setPermission0
 * Signature: (Ljava/io/File;IZZ)Z
 */
HOOK_JNI(jboolean, setPermission0, JNIEnv *env, jobject obj, jobject file, jint access,
         jboolean enable, jboolean owneronly) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_setPermission0(env, obj, redirect, access, enable, owneronly);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    createFileExclusively0
 * Signature: (Ljava/lang/String;)Z
 */
HOOK_JNI(jboolean, createFileExclusively0, JNIEnv *env, jobject obj, jstring path) {
    jstring redirect = IO::redirectPath(env, path);
    return orig_createFileExclusively0(env, obj, redirect);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    list0
 * Signature: (Ljava/io/File;)[Ljava/lang/String;
 */
HOOK_JNI(jobjectArray, list0, JNIEnv *env, jobject obj, jobject file) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_list0(env, obj, redirect);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    createDirectory0
 * Signature: (Ljava/io/File;)Z
 */
HOOK_JNI(jboolean, createDirectory0, JNIEnv *env, jobject obj, jobject path) {
    jobject redirect = IO::redirectPath(env, path);
    return orig_createDirectory0(env, obj, redirect);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    setLastModifiedTime0
 * Signature: (Ljava/io/File;J)Z
 */
HOOK_JNI(jboolean, setLastModifiedTime0, JNIEnv *env, jobject obj, jobject file, jlong time) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_setLastModifiedTime0(env, obj, redirect, time);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    setReadOnly0
 * Signature: (Ljava/io/File;)Z
 */
HOOK_JNI(jboolean, setReadOnly0, JNIEnv *env, jobject obj, jobject file) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_setReadOnly0(env, obj, redirect);
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    getSpace0
 * Signature: (Ljava/io/File;I)J
 */
HOOK_JNI(jlong, getSpace0, JNIEnv *env, jobject obj, jobject file, jint t) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_getSpace0(env, obj, redirect, t);
}

static void Hook(JNIEnv *env, jclass clazz, const char *method_name, const char *sign,
                 void *new_fun, void **orig_fun) {
    jobject method = BoxCore::findMethod(env, clazz, method_name, sign);
    if (method == nullptr) {
        ALOGD("skip hook, method not found: %s %s", method_name, sign);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return;
    }
    JniHook::HookJniFun(env, method, new_fun, orig_fun, false);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(method);
}

void UnixFileSystemHook::init(JNIEnv *env) {
    jclass clazz = reinterpret_cast<jclass>(BoxCore::getFileSystemClass(env));
    if (clazz == nullptr) {
        ALOGE("getFileSystemClass failed.");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return;
    }
    if (BoxCore::getApiLevel() >= 35) {
        Hook(env, clazz, BB_CORE_STR("canonicalize0"), BB_CORE_STR("(Ljava/lang/String;Z)Ljava/lang/String;"),
             (void *) new_canonicalize0_v35, (void **) (&orig_canonicalize0_v35));
    } else {
        Hook(env, clazz, BB_CORE_STR("canonicalize0"), BB_CORE_STR("(Ljava/lang/String;)Ljava/lang/String;"),
             (void *) new_canonicalize0, (void **) (&orig_canonicalize0));
    }
    Hook(env, clazz, BB_CORE_STR("getBooleanAttributes0"), BB_CORE_STR("(Ljava/lang/String;)I"),
         (void *) new_getBooleanAttributes0, (void **) (&orig_getBooleanAttributes0));
    Hook(env, clazz, BB_CORE_STR("getLastModifiedTime0"), BB_CORE_STR("(Ljava/io/File;)J"),
         (void *) new_getLastModifiedTime0, (void **) (&orig_getLastModifiedTime0));
    Hook(env, clazz, BB_CORE_STR("setPermission0"), BB_CORE_STR("(Ljava/io/File;IZZ)Z"),
         (void *) new_setPermission0, (void **) (&orig_setPermission0));
    Hook(env, clazz, BB_CORE_STR("createFileExclusively0"), BB_CORE_STR("(Ljava/lang/String;)Z"),
         (void *) new_createFileExclusively0, (void **) (&orig_createFileExclusively0));
    Hook(env, clazz, BB_CORE_STR("list0"), BB_CORE_STR("(Ljava/io/File;)[Ljava/lang/String;"),
         (void *) new_list0, (void **) (&orig_list0));
    Hook(env, clazz, BB_CORE_STR("createDirectory0"), BB_CORE_STR("(Ljava/io/File;)Z"),
         (void *) new_createDirectory0, (void **) (&orig_createDirectory0));
    Hook(env, clazz, BB_CORE_STR("setLastModifiedTime0"), BB_CORE_STR("(Ljava/io/File;J)Z"),
         (void *) new_setLastModifiedTime0, (void **) (&orig_setLastModifiedTime0));
    Hook(env, clazz, BB_CORE_STR("setReadOnly0"), BB_CORE_STR("(Ljava/io/File;)Z"),
         (void *) new_setReadOnly0, (void **) (&orig_setReadOnly0));
    Hook(env, clazz, BB_CORE_STR("getSpace0"), BB_CORE_STR("(Ljava/io/File;I)J"),
         (void *) new_getSpace0, (void **) (&orig_getSpace0));
    env->DeleteLocalRef(clazz);
}
