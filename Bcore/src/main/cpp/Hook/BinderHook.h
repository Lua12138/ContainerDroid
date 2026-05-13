//
// Created by Milk on 4/25/21.
//

#ifndef BLACKBOX_BINDERHOOK_H
#define BLACKBOX_BINDERHOOK_H


#include "BaseHook.h"

class BinderHook : public BaseHook{
public:
    static void init(JNIEnv *env);
    static void configureBinderMonitor(bool recordNative, bool recordIoctl);
};

#endif //BLACKBOX_BINDERHOOK_H
