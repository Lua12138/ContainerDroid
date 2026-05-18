//
// Created by canyie on 2020/2/9.
//

#ifndef PINE_LOG_H
#define PINE_LOG_H

#include <android/log.h>
#include <cstdlib>

#ifndef PINE_LOGCAT_ENABLED
#define PINE_LOGCAT_ENABLED 1
#endif

#if PINE_LOGCAT_ENABLED
#include "../pine_config.h"
#endif

#define LOG_TAG "Pine"

#if PINE_LOGCAT_ENABLED
#define PINE_LOG_IF_ENABLED(priority, ...) \
do { \
    if (::pine::PineConfig::debug) { \
        __android_log_print(priority, LOG_TAG, __VA_ARGS__); \
    } \
} while (false)

#define LOGV(...) PINE_LOG_IF_ENABLED(ANDROID_LOG_VERBOSE, __VA_ARGS__)
#define LOGD(...) PINE_LOG_IF_ENABLED(ANDROID_LOG_DEBUG, __VA_ARGS__)
#define LOGI(...) PINE_LOG_IF_ENABLED(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGW(...) PINE_LOG_IF_ENABLED(ANDROID_LOG_WARN, __VA_ARGS__)
#define LOGE(...) PINE_LOG_IF_ENABLED(ANDROID_LOG_ERROR, __VA_ARGS__)
#define LOGF(...) __android_log_print(ANDROID_LOG_FATAL, LOG_TAG, __VA_ARGS__)
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
    LOGF("Aborting..."); \
    abort(); \
} while (false)

#define CHECK(cond, ...) \
do { \
    if (UNLIKELY(!(cond))) {\
        LOGF("%s#%d: Check failed: %s", __FILE__, __LINE__, #cond);\
        FATAL(__VA_ARGS__); \
    }\
} while(false)

#define CHECK_EQ(a, b, ...) CHECK((a) == (b), __VA_ARGS__)


#endif //PINE_LOG_H
