//
// Created by Milk on 4/10/21.
//

#include "IO.h"
#include "Log.h"

#include <limits.h>

jmethodID getAbsolutePathMethodId;

list<IO::RelocateInfo> relocate_rule;

char *replace(const char *str, const char *src, const char *dst) {
    const char *pos = str;
    int count = 0;
    while ((pos = strstr(pos, src))) {
        count++;
        pos += strlen(src);
    }

    size_t result_len = strlen(str) + (strlen(dst) - strlen(src)) * count + 1;
    char *result = (char *) malloc(result_len);
    memset(result, 0, strlen(result));

    const char *left = str;
    const char *right = nullptr;

    while ((right = strstr(left, src))) {
        strncat(result, left, right - left);
        strcat(result, dst);
        right += strlen(src);
        left = right;
    }
    strcat(result, left);
    return result;
}

const char *IO::redirectPath(const char *__path) {
    list<IO::RelocateInfo>::iterator iterator;
    for (iterator = relocate_rule.begin(); iterator != relocate_rule.end(); ++iterator) {
        IO::RelocateInfo info = *iterator;
        if (strstr(__path, info.targetPath) && !strstr(__path, "/blackbox/")) {
            char *ret = replace(__path, info.targetPath, info.relocatePath);
            // ALOGD("redirectPath %s  => %s", __path, ret);
            return ret;
        }
    }
    return __path;
}

const char *reverseRedirectPathWithAlias(const char *__path, const char *relocatePath, const char *targetPath) {
    if (__path == nullptr || relocatePath == nullptr || targetPath == nullptr) {
        return nullptr;
    }
    if (strstr(__path, relocatePath)) {
        return replace(__path, relocatePath, targetPath);
    }

    static const char *kDataUserPrefix = "/data/user/0/";
    static const char *kDataDataPrefix = "/data/data/";
    const char *fromPrefix = nullptr;
    const char *toPrefix = nullptr;
    if (strncmp(relocatePath, kDataUserPrefix, strlen(kDataUserPrefix)) == 0) {
        fromPrefix = kDataUserPrefix;
        toPrefix = kDataDataPrefix;
    } else if (strncmp(relocatePath, kDataDataPrefix, strlen(kDataDataPrefix)) == 0) {
        fromPrefix = kDataDataPrefix;
        toPrefix = kDataUserPrefix;
    }
    if (fromPrefix == nullptr || toPrefix == nullptr) {
        return nullptr;
    }

    char alias[PATH_MAX];
    int written = snprintf(alias, sizeof(alias), "%s%s", toPrefix, relocatePath + strlen(fromPrefix));
    if (written <= 0 || static_cast<size_t>(written) >= sizeof(alias)) {
        return nullptr;
    }
    if (strstr(__path, alias)) {
        return replace(__path, alias, targetPath);
    }
    return nullptr;
}

const char *IO::reverseRedirectPath(const char *__path) {
    if (__path == nullptr) {
        return __path;
    }
    list<IO::RelocateInfo>::iterator iterator;
    for (iterator = relocate_rule.begin(); iterator != relocate_rule.end(); ++iterator) {
        IO::RelocateInfo info = *iterator;
        if (info.relocatePath == nullptr || info.targetPath == nullptr) {
            continue;
        }
        const char *reversed = reverseRedirectPathWithAlias(__path, info.relocatePath, info.targetPath);
        if (reversed != nullptr) {
            return reversed;
        }
    }
    return __path;
}

jstring IO::reverseRedirectPath(JNIEnv *env, jstring path) {
    if (env == nullptr || path == nullptr) {
        return path;
    }
    const char *pathC = env->GetStringUTFChars(path, JNI_FALSE);
    if (pathC == nullptr) {
        return path;
    }
    const char *reversed = reverseRedirectPath(pathC);
    jstring result = path;
    if (reversed != pathC) {
        result = env->NewStringUTF(reversed);
        free((void *) reversed);
    }
    env->ReleaseStringUTFChars(path, pathC);
    return result;
}

jstring IO::redirectPath(JNIEnv *env, jstring path) {
//    const char * pathC = env->GetStringUTFChars(path, JNI_FALSE);
//    const char *redirect = redirectPath(pathC);
//    env->ReleaseStringUTFChars(path, pathC);
//    return env->NewStringUTF(redirect);
    return BoxCore::redirectPathString(env, path);
}

jobject IO::redirectPath(JNIEnv *env, jobject path) {
//    auto pathStr =
//            reinterpret_cast<jstring>(env->CallObjectMethod(path, getAbsolutePathMethodId));
//    jstring redirect = redirectPath(env, pathStr);
//    jobject file = env->NewObject(fileClazz, fileNew, redirect);
//    env->DeleteLocalRef(pathStr);
//    env->DeleteLocalRef(redirect);
    return BoxCore::redirectPathFile(env, path);
}

void IO::addRule(const char *targetPath, const char *relocatePath) {
    IO::RelocateInfo info{};
    info.targetPath = targetPath;
    info.relocatePath = relocatePath;
    relocate_rule.push_back(info);
}

void IO::init(JNIEnv *env) {
    jclass tmpFile = env->FindClass("java/io/File");
    getAbsolutePathMethodId = env->GetMethodID(tmpFile, "getAbsolutePath", "()Ljava/lang/String;");
}
