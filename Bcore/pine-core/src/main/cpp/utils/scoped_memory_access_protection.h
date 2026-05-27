//
// Created by canyie on 2020/5/26.
//

#ifndef PINE_SCOPED_MEMORY_ACCESS_PROTECTION_H
#define PINE_SCOPED_MEMORY_ACCESS_PROTECTION_H

#include <cstdint>
#include <signal.h>
#include "macros.h"
#include "log.h"
#include "memory.h"

namespace pine {
    class ScopedMemoryAccessProtection {
    public:
#if defined(__aarch64__) || defined(__arm__)
        ScopedMemoryAccessProtection(void* addr, size_t size, uint32_t max_retries = 2);

        ~ScopedMemoryAccessProtection();
#else
        ScopedMemoryAccessProtection(void* addr, size_t size, uint32_t max_retries = 2) {
        }

        ~ScopedMemoryAccessProtection() {
        }
#endif
    private:
#if defined(__aarch64__) || defined(__arm__)
        static void HandleSignal(int signal, siginfo_t* info, void* reserved);

        uintptr_t addr;
        size_t size;
        uint32_t max_retries;
        bool installed = false;
        struct sigaction def;
#endif
        DISALLOW_COPY_AND_ASSIGN(ScopedMemoryAccessProtection);
    };

}

#endif //PINE_SCOPED_MEMORY_ACCESS_PROTECTION_H
