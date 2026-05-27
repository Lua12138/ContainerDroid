#include <android/log.h>
#include "Utils/XorString.h"

#define TAG BB_CORE_STR("NativeCore")

#ifndef BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED
#define BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED 1
#endif

#if BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED
#define log_print_error(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define log_print_debug(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#else
#define log_print_error(...) ((void) 0)
#define log_print_debug(...) ((void) 0)
#endif

#define ALOGE(...) log_print_error(__VA_ARGS__)
#define ALOGD(...) log_print_debug(__VA_ARGS__)

#ifndef SPEED_LOG_H
#define SPEED_LOG_H 1

#endif
