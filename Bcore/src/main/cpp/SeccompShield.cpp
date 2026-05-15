#include "SeccompShield.h"

#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/futex.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/ucontext.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>

#include <atomic>

namespace blackbox {
namespace seccomp {
namespace {

constexpr const char *kSeccompTag = "BlackBoxSeccomp";
constexpr uint32_t kMaxTrapEvents = 8;
constexpr uint32_t kMaxFrames = 16;
constexpr uint32_t kSlotFree = 0;
constexpr uint32_t kSlotWriting = 1;
constexpr uint32_t kSlotReady = 2;
constexpr uint32_t kSlotProcessing = 3;
constexpr uint32_t kSlotDone = 4;
constexpr uint32_t kSlotTimedOut = 5;
constexpr int kTrapWaitTimeoutSec = 2;
constexpr long kSigsysWatchdogBurstPollNs = 1L * 1000L * 1000L;
constexpr long kSigsysWatchdogPollNs = 250L * 1000L * 1000L;
constexpr time_t kSigsysWatchdogBurstDurationSec = 3;
constexpr uint64_t kTrapBreadcrumbWindowMs = 3000;
constexpr size_t kStatusBufferSize = 4096;
constexpr size_t kMaxTrackedThreads = 64;
constexpr uint32_t kSeccompReturnSuccess = SECCOMP_RET_ERRNO | 0;
constexpr uint32_t kSeccompReturnTrap = SECCOMP_RET_TRAP;

static std::atomic<bool> gSeccompInstalled(false);
static std::atomic<bool> gTerminationOnlySeccompInstalled(false);
static std::atomic<bool> gTerminationTrapSeccompInstalled(false);
static std::atomic<bool> gDumperStarted(false);
static std::atomic<bool> gSigsysWatchdogStarted(false);
static std::atomic<uint32_t> gVirtualSigsysSeq(0);
static std::atomic<int> gVirtualUid(-1);
static std::atomic<int> gKernelUid(-1);
static std::atomic<pid_t> gLastTracerPid(-1);
static std::atomic<unsigned long long> gLastSigIgnMask(ULLONG_MAX);
static std::atomic<unsigned long long> gLastSigCgtMask(ULLONG_MAX);
static std::atomic<uint32_t> gSigsysCanarySeq(0);
static std::atomic<uint64_t> gSeccompInstallMonotonicMs(0);
static std::atomic_flag gRtSigprocmaskBypassLock = ATOMIC_FLAG_INIT;
struct RtSigprocmaskBypassStorage {
    sigset_t mask;
    unsigned long padding;
};
static RtSigprocmaskBypassStorage gRtSigprocmaskBypassStorage = {};
static sigset_t &gRtSigprocmaskBypassSet = gRtSigprocmaskBypassStorage.mask;
static int gTrapPipe[2] = {-1, -1};
static pthread_t gDumperThread;
static pthread_t gSigsysWatchdogThread;
static struct sigaction gExpectedSigsysAction = {};
static struct sigaction gVirtualSigsysAction = {};
static __thread int gSigsysForwardDepth = 0;
static __thread bool gSigsysCanaryArmed = false;
static pthread_once_t gSignalMaskHookOnce = PTHREAD_ONCE_INIT;
static __thread int gSignalMaskHookDepth = 0;

using SigprocmaskFn = int (*)(int, const sigset_t *, sigset_t *);
using RtSigprocmaskFn = int (*)(int, const sigset_t *, sigset_t *, size_t);

static SigprocmaskFn gOrigSigprocmask = nullptr;
static SigprocmaskFn gOrigPthreadSigmask = nullptr;
static RtSigprocmaskFn gOrigRtSigprocmask = nullptr;
static RtSigprocmaskFn gOrigInternalRtSigprocmask = nullptr;

struct ThreadMaskState {
    pid_t tid;
    bool blocked;
};

static ThreadMaskState gThreadMaskStates[kMaxTrackedThreads] = {};

#ifndef SECCOMP_SET_MODE_FILTER
#define SECCOMP_SET_MODE_FILTER 1
#endif

#ifndef SECCOMP_FILTER_FLAG_TSYNC
#define SECCOMP_FILTER_FLAG_TSYNC (1UL << 0)
#endif

#ifndef FUTEX_WAIT_PRIVATE
#define FUTEX_WAIT_PRIVATE FUTEX_WAIT
#endif

#ifndef FUTEX_WAKE_PRIVATE
#define FUTEX_WAKE_PRIVATE FUTEX_WAKE
#endif

#if defined(__aarch64__)
constexpr uint32_t kAuditArch = AUDIT_ARCH_AARCH64;
constexpr int kSysExit = 93;
constexpr int kSysExitGroup = 94;
constexpr int kSysRtSigaction = 134;
constexpr int kSysKill = 129;
constexpr int kSysTkill = 130;
constexpr int kSysTgkill = 131;
constexpr int kSysRtSigprocmask = __NR_rt_sigprocmask;
constexpr int kSysGetuid = __NR_getuid;
constexpr int kSysGeteuid = __NR_geteuid;
constexpr int kSysGetuid32 = -1;
constexpr int kSysGeteuid32 = -1;
#elif defined(__arm__)
constexpr uint32_t kAuditArch = AUDIT_ARCH_ARM;
constexpr int kSysExit = 1;
constexpr int kSysSignal = 48;
constexpr int kSysSigaction = 67;
constexpr int kSysSigprocmask = 126;
constexpr int kSysKill = 37;
constexpr int kSysRtSigaction = 174;
constexpr int kSysRtSigprocmask = __NR_rt_sigprocmask;
constexpr int kSysGetuid = __NR_getuid;
constexpr int kSysGeteuid = __NR_geteuid;
constexpr int kSysGetuid32 = __NR_getuid32;
constexpr int kSysGeteuid32 = __NR_geteuid32;
constexpr int kSysTkill = 238;
constexpr int kSysExitGroup = 248;
constexpr int kSysTgkill = 268;
#endif

#ifdef __NR_rt_sigqueueinfo
constexpr int kSysRtSigqueueinfo = __NR_rt_sigqueueinfo;
#else
constexpr int kSysRtSigqueueinfo = -1;
#endif

#ifdef __NR_rt_tgsigqueueinfo
constexpr int kSysRtTgsigqueueinfo = __NR_rt_tgsigqueueinfo;
#else
constexpr int kSysRtTgsigqueueinfo = -1;
#endif

struct TrapEvent {
    std::atomic<uint32_t> state;
    pid_t pid;
    pid_t tid;
    int sysno;
    int si_code;
    int si_errno;
    uintptr_t trap_call_addr;
    uintptr_t pc;
    uintptr_t sp;
    uintptr_t lr;
    uintptr_t fp;
    uintptr_t regs[8];
    uintptr_t frames[kMaxFrames];
    uint32_t frame_count;
    uint32_t flags;
};

static TrapEvent gTrapEvents[kMaxTrapEvents];

static bool isProcessExitSyscall(int sysno) {
    return sysno == kSysExit || sysno == kSysExitGroup;
}

static int futexWait(std::atomic<uint32_t> *addr, uint32_t expected) {
    return static_cast<int>(syscall(__NR_futex,
                                    reinterpret_cast<uint32_t *>(addr),
                                    FUTEX_WAIT_PRIVATE,
                                    expected,
                                    nullptr,
                                    nullptr,
                                    0));
}

static int futexTimedWait(std::atomic<uint32_t> *addr, uint32_t expected, const struct timespec *timeout) {
    return static_cast<int>(syscall(__NR_futex,
                                    reinterpret_cast<uint32_t *>(addr),
                                    FUTEX_WAIT_PRIVATE,
                                    expected,
                                    timeout,
                                    nullptr,
                                    0));
}

static int futexWake(std::atomic<uint32_t> *addr) {
    return static_cast<int>(syscall(__NR_futex,
                                    reinterpret_cast<uint32_t *>(addr),
                                    FUTEX_WAKE_PRIVATE,
                                    INT_MAX,
                                    nullptr,
                                    nullptr,
                                    0));
}

static ssize_t writeExact(int fd, const void *buffer, size_t len) {
    const uint8_t *cursor = reinterpret_cast<const uint8_t *>(buffer);
    size_t remaining = len;
    while (remaining > 0) {
        ssize_t written = write(fd, cursor, remaining);
        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        cursor += written;
        remaining -= static_cast<size_t>(written);
    }
    return static_cast<ssize_t>(len);
}

static uintptr_t sigsysHandlerPointer(const struct sigaction &action) {
    if ((action.sa_flags & SA_SIGINFO) != 0) {
        return reinterpret_cast<uintptr_t>(action.sa_sigaction);
    }
    return reinterpret_cast<uintptr_t>(action.sa_handler);
}

static const char *sigsysDispositionName(const struct sigaction &action) {
    const uintptr_t handler = sigsysHandlerPointer(action);
    if (handler == reinterpret_cast<uintptr_t>(SIG_IGN)) {
        return "SIG_IGN";
    }
    if (handler == reinterpret_cast<uintptr_t>(SIG_DFL)) {
        return "SIG_DFL";
    }
    if ((action.sa_flags & SA_SIGINFO) != 0) {
        return "SA_SIGINFO";
    }
    return "SA_HANDLER";
}

static bool sigsysActionMatchesExpected(const struct sigaction &action) {
    return (action.sa_flags & SA_SIGINFO) == (gExpectedSigsysAction.sa_flags & SA_SIGINFO)
           && (action.sa_flags & SA_NODEFER) == (gExpectedSigsysAction.sa_flags & SA_NODEFER)
           && sigsysHandlerPointer(action) == sigsysHandlerPointer(gExpectedSigsysAction);
}

static void logSigsysAction(const char *prefix, const struct sigaction &action) {
    __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                        "%s disposition=%s handler=%p flags=0x%lx nodefer=%d siginfo=%d",
                        prefix,
                        sigsysDispositionName(action),
                        reinterpret_cast<void *>(sigsysHandlerPointer(action)),
                        static_cast<unsigned long>(action.sa_flags),
                        (action.sa_flags & SA_NODEFER) != 0,
                        (action.sa_flags & SA_SIGINFO) != 0);
}

static bool querySigsysAction(struct sigaction *action) {
    if (sigaction(SIGSYS, nullptr, action) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                            "sigaction(SIGSYS, query) failed: errno=%d (%s)", errno, strerror(errno));
        return false;
    }
    return true;
}

static bool isSignalBlockedInMask(unsigned long long mask, int signo) {
    if (signo <= 0 || signo > 64) {
        return false;
    }
    return (mask & (1ULL << (signo - 1))) != 0;
}

static bool parseSigBlkMask(const char *status, unsigned long long *mask) {
    const char *line = strstr(status, "\nSigBlk:");
    if (line == nullptr) {
        if (strncmp(status, "SigBlk:", 7) != 0) {
            return false;
        }
        line = status;
    } else {
        line += 1;
    }

    const char *value = strchr(line, ':');
    if (value == nullptr) {
        return false;
    }
    ++value;
    while (*value == ' ' || *value == '\t') {
        ++value;
    }

    errno = 0;
    char *end = nullptr;
    unsigned long long parsed = strtoull(value, &end, 16);
    if (end == value || errno != 0) {
        return false;
    }
    *mask = parsed;
    return true;
}

static bool parseNamedSignalMask(const char *status, const char *name, unsigned long long *mask) {
    const size_t name_len = strlen(name);
    const char *line = strstr(status, name);
    if (line == nullptr) {
        return false;
    }
    if (line != status && line[-1] != '\n') {
        return false;
    }

    const char *value = strchr(line, ':');
    if (value == nullptr) {
        return false;
    }
    ++value;
    while (*value == ' ' || *value == '\t') {
        ++value;
    }

    errno = 0;
    char *end = nullptr;
    unsigned long long parsed = strtoull(value, &end, 16);
    if (end == value || errno != 0) {
        return false;
    }
    *mask = parsed;
    return true;
}

static bool parseTracerPid(const char *status, pid_t *tracer_pid) {
    const char *line = strstr(status, "\nTracerPid:");
    if (line == nullptr) {
        if (strncmp(status, "TracerPid:", 10) != 0) {
            return false;
        }
        line = status;
    } else {
        line += 1;
    }

    const char *value = strchr(line, ':');
    if (value == nullptr) {
        return false;
    }
    ++value;
    while (*value == ' ' || *value == '\t') {
        ++value;
    }

    errno = 0;
    char *end = nullptr;
    long parsed = strtol(value, &end, 10);
    if (end == value || errno != 0 || parsed < 0) {
        return false;
    }
    *tracer_pid = static_cast<pid_t>(parsed);
    return true;
}

static bool readThreadStatus(pid_t tid, char *buffer, size_t buffer_size) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/task/%d/status", tid);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return false;
    }

    ssize_t bytes = read(fd, buffer, buffer_size - 1);
    close(fd);
    if (bytes <= 0) {
        return false;
    }

    buffer[bytes] = '\0';
    return true;
}

static bool readProcessStatus(char *buffer, size_t buffer_size) {
    int fd = open("/proc/self/status", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return false;
    }

    ssize_t bytes = read(fd, buffer, buffer_size - 1);
    close(fd);
    if (bytes <= 0) {
        return false;
    }

    buffer[bytes] = '\0';
    return true;
}

static void scanTracerPid() {
    char status[kStatusBufferSize];
    if (!readProcessStatus(status, sizeof(status))) {
        return;
    }

    pid_t tracer_pid = 0;
    if (!parseTracerPid(status, &tracer_pid)) {
        return;
    }

    pid_t previous = gLastTracerPid.exchange(tracer_pid);
    if (previous != tracer_pid) {
        __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                            "TracerPid changed previous=%d current=%d",
                            previous, tracer_pid);
    }
}

static void scanProcessSignalDispositions() {
    char status[kStatusBufferSize];
    if (!readProcessStatus(status, sizeof(status))) {
        return;
    }

    unsigned long long sig_ign = 0;
    unsigned long long sig_cgt = 0;
    if (!parseNamedSignalMask(status, "SigIgn:", &sig_ign)
        || !parseNamedSignalMask(status, "SigCgt:", &sig_cgt)) {
        return;
    }

    const bool ignored = isSignalBlockedInMask(sig_ign, SIGSYS);
    const bool caught = isSignalBlockedInMask(sig_cgt, SIGSYS);
    const unsigned long long previous_ign = gLastSigIgnMask.exchange(sig_ign);
    const unsigned long long previous_cgt = gLastSigCgtMask.exchange(sig_cgt);
    if (previous_ign != sig_ign || previous_cgt != sig_cgt) {
        __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                            "SIGSYS proc state caught=%d ignored=%d sigcgt=0x%llx sigign=0x%llx",
                            caught ? 1 : 0, ignored ? 1 : 0, sig_cgt, sig_ign);
    }
}

static void noteThreadSigsysMask(pid_t tid, bool blocked, unsigned long long mask) {
    ThreadMaskState *slot = nullptr;
    for (size_t i = 0; i < kMaxTrackedThreads; ++i) {
        if (gThreadMaskStates[i].tid == tid) {
            slot = &gThreadMaskStates[i];
            break;
        }
        if (slot == nullptr && gThreadMaskStates[i].tid == 0) {
            slot = &gThreadMaskStates[i];
        }
    }

    if (slot == nullptr) {
        return;
    }

    if (slot->tid == 0) {
        slot->tid = tid;
        slot->blocked = !blocked;
    }

    if (slot->blocked == blocked) {
        return;
    }

    slot->blocked = blocked;
    __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                        "SIGSYS mask changed tid=%d blocked=%d sigblk=0x%llx",
                        tid, blocked ? 1 : 0, mask);
}

static void scanThreadSignalMasks() {
    DIR *dir = opendir("/proc/self/task");
    if (dir == nullptr) {
        return;
    }

    pid_t seen_tids[kMaxTrackedThreads] = {};
    size_t seen_count = 0;
    struct dirent *entry = nullptr;
    char status[kStatusBufferSize];
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_name[0] == '.') {
            continue;
        }
        char *end = nullptr;
        long tid_long = strtol(entry->d_name, &end, 10);
        if (end == entry->d_name || *end != '\0' || tid_long <= 0) {
            continue;
        }

        pid_t tid = static_cast<pid_t>(tid_long);
        if (!readThreadStatus(tid, status, sizeof(status))) {
            continue;
        }

        unsigned long long mask = 0;
        if (!parseSigBlkMask(status, &mask)) {
            continue;
        }

        if (seen_count < kMaxTrackedThreads) {
            seen_tids[seen_count++] = tid;
        }
        noteThreadSigsysMask(tid, isSignalBlockedInMask(mask, SIGSYS), mask);
    }
    closedir(dir);

    for (size_t i = 0; i < kMaxTrackedThreads; ++i) {
        if (gThreadMaskStates[i].tid == 0) {
            continue;
        }
        bool found = false;
        for (size_t j = 0; j < seen_count; ++j) {
            if (seen_tids[j] == gThreadMaskStates[i].tid) {
                found = true;
                break;
            }
        }
        if (!found) {
            gThreadMaskStates[i].tid = 0;
            gThreadMaskStates[i].blocked = false;
        }
    }
}

static long getThreadId() {
    return syscall(__NR_gettid);
}

static bool sanitizeSignalMaskCopy(const sigset_t *set, sigset_t *sanitized) {
    if (set == nullptr || sanitized == nullptr) {
        return false;
    }
    memcpy(sanitized, set, sizeof(sigset_t));
    const int had_sigsys = sigismember(sanitized, SIGSYS);
    const int had_sigill = sigismember(sanitized, SIGILL);
    if (had_sigsys == 1) {
        sigdelset(sanitized, SIGSYS);
    }
    if (had_sigill == 1) {
        sigdelset(sanitized, SIGILL);
    }
    return had_sigsys == 1 || had_sigill == 1;
}

static void initializeSignalMaskHooks() {
    gOrigSigprocmask = reinterpret_cast<SigprocmaskFn>(dlsym(RTLD_NEXT, "sigprocmask"));
    gOrigPthreadSigmask = reinterpret_cast<SigprocmaskFn>(dlsym(RTLD_NEXT, "pthread_sigmask"));
    gOrigRtSigprocmask = reinterpret_cast<RtSigprocmaskFn>(dlsym(RTLD_NEXT, "rt_sigprocmask"));
    gOrigInternalRtSigprocmask = reinterpret_cast<RtSigprocmaskFn>(dlsym(RTLD_NEXT, "__rt_sigprocmask"));
    __android_log_print(ANDROID_LOG_DEBUG, kSeccompTag,
                        "signal-mask hooks ready sigprocmask=%p pthread_sigmask=%p rt_sigprocmask=%p __rt_sigprocmask=%p",
                        reinterpret_cast<void *>(gOrigSigprocmask),
                        reinterpret_cast<void *>(gOrigPthreadSigmask),
                        reinterpret_cast<void *>(gOrigRtSigprocmask),
                        reinterpret_cast<void *>(gOrigInternalRtSigprocmask));
}

static const sigset_t *sanitizeSignalMaskArgument(const char *api_name, int how,
                                                  const sigset_t *set, sigset_t *sanitized) {
    if (gSignalMaskHookDepth != 0 || set == nullptr
        || (how != SIG_BLOCK && how != SIG_SETMASK)) {
        return set;
    }
    if (!sanitizeSignalMaskCopy(set, sanitized)) {
        return set;
    }

    __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                        "signal-mask sanitize api=%s tid=%ld how=%d removed=%s%s",
                        api_name,
                        getThreadId(),
                        how,
                        sigismember(set, SIGSYS) == 1 ? "SIGSYS" : "",
                        sigismember(set, SIGILL) == 1 ? (sigismember(set, SIGSYS) == 1 ? "|SIGILL" : "SIGILL") : "");
    return sanitized;
}

static uint64_t monotonicMs() {
    struct timespec ts = {};
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0;
    }
    return static_cast<uint64_t>(ts.tv_sec) * 1000ULL
           + static_cast<uint64_t>(ts.tv_nsec / 1000000ULL);
}

static bool shouldLogTrapBreadcrumb() {
    const uint64_t installed_at = gSeccompInstallMonotonicMs.load(std::memory_order_relaxed);
    if (installed_at == 0) {
        return true;
    }
    const uint64_t now_ms = monotonicMs();
    return now_ms != 0 && now_ms - installed_at <= kTrapBreadcrumbWindowMs;
}

static bool isProtectedSyscall(int sysno) {
#if defined(__aarch64__) || defined(__arm__)
    return sysno == kSysExit || sysno == kSysExitGroup
           || sysno == kSysKill || sysno == kSysTkill || sysno == kSysTgkill
           || sysno == kSysRtSigqueueinfo || sysno == kSysRtTgsigqueueinfo
           || sysno == __NR_prctl || sysno == __NR_seccomp;
#else
    return false;
#endif
}

static void initializeVirtualSigsysAction() {
    memset(&gVirtualSigsysAction, 0, sizeof(gVirtualSigsysAction));
    gVirtualSigsysAction.sa_handler = SIG_DFL;
    sigemptyset(&gVirtualSigsysAction.sa_mask);
    gVirtualSigsysSeq.store(0, std::memory_order_release);
}

static void storeVirtualSigsysAction(const struct sigaction &action) {
    gVirtualSigsysSeq.fetch_add(1, std::memory_order_acq_rel);
    gVirtualSigsysAction = action;
    gVirtualSigsysSeq.fetch_add(1, std::memory_order_release);
}

static void loadVirtualSigsysAction(struct sigaction *action) {
    for (;;) {
        uint32_t before = gVirtualSigsysSeq.load(std::memory_order_acquire);
        if ((before & 1U) != 0) {
            continue;
        }
        *action = gVirtualSigsysAction;
        uint32_t after = gVirtualSigsysSeq.load(std::memory_order_acquire);
        if (before == after) {
            return;
        }
    }
}

struct HandlerBacktraceState {
    TrapEvent *event;
};

static _Unwind_Reason_Code unwindCallback(struct _Unwind_Context *context, void *arg) {
    HandlerBacktraceState *state = reinterpret_cast<HandlerBacktraceState *>(arg);
    TrapEvent *event = state->event;
    if (event->frame_count >= kMaxFrames) {
        return _URC_END_OF_STACK;
    }
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc != 0) {
        event->frames[event->frame_count++] = pc;
    }
    return _URC_NO_REASON;
}

#if defined(__aarch64__)
static int getSyscallNumber(const ucontext_t *context) {
    return static_cast<int>(context->uc_mcontext.regs[8]);
}

static uintptr_t getSyscallArg(const ucontext_t *context, int index) {
    return static_cast<uintptr_t>(context->uc_mcontext.regs[index]);
}

static void emulateReturn(ucontext_t *context, uintptr_t value) {
    context->uc_mcontext.regs[0] = value;
}

static void emulateSuccess(ucontext_t *context) {
    emulateReturn(context, 0);
}

static void emulateBlockedProcessExitReturn(ucontext_t *context) {
    emulateReturn(context, 0);
    uintptr_t lr = static_cast<uintptr_t>(context->uc_mcontext.regs[30]);
    if (lr != 0) {
        context->uc_mcontext.pc = lr;
    }
}

static void fillTrapEvent(TrapEvent *event, const siginfo_t *info, const ucontext_t *context) {
    const mcontext_t &mc = context->uc_mcontext;
    event->pid = getpid();
    event->tid = static_cast<pid_t>(getThreadId());
    event->sysno = static_cast<int>(mc.regs[8]);
    event->si_code = info != nullptr ? info->si_code : 0;
    event->si_errno = info != nullptr ? info->si_errno : 0;
    event->trap_call_addr = reinterpret_cast<uintptr_t>(info != nullptr ? info->si_call_addr : nullptr);
    event->pc = static_cast<uintptr_t>(mc.pc);
    event->sp = static_cast<uintptr_t>(mc.sp);
    event->lr = static_cast<uintptr_t>(mc.regs[30]);
    event->fp = static_cast<uintptr_t>(mc.regs[29]);
    event->regs[0] = static_cast<uintptr_t>(mc.regs[0]);
    event->regs[1] = static_cast<uintptr_t>(mc.regs[1]);
    event->regs[2] = static_cast<uintptr_t>(mc.regs[2]);
    event->regs[3] = static_cast<uintptr_t>(mc.regs[3]);
    event->regs[4] = static_cast<uintptr_t>(mc.regs[4]);
    event->regs[5] = static_cast<uintptr_t>(mc.regs[5]);
    event->regs[6] = static_cast<uintptr_t>(mc.regs[6]);
    event->regs[7] = static_cast<uintptr_t>(mc.regs[8]);
    event->frame_count = 0;
    event->flags = 0;
}

#elif defined(__arm__)
static int getSyscallNumber(const ucontext_t *context) {
    return static_cast<int>(context->uc_mcontext.arm_r7);
}

static uintptr_t getSyscallArg(const ucontext_t *context, int index) {
    switch (index) {
        case 0:
            return static_cast<uintptr_t>(context->uc_mcontext.arm_r0);
        case 1:
            return static_cast<uintptr_t>(context->uc_mcontext.arm_r1);
        case 2:
            return static_cast<uintptr_t>(context->uc_mcontext.arm_r2);
        case 3:
            return static_cast<uintptr_t>(context->uc_mcontext.arm_r3);
        case 4:
            return static_cast<uintptr_t>(context->uc_mcontext.arm_r4);
        case 5:
            return static_cast<uintptr_t>(context->uc_mcontext.arm_r5);
        default:
            return 0;
    }
}

static void emulateReturn(ucontext_t *context, uintptr_t value) {
    context->uc_mcontext.arm_r0 = value;
}

static void emulateSuccess(ucontext_t *context) {
    emulateReturn(context, 0);
}

static void emulateBlockedProcessExitReturn(ucontext_t *context) {
    emulateReturn(context, 0);
    uintptr_t lr = static_cast<uintptr_t>(context->uc_mcontext.arm_lr);
    if (lr != 0) {
        context->uc_mcontext.arm_pc = static_cast<unsigned long>(lr);
    }
}

static void fillTrapEvent(TrapEvent *event, const siginfo_t *info, const ucontext_t *context) {
    const mcontext_t &mc = context->uc_mcontext;
    event->pid = getpid();
    event->tid = static_cast<pid_t>(getThreadId());
    event->sysno = static_cast<int>(mc.arm_r7);
    event->si_code = info != nullptr ? info->si_code : 0;
    event->si_errno = info != nullptr ? info->si_errno : 0;
    event->trap_call_addr = reinterpret_cast<uintptr_t>(info != nullptr ? info->si_call_addr : nullptr);
    event->pc = static_cast<uintptr_t>(mc.arm_pc);
    event->sp = static_cast<uintptr_t>(mc.arm_sp);
    event->lr = static_cast<uintptr_t>(mc.arm_lr);
    event->fp = static_cast<uintptr_t>(mc.arm_fp);
    event->regs[0] = static_cast<uintptr_t>(mc.arm_r0);
    event->regs[1] = static_cast<uintptr_t>(mc.arm_r1);
    event->regs[2] = static_cast<uintptr_t>(mc.arm_r2);
    event->regs[3] = static_cast<uintptr_t>(mc.arm_r3);
    event->regs[4] = static_cast<uintptr_t>(mc.arm_r4);
    event->regs[5] = static_cast<uintptr_t>(mc.arm_r5);
    event->regs[6] = static_cast<uintptr_t>(mc.arm_r6);
    event->regs[7] = static_cast<uintptr_t>(mc.arm_r7);
    event->frame_count = 0;
    event->flags = static_cast<uint32_t>(mc.arm_cpsr);
}

#endif

static int signalArgumentIndexForSignalDeliverySyscall(int sysno) {
#if defined(__aarch64__) || defined(__arm__)
    if (sysno == kSysKill || sysno == kSysTkill || sysno == kSysRtSigqueueinfo) {
        return 1;
    }
    if (sysno == kSysTgkill || sysno == kSysRtTgsigqueueinfo) {
        return 2;
    }
#else
    (void) sysno;
#endif
    return -1;
}

static bool isSigsysSignalDeliverySyscall(int sysno, const ucontext_t *context) {
#if defined(__aarch64__) || defined(__arm__)
    const int signal_index = signalArgumentIndexForSignalDeliverySyscall(sysno);
    return signal_index >= 0 && static_cast<int>(getSyscallArg(context, signal_index)) == SIGSYS;
#else
    (void) sysno;
    (void) context;
    return false;
#endif
}

static bool isTrappedSigsysCanarySignalSend(int sysno, const ucontext_t *context) {
#if defined(__aarch64__) || defined(__arm__)
    return gSigsysCanaryArmed
           && sysno == kSysTgkill
           && static_cast<pid_t>(getSyscallArg(context, 0)) == getpid()
           && static_cast<pid_t>(getSyscallArg(context, 1)) == static_cast<pid_t>(getThreadId())
           && isSigsysSignalDeliverySyscall(sysno, context);
#else
    (void) sysno;
    (void) context;
    return false;
#endif
}

static void logTrapBreadcrumb(const siginfo_t *info, const ucontext_t *context, int sysno) {
    if (!shouldLogTrapBreadcrumb()) {
        return;
    }
    __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                        "seccomp breadcrumb tid=%ld sysno=%d a0=%p a1=%p a2=%p a3=%p si_code=%d si_errno=%d si_call_addr=%p",
                        getThreadId(),
                        sysno,
                        reinterpret_cast<void *>(getSyscallArg(context, 0)),
                        reinterpret_cast<void *>(getSyscallArg(context, 1)),
                        reinterpret_cast<void *>(getSyscallArg(context, 2)),
                        reinterpret_cast<void *>(getSyscallArg(context, 3)),
                        info != nullptr ? info->si_code : 0,
                        info != nullptr ? info->si_errno : 0,
                        info != nullptr ? info->si_call_addr : nullptr);
}

static uintptr_t syscallReturnValue(long result) {
    if (result == -1) {
        return static_cast<uintptr_t>(-errno);
    }
    return static_cast<uintptr_t>(result);
}

static void lockRtSigprocmaskBypass() {
    while (gRtSigprocmaskBypassLock.test_and_set(std::memory_order_acquire)) {
    }
}

static void unlockRtSigprocmaskBypass() {
    gRtSigprocmaskBypassLock.clear(std::memory_order_release);
}

static void forwardVirtualSigsys(int signo, siginfo_t *info, void *context_raw) {
    if (gSigsysForwardDepth != 0) {
        return;
    }

    struct sigaction action = {};
    loadVirtualSigsysAction(&action);
    uintptr_t handler_ptr = sigsysHandlerPointer(action);
    if (handler_ptr == reinterpret_cast<uintptr_t>(SIG_IGN)
        || handler_ptr == reinterpret_cast<uintptr_t>(SIG_DFL)) {
        return;
    }

    ++gSigsysForwardDepth;
    __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                        "non-seccomp SIGSYS forwarding tid=%ld si_code=%d si_errno=%d handler=%p flags=0x%lx",
                        getThreadId(),
                        info != nullptr ? info->si_code : 0,
                        info != nullptr ? info->si_errno : 0,
                        reinterpret_cast<void *>(handler_ptr),
                        static_cast<unsigned long>(action.sa_flags));
    if ((action.sa_flags & SA_SIGINFO) != 0 && action.sa_sigaction != nullptr) {
        action.sa_sigaction(signo, info, context_raw);
    } else if (action.sa_handler != nullptr) {
        action.sa_handler(signo);
    }
    --gSigsysForwardDepth;
}

static void emulateVirtualSigaction(siginfo_t *info, ucontext_t *context) {
    if (static_cast<int>(getSyscallArg(context, 0)) != SIGSYS) {
        emulateSuccess(context);
        return;
    }

    struct sigaction current = {};
    loadVirtualSigsysAction(&current);

    auto *new_action = reinterpret_cast<const struct sigaction *>(getSyscallArg(context, 1));
    auto *old_action = reinterpret_cast<struct sigaction *>(getSyscallArg(context, 2));
    if (old_action != nullptr) {
        *old_action = current;
    }

    if (new_action != nullptr) {
        storeVirtualSigsysAction(*new_action);
        __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                            "virtualized rt_sigaction(SIGSYS): handler=%p flags=0x%lx si_code=%d",
                            reinterpret_cast<void *>(sigsysHandlerPointer(*new_action)),
                            static_cast<unsigned long>(new_action->sa_flags),
                            info != nullptr ? info->si_code : 0);
    }

    emulateSuccess(context);
}

#if defined(__arm__)
static void emulateVirtualSignal(siginfo_t *info, ucontext_t *context) {
    if (static_cast<int>(getSyscallArg(context, 0)) != SIGSYS) {
        emulateSuccess(context);
        return;
    }

    struct sigaction current = {};
    loadVirtualSigsysAction(&current);

    auto new_handler = reinterpret_cast<__sighandler_t>(getSyscallArg(context, 1));
    struct sigaction next = current;
    memset(&next, 0, sizeof(next));
    next.sa_handler = new_handler;
    sigemptyset(&next.sa_mask);
    next.sa_flags = 0;
    next.sa_restorer = nullptr;
    storeVirtualSigsysAction(next);

    context->uc_mcontext.arm_r0 = reinterpret_cast<unsigned long>(current.sa_handler);
    __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                        "virtualized signal(SIGSYS): handler=%p si_code=%d",
                        reinterpret_cast<void *>(reinterpret_cast<uintptr_t>(new_handler)),
                        info != nullptr ? info->si_code : 0);
}
#endif

static void collectRawFramesInHandler(TrapEvent *event) {
    event->frame_count = 0;
    HandlerBacktraceState state = {event};
    _Unwind_Backtrace(unwindCallback, &state);
}

static void logTrapEvent(const TrapEvent &event) {
    __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                        "seccomp trap pid=%d tid=%d sysno=%d si_code=%d si_errno=%d si_call_addr=%p pc=%p sp=%p lr=%p fp=%p",
                        event.pid, event.tid, event.sysno, event.si_code, event.si_errno,
                        reinterpret_cast<void *>(event.trap_call_addr),
                        reinterpret_cast<void *>(event.pc),
                        reinterpret_cast<void *>(event.sp),
                        reinterpret_cast<void *>(event.lr),
                        reinterpret_cast<void *>(event.fp));
    __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                        "regs a0=%p a1=%p a2=%p a3=%p sc=%p flags=0x%x",
                        reinterpret_cast<void *>(event.regs[0]),
                        reinterpret_cast<void *>(event.regs[1]),
                        reinterpret_cast<void *>(event.regs[2]),
                        reinterpret_cast<void *>(event.regs[3]),
                        reinterpret_cast<void *>(event.regs[7]),
                        event.flags);
    for (uint32_t i = 0; i < event.frame_count; ++i) {
        __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                            "  frame[%u]=%p", i, reinterpret_cast<void *>(event.frames[i]));
    }
}

static void resetTrapEvent(TrapEvent *event) {
    event->frame_count = 0;
    event->state.store(kSlotFree);
}

static TrapEvent *acquireTrapEvent(uint32_t *slot_index) {
    for (uint32_t i = 0; i < kMaxTrapEvents; ++i) {
        uint32_t expected = kSlotFree;
        if (gTrapEvents[i].state.compare_exchange_strong(expected, kSlotWriting)) {
            *slot_index = i;
            return &gTrapEvents[i];
        }
    }
    return nullptr;
}

static void *dumperThreadMain(void *) {
    for (;;) {
        uint32_t slot_index = UINT32_MAX;
        ssize_t read_bytes = read(gTrapPipe[0], &slot_index, sizeof(slot_index));
        if (read_bytes < 0) {
            if (errno == EINTR) {
                continue;
            }
            __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                                "seccomp dumper read failed: errno=%d (%s)", errno, strerror(errno));
            continue;
        }
        if (read_bytes != sizeof(slot_index) || slot_index >= kMaxTrapEvents) {
            continue;
        }

        TrapEvent &event = gTrapEvents[slot_index];
        uint32_t expected = kSlotReady;
        if (event.state.compare_exchange_strong(expected, kSlotProcessing)) {
            logTrapEvent(event);
            uint32_t previous = event.state.exchange(kSlotDone);
            if (previous == kSlotTimedOut) {
                resetTrapEvent(&event);
                continue;
            }
            futexWake(&event.state);
            continue;
        }

        if (event.state.load() == kSlotTimedOut) {
            logTrapEvent(event);
            resetTrapEvent(&event);
        }
    }
    return nullptr;
}

static bool ensureDumperStarted() {
    bool expected = false;
    if (!gDumperStarted.compare_exchange_strong(expected, true)) {
        return true;
    }

    if (pipe2(gTrapPipe, O_CLOEXEC) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                            "pipe2 failed: errno=%d (%s)", errno, strerror(errno));
        gDumperStarted.store(false);
        return false;
    }

    if (pthread_create(&gDumperThread, nullptr, dumperThreadMain, nullptr) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                            "pthread_create failed for seccomp dumper");
        close(gTrapPipe[0]);
        close(gTrapPipe[1]);
        gTrapPipe[0] = -1;
        gTrapPipe[1] = -1;
        gDumperStarted.store(false);
        return false;
    }

    pthread_setname_np(gDumperThread, "bb-seccomp");
    return true;
}

static void *sigsysWatchdogMain(void *) {
    const time_t started_at = time(nullptr);
    uint64_t last_canary_ms = 0;
    for (;;) {
        scanThreadSignalMasks();
        scanTracerPid();
        scanProcessSignalDispositions();

        const time_t now = time(nullptr);
        const bool in_burst = started_at != static_cast<time_t>(-1)
                              && now != static_cast<time_t>(-1)
                              && (now - started_at) < kSigsysWatchdogBurstDurationSec;
        if (in_burst) {
            const uint64_t now_ms = monotonicMs();
            if (now_ms != 0 && now_ms - last_canary_ms >= 50) {
                gSigsysCanaryArmed = true;
                syscall(__NR_tgkill, getpid(), static_cast<pid_t>(getThreadId()), SIGSYS);
                gSigsysCanaryArmed = false;
                last_canary_ms = now_ms;
            }
        }

        const long poll_ns = in_burst ? kSigsysWatchdogBurstPollNs : kSigsysWatchdogPollNs;
        const struct timespec poll = {0, poll_ns};
        while (nanosleep(&poll, nullptr) != 0 && errno == EINTR) {
        }
    }
}

static bool isSigsysWatchdogDiagnosticsEnabled() {
    const char *value = getenv("BLACKBOX_SECCOMP_WATCHDOG");
    return value != nullptr
           && (strcmp(value, "1") == 0
               || strcmp(value, "true") == 0
               || strcmp(value, "TRUE") == 0
               || strcmp(value, "yes") == 0
               || strcmp(value, "YES") == 0);
}

static bool ensureSigsysWatchdogStarted() {
    bool expected = false;
    if (!gSigsysWatchdogStarted.compare_exchange_strong(expected, true)) {
        return true;
    }

    if (pthread_create(&gSigsysWatchdogThread, nullptr, sigsysWatchdogMain, nullptr) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                            "pthread_create failed for SIGSYS watchdog");
        gSigsysWatchdogStarted.store(false);
        return false;
    }

    pthread_setname_np(gSigsysWatchdogThread, "bb-sigsys");
    return true;
}

static void sigsysHandler(int signo, siginfo_t *info, void *context_raw) {
    if (signo != SIGSYS || context_raw == nullptr) {
        return;
    }

#if defined(__aarch64__) || defined(__arm__)
    if (gSigsysCanaryArmed && (info == nullptr || info->si_code != SYS_SECCOMP)) {
        const uint32_t sequence = gSigsysCanarySeq.fetch_add(1, std::memory_order_relaxed) + 1;
        __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                            "SIGSYS canary delivered seq=%u tid=%ld si_code=%d",
                            sequence, getThreadId(), info != nullptr ? info->si_code : 0);
        return;
    }

    if (info == nullptr || info->si_code != SYS_SECCOMP) {
        forwardVirtualSigsys(signo, info, context_raw);
        return;
    }

    ucontext_t *context = reinterpret_cast<ucontext_t *>(context_raw);
    int sysno = getSyscallNumber(context);

    if (isTrappedSigsysCanarySignalSend(sysno, context)) {
        const uint32_t sequence = gSigsysCanarySeq.fetch_add(1, std::memory_order_relaxed) + 1;
        __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                            "SIGSYS canary trapped seq=%u tid=%ld sysno=%d si_code=%d",
                            sequence, getThreadId(), sysno, info != nullptr ? info->si_code : 0);
        emulateSuccess(context);
        return;
    }

    logTrapBreadcrumb(info, context, sysno);

    if (sysno == kSysRtSigaction
#if defined(__arm__)
        || sysno == kSysSigaction
#endif
    ) {
        emulateVirtualSigaction(info, context);
        return;
    }

#if defined(__arm__)
    if (sysno == kSysSignal) {
        emulateVirtualSignal(info, context);
        return;
    }
#endif

    if (!isProtectedSyscall(sysno)) {
        emulateSuccess(context);
        return;
    }

    uint32_t slot_index = UINT32_MAX;
    TrapEvent *event = acquireTrapEvent(&slot_index);
    if (event == nullptr || gTrapPipe[1] < 0) {
        if (isProcessExitSyscall(sysno)) {
            emulateBlockedProcessExitReturn(context);
        } else {
            emulateSuccess(context);
        }
        return;
    }

    fillTrapEvent(event, info, context);
    collectRawFramesInHandler(event);
    event->state.store(kSlotReady);

    if (writeExact(gTrapPipe[1], &slot_index, sizeof(slot_index)) != sizeof(slot_index)) {
        resetTrapEvent(event);
        if (isProcessExitSyscall(sysno)) {
            emulateBlockedProcessExitReturn(context);
        } else {
            emulateSuccess(context);
        }
        return;
    }

    const struct timespec timeout = {kTrapWaitTimeoutSec, 0};
    while (event->state.load() != kSlotDone) {
        uint32_t state = event->state.load();
        if (state == kSlotReady || state == kSlotProcessing) {
            if (futexTimedWait(&event->state, state, &timeout) == -1 && errno == ETIMEDOUT) {
                uint32_t current = event->state.load();
                if (current == kSlotReady || current == kSlotProcessing) {
                    event->state.store(kSlotTimedOut);
                }
                break;
            }
            continue;
        }
        break;
    }

    if (isProcessExitSyscall(sysno)) {
        emulateBlockedProcessExitReturn(context);
    } else {
        emulateSuccess(context);
    }
    if (event->state.load() == kSlotDone) {
        resetTrapEvent(event);
    }
#endif
}

static bool installSignalHandler() {
    struct sigaction action = {};
    action.sa_sigaction = sigsysHandler;
    action.sa_flags = SA_SIGINFO | SA_NODEFER;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGSYS, &action, nullptr) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                            "sigaction(SIGSYS) failed: errno=%d (%s)", errno, strerror(errno));
        return false;
    }
    gExpectedSigsysAction = action;
    initializeVirtualSigsysAction();
    struct sigaction current = {};
    if (querySigsysAction(&current)) {
        logSigsysAction("SIGSYS installed", current);
    }
    return true;
}

static bool installFilter() {
#if defined(__aarch64__) || defined(__arm__)
    struct sock_filter filter[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, static_cast<uint32_t>(offsetof(struct seccomp_data, arch))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kAuditArch, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),

            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, static_cast<uint32_t>(offsetof(struct seccomp_data, nr))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysRtSigaction, 0, 3),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[0]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGSYS, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnTrap),
#if defined(__arm__)
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysSignal, 0, 3),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[0]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGSYS, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnTrap),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysSigaction, 0, 3),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[0]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGSYS, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnTrap),
#endif

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_prctl, 0, 3),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[0]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, PR_SET_SECCOMP, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnSuccess),

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_seccomp, 0, 3),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[0]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SECCOMP_SET_MODE_FILTER, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnSuccess),

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysKill, 0, 6),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[1]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 3, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGTERM, 2, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGABRT, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnSuccess),

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysTkill, 0, 6),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[1]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 3, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGTERM, 2, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGABRT, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnSuccess),

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysTgkill, 0, 6),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[2]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 3, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGTERM, 2, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGABRT, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnSuccess),

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysRtSigqueueinfo, 0, 6),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[1]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 3, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGTERM, 2, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGABRT, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnSuccess),

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysRtTgsigqueueinfo, 0, 6),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[2]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 3, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGTERM, 2, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGABRT, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnSuccess),

            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };

    struct sock_fprog program = {};
    program.len = static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0]));
    program.filter = filter;

    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                            "prctl(PR_SET_NO_NEW_PRIVS) failed: errno=%d (%s)", errno, strerror(errno));
        return false;
    }

    long tsync_result = syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &program);
    if (tsync_result == 0) {
        __android_log_print(ANDROID_LOG_DEBUG, kSeccompTag,
                            "seccomp TSYNC filter installed for all current threads");
        return true;
    }

    const int tsync_errno = errno;
    __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                        "seccomp TSYNC install failed: ret=%ld errno=%d (%s), falling back to prctl",
                        tsync_result, tsync_errno, strerror(tsync_errno));

    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                            "prctl(PR_SET_SECCOMP) fallback failed: errno=%d (%s)", errno, strerror(errno));
        return false;
    }

    __android_log_print(ANDROID_LOG_DEBUG, kSeccompTag,
                        "seccomp filter installed through prctl fallback on current thread only");
    return true;
#else
    __android_log_print(ANDROID_LOG_WARN, kSeccompTag,
                        "seccomp shield is unsupported on this ABI");
    return false;
#endif
}

static bool installTerminationOnlyFilter() {
#if defined(__aarch64__) || defined(__arm__)
    struct sock_filter filter[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, static_cast<uint32_t>(offsetof(struct seccomp_data, arch))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kAuditArch, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),

            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, static_cast<uint32_t>(offsetof(struct seccomp_data, nr))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysExit, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnSuccess),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysExitGroup, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnSuccess),

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysKill, 0, 6),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[1]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 3, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGTERM, 2, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGABRT, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnSuccess),

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysTkill, 0, 6),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[1]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 3, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGTERM, 2, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGABRT, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnSuccess),

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysTgkill, 0, 6),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[2]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 3, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGTERM, 2, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGABRT, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnSuccess),

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysRtSigqueueinfo, 0, 6),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[1]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 3, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGTERM, 2, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGABRT, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnSuccess),

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysRtTgsigqueueinfo, 0, 6),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[2]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 3, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGTERM, 2, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGABRT, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnSuccess),

            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };

    struct sock_fprog program = {};
    program.len = static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0]));
    program.filter = filter;

    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                            "prctl(PR_SET_NO_NEW_PRIVS) for termination-only filter failed: errno=%d (%s)",
                            errno, strerror(errno));
        return false;
    }

    long tsync_result = syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &program);
    if (tsync_result == 0) {
        __android_log_print(ANDROID_LOG_DEBUG, kSeccompTag,
                            "termination-only seccomp TSYNC filter installed for all current threads");
        return true;
    }

    const int tsync_errno = errno;
    __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                        "termination-only seccomp TSYNC install failed: ret=%ld errno=%d (%s), falling back to prctl",
                        tsync_result, tsync_errno, strerror(tsync_errno));

    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                            "termination-only prctl(PR_SET_SECCOMP) fallback failed: errno=%d (%s)",
                            errno, strerror(errno));
        return false;
    }

    __android_log_print(ANDROID_LOG_DEBUG, kSeccompTag,
                        "termination-only seccomp filter installed through prctl fallback on current thread only");
    return true;
#else
    __android_log_print(ANDROID_LOG_WARN, kSeccompTag,
                        "termination-only seccomp shield is unsupported on this ABI");
    return false;
#endif
}

static bool installTerminationTrapFilter() {
#if defined(__aarch64__) || defined(__arm__)
    struct sock_filter filter[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, static_cast<uint32_t>(offsetof(struct seccomp_data, arch))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kAuditArch, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),

            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, static_cast<uint32_t>(offsetof(struct seccomp_data, nr))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysRtSigaction, 0, 3),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[0]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGSYS, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnTrap),
#if defined(__arm__)
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysSignal, 0, 3),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[0]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGSYS, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnTrap),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysSigaction, 0, 3),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[0]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGSYS, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnTrap),
#endif

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_prctl, 0, 3),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[0]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, PR_SET_SECCOMP, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnSuccess),

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_seccomp, 0, 3),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[0]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SECCOMP_SET_MODE_FILTER, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnSuccess),

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysExit, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnTrap),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysExitGroup, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnTrap),

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysKill, 0, 6),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[1]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 3, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGTERM, 2, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGABRT, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnTrap),

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysTkill, 0, 6),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[1]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 3, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGTERM, 2, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGABRT, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnTrap),

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysTgkill, 0, 6),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[2]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 3, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGTERM, 2, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGABRT, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnTrap),

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysRtSigqueueinfo, 0, 6),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[1]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 3, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGTERM, 2, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGABRT, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnTrap),

            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysRtTgsigqueueinfo, 0, 6),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                     static_cast<uint32_t>(offsetof(struct seccomp_data, args[2]))),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 3, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGTERM, 2, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGABRT, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET | BPF_K, kSeccompReturnTrap),

            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };

    struct sock_fprog program = {};
    program.len = static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0]));
    program.filter = filter;

    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                            "termination-trap prctl(PR_SET_NO_NEW_PRIVS) failed: errno=%d (%s)",
                            errno, strerror(errno));
        return false;
    }

    long tsync_result = syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &program);
    if (tsync_result == 0) {
        __android_log_print(ANDROID_LOG_DEBUG, kSeccompTag,
                            "termination-trap seccomp TSYNC filter installed for all current threads");
        return true;
    }

    const int tsync_errno = errno;
    __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                        "termination-trap seccomp TSYNC install failed: ret=%ld errno=%d (%s), falling back to prctl",
                        tsync_result, tsync_errno, strerror(tsync_errno));

    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kSeccompTag,
                            "termination-trap prctl(PR_SET_SECCOMP) fallback failed: errno=%d (%s)",
                            errno, strerror(errno));
        return false;
    }

    __android_log_print(ANDROID_LOG_DEBUG, kSeccompTag,
                        "termination-trap seccomp filter installed through prctl fallback on current thread only");
    return true;
#else
    __android_log_print(ANDROID_LOG_WARN, kSeccompTag,
                        "termination-trap seccomp shield is unsupported on this ABI");
    return false;
#endif
}

} // namespace

void ensureSignalMaskHooksReady() {
    pthread_once(&gSignalMaskHookOnce, initializeSignalMaskHooks);
}

SigprocmaskFn getOriginalSigprocmask() {
    return gOrigSigprocmask;
}

SigprocmaskFn getOriginalPthreadSigmask() {
    return gOrigPthreadSigmask;
}

RtSigprocmaskFn getOriginalRtSigprocmask() {
    return gOrigRtSigprocmask;
}

RtSigprocmaskFn getOriginalInternalRtSigprocmask() {
    return gOrigInternalRtSigprocmask;
}

const sigset_t *sanitizeSignalMaskForHook(const char *api_name, int how,
                                          const sigset_t *set, sigset_t *sanitized) {
    return sanitizeSignalMaskArgument(api_name, how, set, sanitized);
}

void enterSignalMaskHook() {
    ++gSignalMaskHookDepth;
}

void leaveSignalMaskHook() {
    --gSignalMaskHookDepth;
}

void installSeccompShield() {
    bool expected = false;
    if (!gSeccompInstalled.compare_exchange_strong(expected, true)) {
        __android_log_print(ANDROID_LOG_DEBUG, kSeccompTag,
                            "seccomp shield already installed");
        return;
    }

    if (gKernelUid.load(std::memory_order_relaxed) <= 0) {
        gKernelUid.store(static_cast<int>(getuid()), std::memory_order_relaxed);
    }

    if (!ensureDumperStarted() || !installSignalHandler() || !installFilter()) {
        gSeccompInstalled.store(false);
        return;
    }
    gSeccompInstallMonotonicMs.store(monotonicMs(), std::memory_order_relaxed);
    ensureSignalMaskHooksReady();

    if (isSigsysWatchdogDiagnosticsEnabled()) {
        if (!ensureSigsysWatchdogStarted()) {
            gSeccompInstalled.store(false);
            return;
        }
    }

    __android_log_print(ANDROID_LOG_DEBUG, kSeccompTag,
                        "seccomp shield installed");
}

void installTerminationOnlySeccompShield() {
    if (gSeccompInstalled.load(std::memory_order_acquire)) {
        __android_log_print(ANDROID_LOG_DEBUG, kSeccompTag,
                            "termination-only seccomp skipped because full shield is already installed");
        return;
    }

    bool expected = false;
    if (!gTerminationOnlySeccompInstalled.compare_exchange_strong(expected, true)) {
        __android_log_print(ANDROID_LOG_DEBUG, kSeccompTag,
                            "termination-only seccomp shield already installed");
        return;
    }

    if (gKernelUid.load(std::memory_order_relaxed) <= 0) {
        gKernelUid.store(static_cast<int>(getuid()), std::memory_order_relaxed);
    }

    if (!installTerminationOnlyFilter()) {
        gTerminationOnlySeccompInstalled.store(false);
        return;
    }

    __android_log_print(ANDROID_LOG_DEBUG, kSeccompTag,
                        "termination-only seccomp shield installed");
}

void installTerminationTrapSeccompShield() {
    if (gSeccompInstalled.load(std::memory_order_acquire)) {
        __android_log_print(ANDROID_LOG_DEBUG, kSeccompTag,
                            "termination-trap seccomp skipped because full shield is already installed");
        return;
    }
    if (gTerminationOnlySeccompInstalled.load(std::memory_order_acquire)) {
        __android_log_print(ANDROID_LOG_DEBUG, kSeccompTag,
                            "termination-trap seccomp skipped because termination-only shield is already installed");
        return;
    }

    bool expected = false;
    if (!gTerminationTrapSeccompInstalled.compare_exchange_strong(expected, true)) {
        __android_log_print(ANDROID_LOG_DEBUG, kSeccompTag,
                            "termination-trap seccomp shield already installed");
        return;
    }

    if (gKernelUid.load(std::memory_order_relaxed) <= 0) {
        gKernelUid.store(static_cast<int>(getuid()), std::memory_order_relaxed);
    }

    if (!ensureDumperStarted() || !installSignalHandler() || !installTerminationTrapFilter()) {
        gTerminationTrapSeccompInstalled.store(false);
        return;
    }
    gSeccompInstallMonotonicMs.store(monotonicMs(), std::memory_order_relaxed);
    ensureSignalMaskHooksReady();

    __android_log_print(ANDROID_LOG_DEBUG, kSeccompTag,
                        "termination-trap seccomp shield installed");
}

void setVirtualUid(int virtual_uid) {
    gKernelUid.store(static_cast<int>(getuid()), std::memory_order_relaxed);
    gVirtualUid.store(virtual_uid, std::memory_order_relaxed);
    __android_log_print(ANDROID_LOG_DEBUG, kSeccompTag,
                        "virtual uid configured virtualUid=%d kernelUid=%d",
                        virtual_uid,
                        gKernelUid.load(std::memory_order_relaxed));
}

} // namespace seccomp
} // namespace blackbox

extern "C" __attribute__((visibility("default")))
int sigprocmask(int how, const sigset_t *set, sigset_t *oldset) {
    blackbox::seccomp::ensureSignalMaskHooksReady();
    auto orig = blackbox::seccomp::getOriginalSigprocmask();
    if (orig == nullptr) {
        errno = ENOSYS;
        return -1;
    }

    sigset_t sanitized = {};
    const sigset_t *forward = blackbox::seccomp::sanitizeSignalMaskForHook("sigprocmask", how, set, &sanitized);
    blackbox::seccomp::enterSignalMaskHook();
    const int result = orig(how, forward, oldset);
    blackbox::seccomp::leaveSignalMaskHook();
    return result;
}

extern "C" __attribute__((visibility("default")))
int pthread_sigmask(int how, const sigset_t *set, sigset_t *oldset) {
    blackbox::seccomp::ensureSignalMaskHooksReady();
    auto orig = blackbox::seccomp::getOriginalPthreadSigmask();
    if (orig == nullptr) {
        return EINVAL;
    }

    sigset_t sanitized = {};
    const sigset_t *forward = blackbox::seccomp::sanitizeSignalMaskForHook("pthread_sigmask", how, set, &sanitized);
    blackbox::seccomp::enterSignalMaskHook();
    const int result = orig(how, forward, oldset);
    blackbox::seccomp::leaveSignalMaskHook();
    return result;
}

extern "C" __attribute__((visibility("default")))
int rt_sigprocmask(int how, const sigset_t *set, sigset_t *oldset, size_t sigsetsize) {
    blackbox::seccomp::ensureSignalMaskHooksReady();
    auto orig = blackbox::seccomp::getOriginalRtSigprocmask();
    if (orig == nullptr) {
        errno = ENOSYS;
        return -1;
    }

    sigset_t sanitized = {};
    const sigset_t *forward = blackbox::seccomp::sanitizeSignalMaskForHook("rt_sigprocmask", how, set, &sanitized);
    blackbox::seccomp::enterSignalMaskHook();
    const int result = orig(how, forward, oldset, sigsetsize);
    blackbox::seccomp::leaveSignalMaskHook();
    return result;
}

extern "C" __attribute__((visibility("default")))
int __rt_sigprocmask(int how, const sigset_t *set, sigset_t *oldset, size_t sigsetsize) {
    blackbox::seccomp::ensureSignalMaskHooksReady();
    auto orig = blackbox::seccomp::getOriginalInternalRtSigprocmask() != nullptr
                ? blackbox::seccomp::getOriginalInternalRtSigprocmask()
                : blackbox::seccomp::getOriginalRtSigprocmask();
    if (orig == nullptr) {
        errno = ENOSYS;
        return -1;
    }

    sigset_t sanitized = {};
    const sigset_t *forward = blackbox::seccomp::sanitizeSignalMaskForHook("__rt_sigprocmask", how, set, &sanitized);
    blackbox::seccomp::enterSignalMaskHook();
    const int result = orig(how, forward, oldset, sigsetsize);
    blackbox::seccomp::leaveSignalMaskHook();
    return result;
}
