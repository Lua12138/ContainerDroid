//
// Created by Codex on 5/17/26.
//

#ifndef BLACKBOX_JNIDIAGNOSTICSHOOK_H
#define BLACKBOX_JNIDIAGNOSTICSHOOK_H

#include "BaseHook.h"

class JniDiagnosticsHook : public BaseHook {
public:
    static void init(JNIEnv *env);
};

#endif //BLACKBOX_JNIDIAGNOSTICSHOOK_H
