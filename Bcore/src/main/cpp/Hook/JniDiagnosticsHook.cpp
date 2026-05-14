#include "JniDiagnosticsHook.h"
#include "Log.h"

#include <cinttypes>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <sstream>
#include <string>
#include <sys/system_properties.h>
#include <vector>

namespace {

const JNINativeInterface *gOriginalFunctions = nullptr;
JNINativeInterface gHookedFunctions = {};
thread_local bool gInHook = false;

struct RecursionGuard {
    RecursionGuard() {
        gInHook = true;
    }

    ~RecursionGuard() {
        gInHook = false;
    }
};

bool clearPendingException(JNIEnv *env) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return true;
    }
    return false;
}

bool isDetailedFieldDiagnosticsEnabled() {
    char value[PROP_VALUE_MAX] = {};
    int length = __system_property_get("debug.blackbox.jni_field_details", value);
    if (length <= 0) {
        return false;
    }
    return std::strcmp(value, "1") == 0
           || std::strcmp(value, "true") == 0
           || std::strcmp(value, "TRUE") == 0
           || std::strcmp(value, "yes") == 0
           || std::strcmp(value, "on") == 0;
}

bool isFieldDiagnosticsEnabled() {
    char value[PROP_VALUE_MAX] = {};
    int length = __system_property_get("debug.blackbox.jni_field_diag", value);
    if (length <= 0) {
        return false;
    }
    return std::strcmp(value, "1") == 0
           || std::strcmp(value, "true") == 0
           || std::strcmp(value, "TRUE") == 0
           || std::strcmp(value, "yes") == 0
           || std::strcmp(value, "on") == 0;
}

std::string jstringToString(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return "null";
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr || clearPendingException(env)) {
        return "unavailable";
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string describeClass(JNIEnv *env, jclass clazz) {
    if (clazz == nullptr) {
        return "null";
    }

    jclass classClass = env->FindClass("java/lang/Class");
    if (classClass == nullptr || clearPendingException(env)) {
        return "unavailable";
    }

    jmethodID getName = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
    if (getName == nullptr || clearPendingException(env)) {
        env->DeleteLocalRef(classClass);
        return "unavailable";
    }

    jstring name = static_cast<jstring>(env->CallObjectMethod(clazz, getName));
    if (name == nullptr || clearPendingException(env)) {
        env->DeleteLocalRef(classClass);
        return "unavailable";
    }

    std::string result = jstringToString(env, name);
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(classClass);
    return result;
}

std::string formatObjectIdentity(JNIEnv *env, jobject object) {
    if (object == nullptr) {
        return "null";
    }

    jclass objectClass = env->GetObjectClass(object);
    if (objectClass == nullptr || clearPendingException(env)) {
        return "unavailable";
    }
    std::string className = describeClass(env, objectClass);
    env->DeleteLocalRef(objectClass);

    jclass systemClass = env->FindClass("java/lang/System");
    if (systemClass == nullptr || clearPendingException(env)) {
        return className;
    }
    jmethodID identityHashCode = env->GetStaticMethodID(systemClass,
                                                        "identityHashCode",
                                                        "(Ljava/lang/Object;)I");
    if (identityHashCode == nullptr || clearPendingException(env)) {
        env->DeleteLocalRef(systemClass);
        return className;
    }
    jint hash = env->CallStaticIntMethod(systemClass, identityHashCode, object);
    if (clearPendingException(env)) {
        env->DeleteLocalRef(systemClass);
        return className;
    }
    env->DeleteLocalRef(systemClass);

    char suffix[16] = {};
    std::snprintf(suffix, sizeof(suffix), "@%08x", static_cast<unsigned int>(hash));
    return className + suffix;
}

std::string describeClassLoader(JNIEnv *env, jclass clazz) {
    if (clazz == nullptr) {
        return "null";
    }

    jclass classClass = env->FindClass("java/lang/Class");
    if (classClass == nullptr || clearPendingException(env)) {
        return "unavailable";
    }
    jmethodID getClassLoader = env->GetMethodID(classClass,
                                                "getClassLoader",
                                                "()Ljava/lang/ClassLoader;");
    if (getClassLoader == nullptr || clearPendingException(env)) {
        env->DeleteLocalRef(classClass);
        return "unavailable";
    }

    jobject loader = env->CallObjectMethod(clazz, getClassLoader);
    if (clearPendingException(env)) {
        env->DeleteLocalRef(classClass);
        return "unavailable";
    }
    if (loader == nullptr) {
        env->DeleteLocalRef(classClass);
        return "bootstrap";
    }

    std::string result = formatObjectIdentity(env, loader);
    env->DeleteLocalRef(loader);
    env->DeleteLocalRef(classClass);
    return result;
}

std::string joinEntries(const std::vector<std::string> &entries) {
    std::ostringstream out;
    out << '[';
    for (size_t i = 0; i < entries.size(); ++i) {
        if (i != 0) {
            out << ", ";
        }
        out << entries[i];
    }
    out << ']';
    return out.str();
}

std::string describeField(JNIEnv *env, jobject field, jmethodID getName,
                          jmethodID getType, jmethodID getModifiers) {
    jstring fieldNameValue = static_cast<jstring>(env->CallObjectMethod(field, getName));
    if (fieldNameValue == nullptr || clearPendingException(env)) {
        return "unavailable";
    }
    std::string fieldName = jstringToString(env, fieldNameValue);
    env->DeleteLocalRef(fieldNameValue);

    jobject type = env->CallObjectMethod(field, getType);
    if (type == nullptr || clearPendingException(env)) {
        return fieldName + ":unavailable";
    }
    std::string typeName = describeClass(env, static_cast<jclass>(type));
    env->DeleteLocalRef(type);

    jint modifiers = env->CallIntMethod(field, getModifiers);
    if (clearPendingException(env)) {
        return fieldName + ":" + typeName + " static=unavailable";
    }
    const bool isStatic = (modifiers & 0x0008) != 0;

    std::ostringstream out;
    out << fieldName << ':' << typeName << " static=" << (isStatic ? "true" : "false");
    return out.str();
}

std::string describeDeclaredFields(JNIEnv *env, jclass clazz, const char *requestedName) {
    if (clazz == nullptr) {
        return "null";
    }

    jclass classClass = env->FindClass("java/lang/Class");
    if (classClass == nullptr || clearPendingException(env)) {
        return "unavailable";
    }
    jmethodID getDeclaredFields = env->GetMethodID(classClass,
                                                   "getDeclaredFields",
                                                   "()[Ljava/lang/reflect/Field;");
    if (getDeclaredFields == nullptr || clearPendingException(env)) {
        env->DeleteLocalRef(classClass);
        return "unavailable";
    }
    jobjectArray fields = static_cast<jobjectArray>(env->CallObjectMethod(clazz, getDeclaredFields));
    if (fields == nullptr || clearPendingException(env)) {
        env->DeleteLocalRef(classClass);
        return "unavailable";
    }

    jclass fieldClass = env->FindClass("java/lang/reflect/Field");
    if (fieldClass == nullptr || clearPendingException(env)) {
        env->DeleteLocalRef(fields);
        env->DeleteLocalRef(classClass);
        return "unavailable";
    }
    jmethodID fieldGetName = env->GetMethodID(fieldClass, "getName", "()Ljava/lang/String;");
    jmethodID fieldGetType = env->GetMethodID(fieldClass, "getType", "()Ljava/lang/Class;");
    jmethodID fieldGetModifiers = env->GetMethodID(fieldClass, "getModifiers", "()I");
    if (fieldGetName == nullptr || fieldGetType == nullptr || fieldGetModifiers == nullptr
        || clearPendingException(env)) {
        env->DeleteLocalRef(fieldClass);
        env->DeleteLocalRef(fields);
        env->DeleteLocalRef(classClass);
        return "unavailable";
    }

    const jsize count = env->GetArrayLength(fields);
    if (clearPendingException(env)) {
        env->DeleteLocalRef(fieldClass);
        env->DeleteLocalRef(fields);
        env->DeleteLocalRef(classClass);
        return "unavailable";
    }

    constexpr size_t kMaxEntries = 12;
    std::vector<std::string> matching;
    std::vector<std::string> sample;
    size_t matchingTotal = 0;
    for (jsize i = 0; i < count; ++i) {
        jobject field = env->GetObjectArrayElement(fields, i);
        if (field == nullptr || clearPendingException(env)) {
            continue;
        }

        jstring fieldNameValue = static_cast<jstring>(env->CallObjectMethod(field, fieldGetName));
        if (fieldNameValue == nullptr || clearPendingException(env)) {
            env->DeleteLocalRef(field);
            continue;
        }
        std::string fieldName = jstringToString(env, fieldNameValue);
        env->DeleteLocalRef(fieldNameValue);

        const bool nameMatches = requestedName != nullptr && fieldName == requestedName;
        if (nameMatches) {
            ++matchingTotal;
        }
        if ((nameMatches && matching.size() < kMaxEntries)
            || (matching.empty() && sample.size() < kMaxEntries)) {
            std::string description = describeField(env, field, fieldGetName,
                                                    fieldGetType, fieldGetModifiers);
            if (nameMatches) {
                matching.push_back(description);
            } else if (sample.size() < kMaxEntries) {
                sample.push_back(description);
            }
        }
        env->DeleteLocalRef(field);
    }

    env->DeleteLocalRef(fieldClass);
    env->DeleteLocalRef(fields);
    env->DeleteLocalRef(classClass);

    std::ostringstream out;
    out << "total=" << count;
    if (requestedName != nullptr) {
        out << " requested=" << requestedName << " matches=" << matchingTotal;
    }
    if (!matching.empty()) {
        out << " fields=" << joinEntries(matching);
    } else {
        out << " sample=" << joinEntries(sample);
    }
    std::string result = out.str();
    constexpr size_t kMaxLogChars = 900;
    if (result.size() > kMaxLogChars) {
        result.resize(kMaxLogChars);
        result += "...";
    }
    return result;
}

void describeCaller(void *returnAddress, const char **library, uintptr_t *offset) {
    *library = "unknown";
    *offset = 0;

    Dl_info info = {};
    if (returnAddress != nullptr && dladdr(returnAddress, &info) != 0 && info.dli_fname != nullptr) {
        *library = info.dli_fname;
        if (info.dli_fbase != nullptr) {
            *offset = reinterpret_cast<uintptr_t>(returnAddress)
                      - reinterpret_cast<uintptr_t>(info.dli_fbase);
        }
    }
}

void logFailedLookup(JNIEnv *env, const char *api, jclass clazz, const char *name,
                     const char *sig, void *returnAddress) {
    jthrowable pending = env->ExceptionOccurred();
    env->ExceptionClear();

    std::string className = describeClass(env, clazz);
    const bool detailed = isDetailedFieldDiagnosticsEnabled();
    std::string classLoader = detailed ? describeClassLoader(env, clazz) : "disabled";
    std::string declaredFields = detailed ? describeDeclaredFields(env, clazz, name) : "disabled";
    const char *library = nullptr;
    uintptr_t offset = 0;
    describeCaller(returnAddress, &library, &offset);

    ALOGD("jni field lookup failed api=%s class=%s name=%s sig=%s callerLib=%s callerOffset=0x%" PRIxPTR " classLoader=%s declaredFields=%s",
          api,
          className.c_str(),
          name == nullptr ? "null" : name,
          sig == nullptr ? "null" : sig,
          library == nullptr ? "unknown" : library,
          offset,
          classLoader.c_str(),
          declaredFields.c_str());

    if (pending != nullptr) {
        env->Throw(pending);
        env->DeleteLocalRef(pending);
    }
}

jfieldID hookedGetFieldID(JNIEnv *env, jclass clazz, const char *name, const char *sig) {
    const JNINativeInterface *functions = gOriginalFunctions != nullptr ? gOriginalFunctions : env->functions;
    if (gInHook || gOriginalFunctions == nullptr) {
        return functions->GetFieldID(env, clazz, name, sig);
    }

    RecursionGuard guard;
    jfieldID result = functions->GetFieldID(env, clazz, name, sig);
    if (result == nullptr && env->ExceptionCheck()) {
        logFailedLookup(env, "GetFieldID", clazz, name, sig, __builtin_return_address(0));
    }
    return result;
}

jfieldID hookedGetStaticFieldID(JNIEnv *env, jclass clazz, const char *name, const char *sig) {
    const JNINativeInterface *functions = gOriginalFunctions != nullptr ? gOriginalFunctions : env->functions;
    if (gInHook || gOriginalFunctions == nullptr) {
        return functions->GetStaticFieldID(env, clazz, name, sig);
    }

    RecursionGuard guard;
    jfieldID result = functions->GetStaticFieldID(env, clazz, name, sig);
    if (result == nullptr && env->ExceptionCheck()) {
        logFailedLookup(env, "GetStaticFieldID", clazz, name, sig, __builtin_return_address(0));
    }
    return result;
}

} // namespace

void JniDiagnosticsHook::init(JNIEnv *env) {
    if (env == nullptr || env->functions == &gHookedFunctions) {
        return;
    }
    if (!isFieldDiagnosticsEnabled()) {
        ALOGD("JNI diagnostics disabled by debug property");
        return;
    }

    if (gOriginalFunctions == nullptr) {
        gOriginalFunctions = env->functions;
        std::memcpy(&gHookedFunctions, gOriginalFunctions, sizeof(gHookedFunctions));
        gHookedFunctions.GetFieldID = hookedGetFieldID;
        gHookedFunctions.GetStaticFieldID = hookedGetStaticFieldID;
    }

    env->functions = &gHookedFunctions;
    ALOGD("JNI diagnostics function table installed");
}
