//
// Created by canyie on 2020/2/9.
//

#ifndef PINE_LOG_H
#define PINE_LOG_H

#include <android/log.h>
#include <cstdarg>
#include <cstdlib>
#include "xor_string.h"

#ifndef PINE_LOGCAT_ENABLED
#define PINE_LOGCAT_ENABLED 1
#endif

#if PINE_LOGCAT_ENABLED
#include "../pine_config.h"
#endif

#define LOG_TAG PINE_STR("Pine")

#if PINE_LOGCAT_ENABLED
namespace pine {
inline void logPrint(int priority, const char *format, ...) {
    va_list args;
    va_start(args, format);
    __android_log_vprint(priority, LOG_TAG, format, args);
    va_end(args);
}
}

#define PINE_LOG_IF_ENABLED(priority, ...) \
do { \
    if (::pine::PineConfig::debug) { \
        ::pine::logPrint(priority, __VA_ARGS__); \
    } \
} while (false)

#define LOGV(...) PINE_LOG_IF_ENABLED(ANDROID_LOG_VERBOSE, __VA_ARGS__)
#define LOGD(...) PINE_LOG_IF_ENABLED(ANDROID_LOG_DEBUG, __VA_ARGS__)
#define LOGI(...) PINE_LOG_IF_ENABLED(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGW(...) PINE_LOG_IF_ENABLED(ANDROID_LOG_WARN, __VA_ARGS__)
#define LOGE(...) PINE_LOG_IF_ENABLED(ANDROID_LOG_ERROR, __VA_ARGS__)
#define LOGF(...) ::pine::logPrint(ANDROID_LOG_FATAL, __VA_ARGS__)
#else
#define PINE_LOG_IF_ENABLED(priority, ...) ((void) 0)
#define LOGV(...) ((void) 0)
#define LOGD(...) ((void) 0)
#define LOGI(...) ((void) 0)
#define LOGW(...) ((void) 0)
#define LOGE(...) ((void) 0)
#define LOGF(...) ((void) 0)
#endif

#define FATAL(...) \
do {\
    LOGF(__VA_ARGS__); \
    LOGF(PINE_STR("Aborting...")); \
    abort(); \
} while (false)

#define CHECK(cond, ...) \
do { \
    if (UNLIKELY(!(cond))) {\
        LOGF(PINE_STR("Check failed"));\
        FATAL(__VA_ARGS__); \
    }\
} while(false)

#define CHECK_EQ(a, b, ...) CHECK((a) == (b), __VA_ARGS__)


#endif //PINE_LOG_H
