#ifndef BLACKBOX_NATIVEPROPERTY_H
#define BLACKBOX_NATIVEPROPERTY_H

#include <cstring>
#include <sys/system_properties.h>

namespace blackbox {
namespace native_property {

inline bool isTruthyOneTrueYes(const char *value) {
    return value != nullptr
           && (std::strcmp(value, "1") == 0
               || std::strcmp(value, "true") == 0
               || std::strcmp(value, "TRUE") == 0
               || std::strcmp(value, "yes") == 0);
}

inline bool isTruthy(const char *value) {
    return isTruthyOneTrueYes(value)
           || (value != nullptr
               && (std::strcmp(value, "YES") == 0
                   || std::strcmp(value, "on") == 0
                   || std::strcmp(value, "ON") == 0));
}

inline bool isTruthyJniDiagnostic(const char *value) {
    return isTruthyOneTrueYes(value)
           || (value != nullptr && std::strcmp(value, "on") == 0);
}

inline bool isTruthySeccompWatchdog(const char *value) {
    return isTruthyOneTrueYes(value)
           || (value != nullptr && std::strcmp(value, "YES") == 0);
}

inline bool isFalsy(const char *value) {
    return value != nullptr
           && (std::strcmp(value, "0") == 0
               || std::strcmp(value, "false") == 0
               || std::strcmp(value, "FALSE") == 0
               || std::strcmp(value, "no") == 0
               || std::strcmp(value, "NO") == 0
               || std::strcmp(value, "off") == 0
               || std::strcmp(value, "OFF") == 0);
}

inline bool getBool(const char *key) {
    char value[PROP_VALUE_MAX] = {};
    return key != nullptr
           && __system_property_get(key, value) > 0
           && isTruthy(value);
}

inline bool getBoolJniDiagnostic(const char *key) {
    char value[PROP_VALUE_MAX] = {};
    return key != nullptr
           && __system_property_get(key, value) > 0
           && isTruthyJniDiagnostic(value);
}

inline bool getBoolDefaultTrue(const char *key) {
    char value[PROP_VALUE_MAX] = {};
    if (key == nullptr || __system_property_get(key, value) <= 0) {
        return true;
    }
    return !isFalsy(value);
}

}  // namespace native_property
}  // namespace blackbox

#endif  // BLACKBOX_NATIVEPROPERTY_H
