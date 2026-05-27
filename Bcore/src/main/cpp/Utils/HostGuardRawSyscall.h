#pragma once

#include <stddef.h>
#include <stdint.h>

namespace bbguard {

constexpr long kAtFdcwd = -100;
constexpr long kProtReadWrite = 0x3;
constexpr long kMapPrivateAnonymous = 0x22;

#if defined(__aarch64__)
constexpr long kSysOpenAt = 56;
constexpr long kSysClose = 57;
constexpr long kSysRead = 63;
constexpr long kSysLseek = 62;
constexpr long kSysNanosleep = 101;
constexpr long kSysExitGroup = 94;
constexpr long kSysMmap = 222;
constexpr long kSysMunmap = 215;
constexpr long kSysGetPid = 172;
#elif defined(__arm__)
constexpr long kSysRead = 3;
constexpr long kSysClose = 6;
constexpr long kSysLseek = 19;
constexpr long kSysNanosleep = 162;
constexpr long kSysExitGroup = 248;
constexpr long kSysMmap = 192;
constexpr long kSysMunmap = 91;
constexpr long kSysOpenAt = 322;
constexpr long kSysGetPid = 20;
#else
#error "host guard supports armeabi-v7a and arm64-v8a only"
#endif

struct KernelTimespec {
    long tv_sec;
    long tv_nsec;
};

static inline long rawSyscall6(long number, long arg0, long arg1, long arg2,
                               long arg3, long arg4, long arg5) {
#if defined(__aarch64__)
    register long x0 __asm__("x0") = arg0;
    register long x1 __asm__("x1") = arg1;
    register long x2 __asm__("x2") = arg2;
    register long x3 __asm__("x3") = arg3;
    register long x4 __asm__("x4") = arg4;
    register long x5 __asm__("x5") = arg5;
    register long x8 __asm__("x8") = number;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8)
                     : "memory", "cc");
    return x0;
#elif defined(__arm__)
    register long r0 __asm__("r0") = arg0;
    register long r1 __asm__("r1") = arg1;
    register long r2 __asm__("r2") = arg2;
    register long r3 __asm__("r3") = arg3;
    register long r4 __asm__("r4") = arg4;
    register long r5 __asm__("r5") = arg5;
    register long ip __asm__("ip") = number;
    __asm__ volatile("push {r7}\n"
                     "mov r7, ip\n"
                     "svc #0\n"
                     "pop {r7}\n"
                     : "+r"(r0)
                     : "r"(r1), "r"(r2), "r"(r3), "r"(r4), "r"(r5), "r"(ip)
                     : "memory", "cc");
    return r0;
#endif
}

static inline bool rawIsError(long value) {
    return value < 0 && value > -4096;
}

static inline long rawOpenAt(const char *path, int flags) {
    return rawSyscall6(kSysOpenAt, kAtFdcwd, reinterpret_cast<long>(path), flags, 0, 0, 0);
}

static inline long rawGetPid() {
    return rawSyscall6(kSysGetPid, 0, 0, 0, 0, 0, 0);
}

static inline long rawRead(int fd, void *buffer, size_t size) {
    return rawSyscall6(kSysRead, fd, reinterpret_cast<long>(buffer), static_cast<long>(size), 0, 0, 0);
}

static inline long rawClose(int fd) {
    return rawSyscall6(kSysClose, fd, 0, 0, 0, 0, 0);
}

static inline long rawLseek(int fd, long offset, int whence) {
    return rawSyscall6(kSysLseek, fd, offset, whence, 0, 0, 0);
}

static inline void *rawMmap(size_t size) {
    long result = rawSyscall6(kSysMmap, 0, static_cast<long>(size), kProtReadWrite,
                              kMapPrivateAnonymous, -1, 0);
    return rawIsError(result) ? nullptr : reinterpret_cast<void *>(result);
}

static inline void rawMunmap(void *address, size_t size) {
    if (address != nullptr && size != 0) {
        rawSyscall6(kSysMunmap, reinterpret_cast<long>(address), static_cast<long>(size), 0, 0, 0, 0);
    }
}

static inline void rawNanosleepSeconds(long seconds) {
    KernelTimespec request = {seconds, 0};
    rawSyscall6(kSysNanosleep, reinterpret_cast<long>(&request), 0, 0, 0, 0, 0);
}

__attribute__((noreturn)) static inline void rawExitGroup(int code) {
    rawSyscall6(kSysExitGroup, code, 0, 0, 0, 0, 0);
    volatile int *crash = reinterpret_cast<volatile int *>(0);
    *crash = 0;
    while (true) {
    }
}

} // namespace bbguard
