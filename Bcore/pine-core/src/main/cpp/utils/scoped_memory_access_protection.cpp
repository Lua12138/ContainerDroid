//
// Created by canyie on 2020/5/26.
//
#include "scoped_memory_access_protection.h"

using namespace pine;

#if defined(__aarch64__) || defined(__arm__)

static __thread ScopedMemoryAccessProtection* current_protection = nullptr;

ScopedMemoryAccessProtection::ScopedMemoryAccessProtection(void* addr, size_t size, uint32_t max_retries) :
        addr(reinterpret_cast<uintptr_t>(addr)), size(size), max_retries(max_retries) {
    if (current_protection != nullptr) {
        return;
    }
    current_protection = this;
    struct sigaction my;
    my.sa_sigaction = HandleSignal;
    my.sa_flags = SA_SIGINFO;
    sigaction(SIGSEGV, &my, &def);
    installed = true;
}

ScopedMemoryAccessProtection::~ScopedMemoryAccessProtection() {
    if (installed) {
        sigaction(SIGSEGV, &def, nullptr);
        current_protection = nullptr;
    }
}

void ScopedMemoryAccessProtection::HandleSignal(int signal, siginfo_t* info, void* reserved) {
    if (signal != SIGSEGV || current_protection == nullptr) {
        return;
    }
    ucontext_t* context = static_cast<ucontext_t*>(reserved);
    uintptr_t fault_addr = context->uc_mcontext.fault_address;

    if (LIKELY(info->si_code == SEGV_ACCERR)) {
        if (LIKELY(fault_addr >= current_protection->addr
                   && fault_addr <= (current_protection->addr + current_protection->size))) {
            if (LIKELY(current_protection->max_retries-- > 0)) {
                LOGW("Segmentation fault when trying access %p, unprotect it and try again", (void*) fault_addr);
                if (LIKELY(Memory::Unprotect(reinterpret_cast<void*>(fault_addr))))
                    return;
                LOGE("Failed to unprotect fault address…");
            } else {
                LOGE("Retried too many times to access %p", (void*) fault_addr);
            }
        }
    }

    if (current_protection->def.sa_sigaction == nullptr) {
        FATAL("No default signal handler to dispatch SIGSEGV (fault addr %p)", (void*) fault_addr);
    } else {
        current_protection->def.sa_sigaction(signal, info, reserved);
    }
}
#endif
