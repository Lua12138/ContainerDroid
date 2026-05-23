#include "RawSyscallTerminationProbe.h"
#include "IO.h"

#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <cinttypes>
#include <cstdlib>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/ucontext.h>
#include <unistd.h>

extern "C" int blackbox_open_virtual_proc_fd_for_raw_syscall(int dirfd,
                                                             const char *pathname,
                                                             int flags,
                                                             void *caller);

namespace blackbox {
namespace rawsyscall {
namespace {

static const char *kTag = "BlackBoxRawSyscall";
static constexpr size_t kMaxPatches = 8192;
static constexpr size_t kMaxPath = 192;
static constexpr uint32_t kHotPassthroughRestoreThreshold = 64 * 1024;

#ifndef BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED
#define BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED 1
#endif

#if BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED
#define RAW_SYSCALL_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, kTag, __VA_ARGS__)
#else
#define RAW_SYSCALL_LOGD(...) ((void) 0)
#endif

struct PatchEntry {
    uintptr_t address;
    uintptr_t map_start;
    uintptr_t map_end;
    uintptr_t map_offset;
    uint32_t original;
    int prot;
    uint8_t size;
    bool thumb;
    bool active;
    bool patched;
    bool saw_redirectable_syscall;
    uint32_t non_termination_count;
    char path[kMaxPath];
};

struct RawSyscallRedirectTelemetry {
    bool redirectable;
    bool redirected;
    char original[kMaxPath];
    char redirected_path[kMaxPath];
};

static std::atomic<bool> gInstalled(false);
static std::atomic<bool> gBlockTerminationSyscalls(false);
static volatile sig_atomic_t gPatchCount = 0;
static PatchEntry gPatches[kMaxPatches];
static pthread_mutex_t gPatchRegistryLock = PTHREAD_MUTEX_INITIALIZER;
static struct sigaction gPreviousTrapAction = {};
static bool gHasPreviousTrapAction = false;
static pid_t gRootPid = -1;
static constexpr size_t kTrapAltStackSize = 64 * 1024;
static __thread bool gThreadTrapAltStackInstalled = false;
static __thread void *gThreadTrapAltStack = nullptr;
#if defined(__arm__)
static __thread RawSyscallRedirectTelemetry gLastRedirectTelemetry;
#endif

static bool patchBytes(uintptr_t address, const void *trap, size_t size, int restore_prot);
static uintptr_t patchedInstructionFileOffset(const PatchEntry &entry);

class ScopedPatchRegistryLock {
public:
    ScopedPatchRegistryLock() {
        pthread_mutex_lock(&gPatchRegistryLock);
    }

    ~ScopedPatchRegistryLock() {
        pthread_mutex_unlock(&gPatchRegistryLock);
    }

    ScopedPatchRegistryLock(const ScopedPatchRegistryLock &) = delete;
    ScopedPatchRegistryLock &operator=(const ScopedPatchRegistryLock &) = delete;
};

static bool isTerminationSignal(int signal) {
    return signal == SIGKILL || signal == SIGTERM || signal == SIGABRT;
}

static bool isProtectedPidTarget(pid_t target) {
    const pid_t self = getpid();
    return target == self
           || (gRootPid > 0 && target == gRootPid)
           || target == 0
           || target == -1
           || target == -self
           || (gRootPid > 0 && target == -gRootPid);
}

#if defined(__arm__)
static bool isTerminationSyscall(int sysno, const mcontext_t &mc) {
    switch (sysno) {
#ifdef __NR_exit
        case __NR_exit:
            return true;
#endif
#ifdef __NR_exit_group
        case __NR_exit_group:
            return true;
#endif
#ifdef __NR_kill
        case __NR_kill:
            return isProtectedPidTarget(static_cast<pid_t>(mc.arm_r0))
                   && isTerminationSignal(static_cast<int>(mc.arm_r1));
#endif
#ifdef __NR_tkill
        case __NR_tkill:
            return isTerminationSignal(static_cast<int>(mc.arm_r1));
#endif
#ifdef __NR_tgkill
        case __NR_tgkill:
            return isProtectedPidTarget(static_cast<pid_t>(mc.arm_r0))
                   && isTerminationSignal(static_cast<int>(mc.arm_r2));
#endif
        default:
            return false;
    }
}

static bool isProcessExitSyscall(int sysno) {
    switch (sysno) {
#ifdef __NR_exit
        case __NR_exit:
            return true;
#endif
#ifdef __NR_exit_group
        case __NR_exit_group:
            return true;
#endif
        default:
            return false;
    }
}

static void resumeBlockedProcessExit(mcontext_t &mc, const PatchEntry *entry) {
    if (mc.arm_lr != 0) {
        mc.arm_pc = static_cast<unsigned long>(mc.arm_lr);
        return;
    }
    if (entry != nullptr) {
        mc.arm_pc = static_cast<unsigned long>(entry->address + entry->size);
    }
}
#endif

static const char *syscallName(int sysno) {
    switch (sysno) {
#ifdef __NR_exit
        case __NR_exit:
            return "exit";
#endif
#ifdef __NR_exit_group
        case __NR_exit_group:
            return "exit_group";
#endif
#ifdef __NR_kill
        case __NR_kill:
            return "kill";
#endif
#ifdef __NR_tkill
        case __NR_tkill:
            return "tkill";
#endif
#ifdef __NR_tgkill
        case __NR_tgkill:
            return "tgkill";
#endif
#ifdef __NR_open
        case __NR_open:
            return "open";
#endif
#ifdef __NR_openat
        case __NR_openat:
            return "openat";
#endif
#ifdef __NR_read
        case __NR_read:
            return "read";
#endif
#ifdef __NR_lseek
        case __NR_lseek:
            return "lseek";
#endif
#ifdef __NR_access
        case __NR_access:
            return "access";
#endif
#ifdef __NR_faccessat
        case __NR_faccessat:
            return "faccessat";
#endif
#ifdef __NR_mkdir
        case __NR_mkdir:
            return "mkdir";
#endif
#ifdef __NR_mkdirat
        case __NR_mkdirat:
            return "mkdirat";
#endif
#ifdef __NR_readlink
        case __NR_readlink:
            return "readlink";
#endif
#ifdef __NR_readlinkat
        case __NR_readlinkat:
            return "readlinkat";
#endif
        default:
            return "unknown";
    }
}

#if defined(__arm__)
static void copyTelemetryPath(char *dst, size_t dst_size, const char *src) {
    if (dst == nullptr || dst_size == 0) {
        return;
    }
    if (src == nullptr) {
        snprintf(dst, dst_size, "null");
        return;
    }
    snprintf(dst, dst_size, "%s", src);
}

static const char *redirectRawSyscallPath(const char *pathname) {
    if (pathname == nullptr || pathname[0] == '\0' || pathname[0] != '/') {
        return pathname;
    }
    return IO::redirectPath(pathname);
}

static void releaseRedirectedRawPath(const char *pathname, const char *redirected_path) {
    if (redirected_path != nullptr && redirected_path != pathname) {
        free(const_cast<char *>(redirected_path));
    }
}

static int openVirtualProcFdForRawSyscall(int sysno, long args[6], uintptr_t caller) {
    const char *pathname = nullptr;
    int flags = 0;
    int dirfd = AT_FDCWD;
    switch (sysno) {
#ifdef __NR_open
        case __NR_open:
            pathname = reinterpret_cast<const char *>(args[0]);
            flags = static_cast<int>(args[1]);
            break;
#endif
#ifdef __NR_openat
        case __NR_openat:
            dirfd = static_cast<int>(args[0]);
            pathname = reinterpret_cast<const char *>(args[1]);
            flags = static_cast<int>(args[2]);
            break;
#endif
        default:
            return -1;
    }

    int fd = blackbox_open_virtual_proc_fd_for_raw_syscall(
            dirfd,
            pathname,
            flags,
            reinterpret_cast<void *>(caller));
    if (fd < 0) {
        return -1;
    }

    gLastRedirectTelemetry.redirectable = true;
    gLastRedirectTelemetry.redirected = true;
    copyTelemetryPath(gLastRedirectTelemetry.original,
                      sizeof(gLastRedirectTelemetry.original),
                      pathname);
    copyTelemetryPath(gLastRedirectTelemetry.redirected_path,
                      sizeof(gLastRedirectTelemetry.redirected_path),
                      "virtual-proc-fd");
    return fd;
}

static long rawKernelSyscall6(long sysno, long arg0, long arg1, long arg2,
                              long arg3, long arg4, long arg5) {
    register long r0 __asm__("r0") = arg0;
    register long r1 __asm__("r1") = arg1;
    register long r2 __asm__("r2") = arg2;
    register long r3 __asm__("r3") = arg3;
    register long r4 __asm__("r4") = arg4;
    register long r5 __asm__("r5") = arg5;
    register long r7 __asm__("r7") = sysno;
    __asm__ volatile("svc #0"
                     : "+r"(r0)
                     : "r"(r1), "r"(r2), "r"(r3), "r"(r4), "r"(r5), "r"(r7)
                     : "memory", "cc");
    return r0;
}

static long emulateRawSyscall(int sysno, const mcontext_t &mc) {
    return rawKernelSyscall6(sysno,
                             static_cast<long>(mc.arm_r0),
                             static_cast<long>(mc.arm_r1),
                             static_cast<long>(mc.arm_r2),
                             static_cast<long>(mc.arm_r3),
                             static_cast<long>(mc.arm_r4),
                             static_cast<long>(mc.arm_r5));
}

static long emulateRedirectableRawSyscall(int sysno, const mcontext_t &mc) {
    gLastRedirectTelemetry = {};

    long args[6] = {
            static_cast<long>(mc.arm_r0),
            static_cast<long>(mc.arm_r1),
            static_cast<long>(mc.arm_r2),
            static_cast<long>(mc.arm_r3),
            static_cast<long>(mc.arm_r4),
            static_cast<long>(mc.arm_r5),
    };

    int virtual_proc_fd = openVirtualProcFdForRawSyscall(sysno, args, static_cast<uintptr_t>(mc.arm_pc));
    if (virtual_proc_fd >= 0) {
        return virtual_proc_fd;
    }

    int path_index = -1;
    switch (sysno) {
#ifdef __NR_open
        case __NR_open:
            path_index = 0;
            break;
#endif
#ifdef __NR_openat
        case __NR_openat:
            path_index = 1;
            break;
#endif
#ifdef __NR_access
        case __NR_access:
            path_index = 0;
            break;
#endif
#ifdef __NR_faccessat
        case __NR_faccessat:
            path_index = 1;
            break;
#endif
#ifdef __NR_mkdir
        case __NR_mkdir:
            path_index = 0;
            break;
#endif
#ifdef __NR_mkdirat
        case __NR_mkdirat:
            path_index = 1;
            break;
#endif
#ifdef __NR_readlink
        case __NR_readlink:
            path_index = 0;
            break;
#endif
#ifdef __NR_readlinkat
        case __NR_readlinkat:
            path_index = 1;
            break;
#endif
        default:
            return emulateRawSyscall(sysno, mc);
    }

    gLastRedirectTelemetry.redirectable = true;
    const char *pathname = reinterpret_cast<const char *>(args[path_index]);
    const char *redirected_path = redirectRawSyscallPath(pathname);
    const bool redirected = redirected_path != pathname;
    gLastRedirectTelemetry.redirected = redirected;
    copyTelemetryPath(gLastRedirectTelemetry.original,
                      sizeof(gLastRedirectTelemetry.original),
                      pathname);
    copyTelemetryPath(gLastRedirectTelemetry.redirected_path,
                      sizeof(gLastRedirectTelemetry.redirected_path),
                      redirected_path);
    args[path_index] = reinterpret_cast<long>(redirected_path);

    long result = rawKernelSyscall6(sysno, args[0], args[1], args[2],
                                    args[3], args[4], args[5]);
    releaseRedirectedRawPath(pathname, redirected_path);
    return result;
}
#endif

static uint32_t incrementNonTerminationCount(PatchEntry *entry) {
    if (entry == nullptr) {
        return 0;
    }
    return ++entry->non_termination_count;
}

static bool shouldLogNonTerminationTrap(uint32_t count) {
    if (count == 0) {
        return false;
    }
    return count <= 3 || (count & (count - 1)) == 0;
}

static bool isHighFrequencyPassthroughSyscall(int sysno) {
    switch (sysno) {
#ifdef __NR_read
        case __NR_read:
            return true;
#endif
#ifdef __NR_lseek
        case __NR_lseek:
            return true;
#endif
        default:
            return false;
    }
}

static bool shouldRestoreHotPassthroughPatch(PatchEntry *entry, int sysno, uint32_t count) {
    return entry != nullptr
           && entry->active
           && entry->patched
           && !entry->saw_redirectable_syscall
           && isHighFrequencyPassthroughSyscall(sysno)
           && count >= kHotPassthroughRestoreThreshold;
}

static bool restorePatch(PatchEntry *entry) {
    if (entry == nullptr || !entry->active || !entry->patched) {
        return false;
    }
    if (!patchBytes(entry->address, &entry->original, entry->size, entry->prot)) {
        return false;
    }
    entry->patched = false;
    return true;
}

static void restoreHotPassthroughPatch(PatchEntry *entry, int sysno, uint32_t count) {
    if (!shouldRestoreHotPassthroughPatch(entry, sysno, count)) {
        return;
    }
    if (restorePatch(entry)) {
        RAW_SYSCALL_LOGD("raw syscall hot passthrough restored sys=%s(%d) pc=0x%" PRIxPTR
                         " count=%u map=0x%" PRIxPTR "-0x%" PRIxPTR
                         " mapOff=0x%" PRIxPTR " pcFileOff=0x%" PRIxPTR " path=%s",
                         syscallName(sysno),
                         sysno,
                         entry->address,
                         count,
                         entry->map_start,
                         entry->map_end,
                         entry->map_offset,
                         patchedInstructionFileOffset(*entry),
                         entry->path);
    }
}

static const char *pathOrEmpty(const char *path) {
    return path == nullptr ? "" : path;
}

static uintptr_t fileOffsetForAddress(const PatchEntry &entry, uintptr_t address) {
    if (address < entry.map_start || address >= entry.map_end) {
        return 0;
    }
    return entry.map_offset + (address - entry.map_start);
}

static uintptr_t patchedInstructionFileOffset(const PatchEntry &entry) {
    return entry.map_offset + (entry.address - entry.map_start);
}

static PatchEntry *findPatch(uintptr_t pc, uintptr_t fault_address) {
    const int count = gPatchCount;
    for (int i = 0; i < count; i++) {
        PatchEntry &entry = gPatches[i];
        if (!entry.active) {
            continue;
        }
        if (entry.address == pc
            || entry.address + entry.size == pc
            || (fault_address != 0 && entry.address == fault_address)) {
            return &entry;
        }
    }
    return nullptr;
}

static PatchEntry *findRecordedPatch(uintptr_t address) {
    const int count = gPatchCount;
    for (int i = 0; i < count; i++) {
        PatchEntry &entry = gPatches[i];
        if (entry.active && entry.address == address) {
            return &entry;
        }
    }
    return nullptr;
}

static void callPreviousTrapHandler(int signo, siginfo_t *info, void *context_raw) {
    if (!gHasPreviousTrapAction) {
        signal(SIGTRAP, SIG_DFL);
        raise(SIGTRAP);
        return;
    }
    if ((gPreviousTrapAction.sa_flags & SA_SIGINFO) != 0
        && gPreviousTrapAction.sa_sigaction != nullptr) {
        gPreviousTrapAction.sa_sigaction(signo, info, context_raw);
        return;
    }
    if (gPreviousTrapAction.sa_handler == SIG_IGN) {
        return;
    }
    if (gPreviousTrapAction.sa_handler != nullptr
        && gPreviousTrapAction.sa_handler != SIG_DFL) {
        gPreviousTrapAction.sa_handler(signo);
        return;
    }
    signal(SIGTRAP, SIG_DFL);
    raise(SIGTRAP);
}

static void sigtrapHandler(int signo, siginfo_t *info, void *context_raw) {
#if defined(__arm__)
    ucontext_t *context = reinterpret_cast<ucontext_t *>(context_raw);
    if (context == nullptr) {
        callPreviousTrapHandler(signo, info, context_raw);
        return;
    }

    mcontext_t &mc = context->uc_mcontext;
    uintptr_t pc = static_cast<uintptr_t>(mc.arm_pc);
    uintptr_t fault_address = reinterpret_cast<uintptr_t>(info == nullptr ? nullptr : info->si_addr);
    PatchEntry *entry = findPatch(pc, fault_address);
    if (entry == nullptr) {
        if (pc >= 2) {
            entry = findPatch(pc - 2, fault_address);
        }
        if (entry == nullptr && pc >= 4) {
            entry = findPatch(pc - 4, fault_address);
        }
    }
    if (entry == nullptr) {
        callPreviousTrapHandler(signo, info, context_raw);
        return;
    }

    const int sysno = static_cast<int>(mc.arm_r7);
    if (isTerminationSyscall(sysno, mc)) {
        const bool block_termination = gBlockTerminationSyscalls.load();
        const uintptr_t pc_file_offset = patchedInstructionFileOffset(*entry);
        const uintptr_t lr = static_cast<uintptr_t>(mc.arm_lr);
        const uintptr_t lr_file_offset = fileOffsetForAddress(*entry, lr);
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "raw syscall termination intercepted sys=%s(%d) pc=0x%" PRIxPTR
                            " lr=0x%lx sp=0x%lx r0=0x%lx r1=0x%lx r2=0x%lx r7=0x%lx"
                            " root=%d self=%d map=0x%" PRIxPTR "-0x%" PRIxPTR
                            " mapOff=0x%" PRIxPTR " pcFileOff=0x%" PRIxPTR
                            " lrFileOff=0x%" PRIxPTR " blocked=%d resume=%s path=%s",
                            syscallName(sysno),
                            sysno,
                            entry->address,
                            static_cast<unsigned long>(mc.arm_lr),
                            static_cast<unsigned long>(mc.arm_sp),
                            static_cast<unsigned long>(mc.arm_r0),
                            static_cast<unsigned long>(mc.arm_r1),
                            static_cast<unsigned long>(mc.arm_r2),
                            static_cast<unsigned long>(mc.arm_r7),
                            static_cast<int>(gRootPid),
                            static_cast<int>(getpid()),
                            entry->map_start,
                            entry->map_end,
                            entry->map_offset,
                            pc_file_offset,
                            lr_file_offset,
                            block_termination ? 1 : 0,
                            block_termination
                                    ? (isProcessExitSyscall(sysno) ? "lr" : "next")
                                    : "kernel",
                            entry->path);
        if (!block_termination) {
            long result = emulateRawSyscall(sysno, mc);
            mc.arm_r0 = static_cast<unsigned long>(result);
            mc.arm_pc = static_cast<unsigned long>(entry->address + entry->size);
            return;
        }
        mc.arm_r0 = 0;
        if (isProcessExitSyscall(sysno)) {
            resumeBlockedProcessExit(mc, entry);
            return;
        }
    } else {
        const uint32_t count = incrementNonTerminationCount(entry);
        long result = emulateRedirectableRawSyscall(sysno, mc);
        if (gLastRedirectTelemetry.redirectable) {
            entry->saw_redirectable_syscall = true;
        }
        if (shouldLogNonTerminationTrap(count)) {
            if (gLastRedirectTelemetry.redirected) {
                RAW_SYSCALL_LOGD("raw syscall file redirected sys=%s(%d) pc=0x%" PRIxPTR
                                 " path=%s redirected=%s result=0x%lx count=%u"
                                 " map=0x%" PRIxPTR "-0x%" PRIxPTR
                                 " mapOff=0x%" PRIxPTR " pcFileOff=0x%" PRIxPTR
                                 " pathMap=%s",
                                 syscallName(sysno),
                                 sysno,
                                 entry->address,
                                 gLastRedirectTelemetry.original,
                                 gLastRedirectTelemetry.redirected_path,
                                 static_cast<unsigned long>(result),
                                 count,
                                 entry->map_start,
                                 entry->map_end,
                                 entry->map_offset,
                                 patchedInstructionFileOffset(*entry),
                                 entry->path);
            } else if (gLastRedirectTelemetry.redirectable) {
                RAW_SYSCALL_LOGD("raw syscall file passthrough sys=%s(%d) pc=0x%" PRIxPTR
                                 " path=%s result=0x%lx count=%u map=0x%" PRIxPTR
                                 "-0x%" PRIxPTR " mapOff=0x%" PRIxPTR
                                 " pcFileOff=0x%" PRIxPTR " pathMap=%s",
                                 syscallName(sysno),
                                 sysno,
                                 entry->address,
                                 gLastRedirectTelemetry.original,
                                 static_cast<unsigned long>(result),
                                 count,
                                 entry->map_start,
                                 entry->map_end,
                                 entry->map_offset,
                                 patchedInstructionFileOffset(*entry),
                                 entry->path);
            } else {
                RAW_SYSCALL_LOGD("raw syscall non-termination emulated sys=%s(%d) pc=0x%" PRIxPTR
                                 " result=0x%lx count=%u map=0x%" PRIxPTR "-0x%" PRIxPTR
                                 " mapOff=0x%" PRIxPTR " pcFileOff=0x%" PRIxPTR " path=%s",
                                 syscallName(sysno),
                                 sysno,
                                 entry->address,
                                 static_cast<unsigned long>(result),
                                 count,
                                 entry->map_start,
                                 entry->map_end,
                                 entry->map_offset,
                                 patchedInstructionFileOffset(*entry),
                                 entry->path);
            }
        }
        mc.arm_r0 = static_cast<unsigned long>(result);
        restoreHotPassthroughPatch(entry, sysno, count);
    }
    mc.arm_pc = static_cast<unsigned long>(entry->address + entry->size);
#else
    callPreviousTrapHandler(signo, info, context_raw);
#endif
}

static int protFromPerms(const char *perms) {
    int prot = 0;
    if (perms[0] == 'r') {
        prot |= PROT_READ;
    }
    if (perms[1] == 'w') {
        prot |= PROT_WRITE;
    }
    if (perms[2] == 'x') {
        prot |= PROT_EXEC;
    }
    return prot;
}

static bool isPatchableAnonymousExecutableMap(const char *path);

static bool shouldScanPath(const char *path, bool include_file_backed_app_code) {
    if (path == nullptr || path[0] == '\0') {
        return false;
    }
    if (strstr(path, "/system/") != nullptr
        || strstr(path, "/system_ext/") != nullptr
        || strstr(path, "/apex/") != nullptr
        || strstr(path, "/vendor/") != nullptr
        || strstr(path, "/product/") != nullptr
        || strstr(path, "/odm/") != nullptr
        || strstr(path, "libblackbox.so") != nullptr
        || strstr(path, "libpine.so") != nullptr
        || strstr(path, "libblackhook.so") != nullptr
        || strstr(path, "/top.niunaijun.blackboxa32-") != nullptr) {
        return false;
    }
    if (isPatchableAnonymousExecutableMap(path)) {
        return true;
    }
    if (!include_file_backed_app_code) {
        return false;
    }
    return strstr(path, "/data/") != nullptr
           || strstr(path, "/mnt/") != nullptr;
}

static bool isPatchableAnonymousExecutableMap(const char *path) {
    if (path == nullptr || path[0] == '\0') {
        return false;
    }
    return strncmp(path, "[anon:.bss]", 11) == 0;
}

static bool isExcludedVolatileExecutableMap(const char *path) {
    if (path == nullptr || path[0] == '\0') {
        return false;
    }
    if (strncmp(path, "/memfd:", 7) == 0) {
        return true;
    }
    if (strncmp(path, "[anon:pine codes]", 17) == 0) {
        return true;
    }
    if (strncmp(path, "[anon:", 6) == 0 && !isPatchableAnonymousExecutableMap(path)) {
        return true;
    }
    return strstr(path, "jit-cache") != nullptr
           || strstr(path, "dalvik-jit-code-cache") != nullptr;
}

static bool isVolatileExecutableMap(const char *path) {
    return isExcludedVolatileExecutableMap(path) || isPatchableAnonymousExecutableMap(path);
}

static bool patchBytes(uintptr_t address, const void *trap, size_t size, int restore_prot) {
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) {
        return false;
    }
    const uintptr_t page = address & ~static_cast<uintptr_t>(page_size - 1);
    const uintptr_t page_offset = address - page;
    if (page_offset + size > static_cast<uintptr_t>(page_size)) {
        return false;
    }
    if (mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(page_size),
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        return false;
    }
    memcpy(reinterpret_cast<void *>(address), trap, size);
    __builtin___clear_cache(reinterpret_cast<char *>(address),
                            reinterpret_cast<char *>(address + size));
    mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(page_size), restore_prot);
    return true;
}

static void rememberPatch(uintptr_t address, uintptr_t map_start, uintptr_t map_end,
                          uintptr_t map_offset, int prot,
                          uint32_t original, uint8_t size, bool thumb, const char *path) {
    int index = gPatchCount;
    if (index < 0 || static_cast<size_t>(index) >= kMaxPatches) {
        return;
    }
    PatchEntry &entry = gPatches[index];
    entry.address = address;
    entry.map_start = map_start;
    entry.map_end = map_end;
    entry.map_offset = map_offset;
    entry.original = original;
    entry.prot = prot;
    entry.size = size;
    entry.thumb = thumb;
    entry.active = true;
    entry.patched = true;
    entry.saw_redirectable_syscall = false;
    entry.non_termination_count = 0;
    snprintf(entry.path, sizeof(entry.path), "%s", pathOrEmpty(path));
    gPatchCount = index + 1;
}

static void scanAndPatchMap(uintptr_t start, uintptr_t end, uintptr_t map_offset,
                            const char *perms, const char *path,
                            bool include_file_backed_app_code) {
#if defined(__arm__)
    if (start >= end || perms == nullptr || perms[0] != 'r' || perms[2] != 'x') {
        return;
    }
    if (isExcludedVolatileExecutableMap(path)) {
        RAW_SYSCALL_LOGD("raw syscall probe skipped volatile executable map=0x%" PRIxPTR
                         "-0x%" PRIxPTR " mapOff=0x%" PRIxPTR " path=%s",
                         start, end, map_offset, pathOrEmpty(path));
        return;
    }
    if (!shouldScanPath(path, include_file_backed_app_code)) {
        return;
    }
    const int restore_prot = protFromPerms(perms);
    size_t patched = 0;

    for (uintptr_t address = start; address + sizeof(uint16_t) <= end; address += sizeof(uint16_t)) {
        uint16_t value = *reinterpret_cast<uint16_t *>(address);
        if (value != 0xdf00) {
            continue;
        }
        if (findRecordedPatch(address) != nullptr) {
            continue;
        }
        const uint16_t bkpt = 0xbe00;
        if (patchBytes(address, &bkpt, sizeof(bkpt), restore_prot)) {
            rememberPatch(address, start, end, map_offset, restore_prot, value, sizeof(value), true, path);
            patched++;
        }
    }

    const uintptr_t arm_start = (start + 3u) & ~static_cast<uintptr_t>(3u);
    for (uintptr_t address = arm_start; address + sizeof(uint32_t) <= end; address += sizeof(uint32_t)) {
        uint32_t value = *reinterpret_cast<uint32_t *>(address);
        if (value != 0xef000000) {
            continue;
        }
        if (findRecordedPatch(address) != nullptr) {
            continue;
        }
        const uint32_t bkpt = 0xe1200070;
        if (patchBytes(address, &bkpt, sizeof(bkpt), restore_prot)) {
            rememberPatch(address, start, end, map_offset, restore_prot, value, sizeof(value), false, path);
            patched++;
        }
    }

    if (patched > 0) {
        RAW_SYSCALL_LOGD("raw syscall probe patched %zu svc instructions map=0x%" PRIxPTR
                         "-0x%" PRIxPTR " mapOff=0x%" PRIxPTR " path=%s",
                         patched, start, end, map_offset, pathOrEmpty(path));
    }
#else
    (void) start;
    (void) end;
    (void) map_offset;
    (void) perms;
    (void) path;
#endif
}

static void scanProcessMaps(bool include_file_backed_app_code) {
    FILE *fp = fopen("/proc/self/maps", "re");
    if (fp == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "open /proc/self/maps failed: errno=%d (%s)", errno, strerror(errno));
        return;
    }

    char line[1024];
    while (fgets(line, sizeof(line), fp) != nullptr) {
        uintptr_t start = 0;
        uintptr_t end = 0;
        char perms[8] = {};
        char path[512] = {};
        unsigned long offset = 0;
        char dev[32] = {};
        unsigned long inode = 0;
        int fields = sscanf(line, "%" SCNxPTR "-%" SCNxPTR " %7s %lx %31s %lu %511[^\n]",
                            &start, &end, perms, &offset, dev, &inode, path);
        if (fields < 6) {
            continue;
        }
        char *trimmed_path = path;
        while (*trimmed_path == ' ') {
            trimmed_path++;
        }
        scanAndPatchMap(start, end, static_cast<uintptr_t>(offset), perms,
                        fields >= 7 ? trimmed_path : "",
                        include_file_backed_app_code);
        if (static_cast<size_t>(gPatchCount) >= kMaxPatches) {
            __android_log_print(ANDROID_LOG_WARN, kTag,
                                "raw syscall probe patch limit reached: %zu", kMaxPatches);
            break;
        }
    }
    fclose(fp);
}

static bool installTrapHandler() {
    struct sigaction action = {};
    action.sa_sigaction = sigtrapHandler;
    sigemptyset(&action.sa_mask);
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    if (sigaction(SIGTRAP, &action, &gPreviousTrapAction) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "sigaction(SIGTRAP) failed: errno=%d (%s)", errno, strerror(errno));
        return false;
    }
    gHasPreviousTrapAction = true;
    return true;
}

static bool ensureCurrentThreadTrapAlternateStack() {
    if (gThreadTrapAltStackInstalled) {
        return true;
    }
    void *stack_memory = mmap(nullptr,
                              kTrapAltStackSize,
                              PROT_READ | PROT_WRITE,
                              MAP_PRIVATE | MAP_ANONYMOUS,
                              -1,
                              0);
    if (stack_memory == MAP_FAILED) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "sigaltstack mmap failed: errno=%d (%s)", errno, strerror(errno));
        return false;
    }
    stack_t stack = {};
    stack.ss_sp = stack_memory;
    stack.ss_size = kTrapAltStackSize;
    stack.ss_flags = 0;
    if (sigaltstack(&stack, nullptr) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "sigaltstack install failed: errno=%d (%s)", errno, strerror(errno));
        munmap(stack_memory, kTrapAltStackSize);
        return false;
    }
    gThreadTrapAltStack = stack_memory;
    gThreadTrapAltStackInstalled = true;
    return true;
}

static bool ensureRawSyscallProbeInstalled() {
#if defined(__arm__)
    if (!ensureCurrentThreadTrapAlternateStack()) {
        return false;
    }
    bool expected = false;
    if (gInstalled.compare_exchange_strong(expected, true)) {
        gRootPid = getpid();
        if (!installTrapHandler()) {
            gInstalled.store(false);
            return false;
        }
    }
    return true;
#else
    return false;
#endif
}

} // namespace

void setRawSyscallTerminationBlocking(bool enabled) {
    gBlockTerminationSyscalls.store(enabled);
}

void installRawSyscallEnvironmentProbe() {
#if defined(__arm__)
    setRawSyscallTerminationBlocking(false);
    if (!ensureRawSyscallProbeInstalled()) {
        return;
    }
    ScopedPatchRegistryLock lock;
    scanProcessMaps(true);
    RAW_SYSCALL_LOGD("raw syscall environment probe installed root=%d patches=%d",
                     static_cast<int>(gRootPid),
                     static_cast<int>(gPatchCount));
#else
    __android_log_print(ANDROID_LOG_WARN, kTag,
                        "raw syscall environment probe unsupported on this ABI");
#endif
}

void installRawSyscallTerminationProbe() {
#if defined(__arm__)
    setRawSyscallTerminationBlocking(true);
    if (!ensureRawSyscallProbeInstalled()) {
        return;
    }
    ScopedPatchRegistryLock lock;
    scanProcessMaps(true);
    RAW_SYSCALL_LOGD("raw syscall termination probe installed root=%d patches=%d",
                     static_cast<int>(gRootPid),
                     static_cast<int>(gPatchCount));
#else
    __android_log_print(ANDROID_LOG_WARN, kTag,
                        "raw syscall termination probe unsupported on this ABI");
#endif
}

void refreshRawSyscallProbeMaps() {
#if defined(__arm__)
    if (!ensureRawSyscallProbeInstalled()) {
        return;
    }
    if (!ensureCurrentThreadTrapAlternateStack()) {
        return;
    }
    ScopedPatchRegistryLock lock;
    int before = static_cast<int>(gPatchCount);
    scanProcessMaps(false);
    RAW_SYSCALL_LOGD("raw syscall probe refreshed root=%d patches=%d added=%d",
                     static_cast<int>(gRootPid),
                     static_cast<int>(gPatchCount),
                     static_cast<int>(gPatchCount) - before);
#else
    __android_log_print(ANDROID_LOG_WARN, kTag,
                        "raw syscall probe refresh unsupported on this ABI");
#endif
}

} // namespace rawsyscall
} // namespace blackbox
