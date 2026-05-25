//
// Created by Codex on 5/16/26.
//

#include <android/dlext.h>
#include <dlfcn.h>
#include <dirent.h>
#include <errno.h>
#include <elf.h>
#include <fcntl.h>
#include <pthread.h>
#include <linux/stat.h>
#include <limits.h>
#include <link.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sched.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/ucontext.h>
#include <sys/types.h>
#include <sys/vfs.h>
#include <time.h>
#include <unistd.h>
#include <unwind.h>

#include <string>

#include "../Log.h"
#include "../RawSyscallTerminationProbe.h"
#include "Utils/NativeProperty.h"
#include "IO.h"

#if defined(__LP64__)
#define BB_ELF_R_SYM ELF64_R_SYM
#else
#define BB_ELF_R_SYM ELF32_R_SYM
#endif

extern "C" void refreshProtectedProcMapsShim();
extern "C" bool isProtectedProcMapsShimReady();
extern "C" void installNativeFileHooks();

namespace {

typedef int (*OpenFn)(const char *pathname, int flags, ...);
typedef int (*Open2Fn)(const char *pathname, int flags);
typedef int (*OpenAtFn)(int dirfd, const char *pathname, int flags, ...);
typedef int (*OpenAt2Fn)(int dirfd, const char *pathname, int flags);
typedef FILE *(*FopenFn)(const char *pathname, const char *mode);
typedef long (*SyscallFn)(long number, ...);
typedef void *(*DlopenFn)(const char *filename, int flags);
typedef void *(*AndroidDlopenExtFn)(const char *filename, int flags, const android_dlextinfo *info);
typedef void *(*DlsymFn)(void *handle, const char *symbol);
typedef int (*DladdrFn)(const void *addr, Dl_info *info);
typedef int (*AccessFn)(const char *pathname, int mode);
typedef int (*FaccessatFn)(int dirfd, const char *pathname, int mode, int flags);
typedef int (*MkdirFn)(const char *pathname, mode_t mode);
typedef int (*MkdirAtFn)(int dirfd, const char *pathname, mode_t mode);
typedef int (*StatFn)(const char *pathname, struct stat *buf);
typedef int (*FstatFn)(int fd, struct stat *buf);
typedef int (*FstatatFn)(int dirfd, const char *pathname, struct stat *buf, int flags);
typedef int (*StatxFn)(int dirfd, const char *pathname, int flags, unsigned int mask, struct statx *buf);
typedef int (*StatfsFn)(const char *pathname, struct statfs *buf);
typedef int (*Statfs64Fn)(const char *pathname, struct statfs64 *buf);
typedef ssize_t (*ReadlinkFn)(const char *pathname, char *buf, size_t bufsiz);
typedef ssize_t (*ReadlinkChkFn)(const char *pathname, char *buf, size_t bufsiz, size_t buf_size);
typedef ssize_t (*ReadlinkAtFn)(int dirfd, const char *pathname, char *buf, size_t bufsiz);
typedef ssize_t (*ReadlinkAtChkFn)(int dirfd, const char *pathname, char *buf, size_t bufsiz, size_t buf_size);
typedef uid_t (*GetUidFn)(void);
typedef gid_t (*GetGidFn)(void);
typedef int (*GetGroupsFn)(int size, gid_t *list);
typedef char *(*RealpathFn)(const char *pathname, char *resolved);
typedef DIR *(*OpendirFn)(const char *pathname);
typedef int (*KillFn)(pid_t pid, int signal);
typedef int (*TgkillFn)(int tgid, int tid, int signal);
typedef int (*RaiseFn)(int signal);
typedef void (*AbortFn)();
typedef void (*ExitFn)(int status);
typedef bool (*PineNativeInlineHookSymbolNoBackupFn)(const char *elf, const char *symbol,
                                                     void *replace);
typedef void (*PineNativeInlineHookFuncNoBackupFn)(void *target, void *replace);
typedef pid_t (*ForkFn)(void);
typedef pid_t (*VforkFn)(void);
typedef int (*CloneFn)(int (*fn)(void *), void *child_stack, int flags, void *arg, ...);
typedef int (*ExecveFn)(const char *pathname, char *const argv[], char *const envp[]);
typedef int (*PthreadCreateFn)(pthread_t *thread, const pthread_attr_t *attr,
                               void *(*start_routine)(void *), void *arg);
#if !defined(__LP64__) && (!defined(__ANDROID_API__) || __ANDROID_API__ < 21)
typedef int (*Stat64Fn)(const char *pathname, struct stat64 *buf);
typedef int (*Fstat64Fn)(int fd, struct stat64 *buf);
#endif

OpenFn gOrigOpen = nullptr;
OpenFn gOrigOpen64 = nullptr;
Open2Fn gOrigOpen2 = nullptr;
OpenAtFn gOrigOpenAt = nullptr;
OpenAt2Fn gOrigOpenAt2 = nullptr;
FopenFn gOrigFopen = nullptr;
FopenFn gOrigFopen64 = nullptr;
SyscallFn gOrigSyscall = nullptr;
DlopenFn gOrigDlopen = nullptr;
AndroidDlopenExtFn gOrigAndroidDlopenExt = nullptr;
DlsymFn gOrigDlsym = nullptr;
DladdrFn gOrigDladdr = nullptr;
AccessFn gOrigAccess = nullptr;
FaccessatFn gOrigFaccessat = nullptr;
MkdirFn gOrigMkdir = nullptr;
MkdirAtFn gOrigMkdirAt = nullptr;
StatFn gOrigStat = nullptr;
StatFn gOrigLstat = nullptr;
FstatFn gOrigFstat = nullptr;
FstatatFn gOrigFstatat = nullptr;
StatxFn gOrigStatx = nullptr;
StatfsFn gOrigStatfs = nullptr;
Statfs64Fn gOrigStatfs64 = nullptr;
ReadlinkFn gOrigReadlink = nullptr;
ReadlinkChkFn gOrigReadlinkChk = nullptr;
ReadlinkAtFn gOrigReadlinkAt = nullptr;
ReadlinkAtChkFn gOrigReadlinkAtChk = nullptr;
GetUidFn gOrigGetUid = nullptr;
GetUidFn gOrigGetEuid = nullptr;
GetGidFn gOrigGetGid = nullptr;
GetGidFn gOrigGetEgid = nullptr;
GetGroupsFn gOrigGetGroups = nullptr;
RealpathFn gOrigRealpath = nullptr;
OpendirFn gOrigOpendir = nullptr;
KillFn gOrigKill = nullptr;
KillFn gOrigTkill = nullptr;
TgkillFn gOrigTgkill = nullptr;
RaiseFn gOrigRaise = nullptr;
AbortFn gOrigAbort = nullptr;
ExitFn gOrigExit = nullptr;
ExitFn gOrigUnderscoreExit = nullptr;
ExitFn gOrigCapitalExit = nullptr;
ForkFn gOrigFork = nullptr;
VforkFn gOrigVfork = nullptr;
CloneFn gOrigClone = nullptr;
ExecveFn gOrigExecve = nullptr;
PthreadCreateFn gOrigPthreadCreate = nullptr;
#if !defined(__LP64__) && (!defined(__ANDROID_API__) || __ANDROID_API__ < 21)
Stat64Fn gOrigStat64 = nullptr;
Stat64Fn gOrigLstat64 = nullptr;
Fstat64Fn gOrigFstat64 = nullptr;
#endif
__thread char gSanitizedDladdrPath[PATH_MAX];
char gNativeTerminationShieldPackage[128] = {};
char gNativeSandboxProcessName[128] = {};
bool gNativeTerminationBlockingEnabled = false;
pid_t gNativeTerminationShieldRootPid = 0;
pid_t gNativeTerminationShieldRootPgid = 0;
char gEarlyProcMapsPackage[128] = {};
bool gEarlyProcMapsReady = false;
__thread bool gRefreshingEarlyProcMapsShim = false;
__thread int gInternalFileProbeDepth = 0;
bool gNativeFileHooksInstalled = false;
bool gNativeFileHooksInstalling = false;
bool gDirectLibcTerminationHooksInstalled = false;
bool gDirectLibcTerminationHooksInstalling = false;
bool gDirectLibcProcMapsHooksInstalled = false;
bool gDirectLibcProcMapsHooksInstalling = false;
bool gDirectLibcMetadataHooksInstalled = false;
bool gDirectLibcMetadataHooksInstalling = false;
bool gDirectLibcPthreadCreateHookInstalled = false;
bool gDirectLibcPthreadCreateHookInstalling = false;
bool gNativeCrashProbeInstalled = false;
int gNativeVirtualUid = -1;
int gNativeHostUid = -1;
int gNativeHostGid = -1;
pthread_mutex_t gAppOwnedNativeThreadsLock = PTHREAD_MUTEX_INITIALIZER;
constexpr size_t kMaxAppOwnedNativeThreads = 128;
pthread_t gAppOwnedNativeThreads[kMaxAppOwnedNativeThreads] = {};
size_t gAppOwnedNativeThreadCount = 0;
pthread_mutex_t gRecentNativeFileProbeLock = PTHREAD_MUTEX_INITIALIZER;
constexpr size_t kRecentNativeFileProbeCount = 32;
constexpr size_t kRecentNativeFileProbeDumpCount = 8;
struct RecentNativeFileProbe {
    bool valid = false;
    uint64_t sequence = 0;
    uint64_t ts_ns = 0;
    pid_t tid = 0;
    char api[32] = {};
    char path[PATH_MAX] = {};
    char redirected[PATH_MAX] = {};
    int flags = 0;
    long result = 0;
    int result_errno = 0;
    void *caller = nullptr;
    uintptr_t caller_offset = 0;
    char caller_map[PATH_MAX] = {};
};
RecentNativeFileProbe gRecentNativeFileProbes[kRecentNativeFileProbeCount] = {};
uint64_t gRecentNativeFileProbeSequence = 0;
uint64_t gAppNativeLoaderMapsTrustUntilNs = 0;
constexpr uint64_t kAppNativeLoaderMapsTrustWindowNs = 20ULL * 1000ULL * 1000ULL * 1000ULL;

constexpr int kProcShimFdStart = 90;
constexpr int kProcShimFdEnd = 94;
constexpr int kProcCommFd = 90;
constexpr int kProcCmdlineFd = 91;
constexpr int kProcMeminfoFd = 92;
constexpr int kProcMapsFd = 93;
constexpr int kProcVersionFd = 94;
static const char *kProcCommFdPath = "/dev/fd/90";
static const char *kProcCmdlineFdPath = "/dev/fd/91";
static const char *kProcMeminfoFdPath = "/dev/fd/92";
static const char *kProcMapsFdPath = "/dev/fd/93";
static const char *kProcVersionFdPath = "/dev/fd/94";
static const char *kBlackBoxHostPackagePrefix = "top.niunaijun.blackbox";
static const char *kProcShimProperty = "debug.blackbox.proc_shim";
static const char *kProcMapsPathSanitizeProperty = "debug.blackbox.maps_path_sanitize";
static const char *kTransientProcMapsProperty = "debug.blackbox.transient_maps";
static const char *kRawProcVirtualizationProperty = "debug.blackbox.raw_proc_virtual";
static const char *kRawSyscallThreadRefreshProperty = "debug.blackbox.raw_syscall_thread_refresh";
static const char *kProcessProbeProperty = "debug.blackbox.process_probe";
static const char *kFileProbeProperty = "debug.blackbox.file_probe";
static const char *kTerminationProbeProperty = "debug.blackbox.termination_probe";
static const char *kTerminationMemoryDumpProperty = "debug.blackbox.termination_memdump";
static const char *kNativeCrashProbeProperty = "debug.blackbox.native_crash_probe";
static const char *kDlopenProbeProperty = "debug.blackbox.dlopen_probe";
static const char *kEarlyDlopenRepatchProperty = "debug.blackbox.early_dlopen_repatch";
static const char *kDlsymProbeProperty = "debug.blackbox.dlsym_probe";
static const char *kDlsymReplacementProperty = "debug.blackbox.dlsym_replace";
constexpr int kSignalAbort = SIGABRT;
constexpr int kSignalKill = SIGKILL;
constexpr int kSignalTerm = SIGTERM;
constexpr size_t kLinuxTaskCommMaxBytes = 15;
constexpr size_t kTerminationProbeMaxFrames = 16;
constexpr size_t kProcessProbeMaxFrames = 16;
constexpr size_t kTerminationMemoryDumpMaxBytes = 2 * 1024 * 1024;
constexpr size_t kTerminationStackDumpMaxBytes = 16 * 1024;
constexpr size_t kTerminationAdjacentDumpMaxMaps = 4;
constexpr size_t kTerminationAdjacentDumpMaxBytes = 512 * 1024;
constexpr uintptr_t kTerminationAdjacentDumpMaxDistance = 4 * 1024 * 1024;
struct NativeCrashSignalAction {
    int signo = 0;
    bool has_previous = false;
    struct sigaction previous = {};
};
NativeCrashSignalAction gNativeCrashSignalActions[] = {
        {SIGSEGV, false, {}},
        {SIGBUS, false, {}},
        {SIGILL, false, {}},
};
__thread bool gNativeCrashProbeHandling = false;
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

struct ResolvedPath {
    const char *path = nullptr;
    char storage[PATH_MAX] = {};
};

struct NativePatchSpec {
    const char *symbol = nullptr;
    void *replacement = nullptr;
    void **original = nullptr;
    int patched = 0;
};

struct NativePatchContext {
    NativePatchSpec *specs = nullptr;
    size_t spec_count = 0;
};

class ScopedInternalFileProbe {
public:
    ScopedInternalFileProbe() {
        gInternalFileProbeDepth++;
    }

    ~ScopedInternalFileProbe() {
        if (gInternalFileProbeDepth > 0) {
            gInternalFileProbeDepth--;
        }
    }

    ScopedInternalFileProbe(const ScopedInternalFileProbe &) = delete;
    ScopedInternalFileProbe &operator=(const ScopedInternalFileProbe &) = delete;
};

bool isInternalFileProbe() {
    return gInternalFileProbeDepth > 0;
}

template<typename Fn>
Fn resolveSymbol(Fn *slot, const char *name) {
    Fn fn = *slot;
    if (fn == nullptr) {
        fn = reinterpret_cast<Fn>(dlsym(RTLD_NEXT, name));
        *slot = fn;
    }
    return fn;
}

#if defined(__arm__)
long rawKernelSyscall6(long sysno, long arg0, long arg1, long arg2,
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
#elif defined(__aarch64__)
long rawKernelSyscall6(long sysno, long arg0, long arg1, long arg2,
                       long arg3, long arg4, long arg5) {
    register long x0 __asm__("x0") = arg0;
    register long x1 __asm__("x1") = arg1;
    register long x2 __asm__("x2") = arg2;
    register long x3 __asm__("x3") = arg3;
    register long x4 __asm__("x4") = arg4;
    register long x5 __asm__("x5") = arg5;
    register long x8 __asm__("x8") = sysno;
    __asm__ volatile("svc #0"
                     : "+r"(x0)
                     : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8)
                     : "memory", "cc");
    return x0;
}
#endif

long normalizeKernelSyscallResult(long result) {
    if (result < 0 && result >= -4095) {
        errno = static_cast<int>(-result);
        return -1;
    }
    return result;
}

long callKernelSyscall(long number, long args[6]) {
#if defined(__arm__) || defined(__aarch64__)
    return normalizeKernelSyscallResult(rawKernelSyscall6(number,
                                                          args[0],
                                                          args[1],
                                                          args[2],
                                                          args[3],
                                                          args[4],
                                                          args[5]));
#else
    SyscallFn fn = resolveSymbol(&gOrigSyscall, "syscall");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    return fn(number, args[0], args[1], args[2], args[3], args[4], args[5]);
#endif
}

long callKernelSyscall(long number, long arg0 = 0, long arg1 = 0, long arg2 = 0,
                       long arg3 = 0, long arg4 = 0, long arg5 = 0) {
    long args[6] = {arg0, arg1, arg2, arg3, arg4, arg5};
    return callKernelSyscall(number, args);
}

uintptr_t dynamicPtr(uintptr_t base, uintptr_t value) {
    if (value == 0) {
        return 0;
    }
    if (base != 0 && value < base) {
        return base + value;
    }
    return value;
}

bool makeWritable(void *address) {
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) {
        return false;
    }
    uintptr_t page = reinterpret_cast<uintptr_t>(address)
                     & ~(static_cast<uintptr_t>(page_size) - 1);
    return mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(page_size),
                    PROT_READ | PROT_WRITE) == 0;
}

bool shouldSkipPatchObject(const char *name) {
    if (name == nullptr) {
        return false;
    }
    return strstr(name, "libblackbox.so") != nullptr
           || strstr(name, "libblackhook.so") != nullptr
           || strstr(name, "libblackdex.so") != nullptr
           || strstr(name, "libpine.so") != nullptr
           || strstr(name, "/libc.so") != nullptr
           || strstr(name, "/linker") != nullptr;
}

void patchSlot(uintptr_t *slot, NativePatchSpec *spec) {
    if (slot == nullptr || spec == nullptr || spec->replacement == nullptr) {
        return;
    }
    uintptr_t replacement = reinterpret_cast<uintptr_t>(spec->replacement);
    if (*slot == 0 || *slot == replacement) {
        return;
    }
    if (spec->original != nullptr && *spec->original == nullptr) {
        *spec->original = reinterpret_cast<void *>(*slot);
    }
    if (!makeWritable(slot)) {
        return;
    }
    *slot = replacement;
    __builtin___clear_cache(reinterpret_cast<char *>(slot),
                            reinterpret_cast<char *>(slot + 1));
    ++spec->patched;
}

void patchRela(uintptr_t base, ElfW(Rela) *relocations, size_t size,
               ElfW(Sym) *symtab, const char *strtab, NativePatchContext *context) {
    if (relocations == nullptr || symtab == nullptr || strtab == nullptr
        || context == nullptr || context->specs == nullptr) {
        return;
    }
    size_t count = size / sizeof(ElfW(Rela));
    for (size_t i = 0; i < count; ++i) {
        ElfW(Rela) *rel = relocations + i;
        const char *name = strtab + symtab[BB_ELF_R_SYM(rel->r_info)].st_name;
        for (size_t j = 0; j < context->spec_count; ++j) {
            NativePatchSpec *spec = context->specs + j;
            if (spec->symbol != nullptr && strcmp(name, spec->symbol) == 0) {
                patchSlot(reinterpret_cast<uintptr_t *>(base + rel->r_offset), spec);
            }
        }
    }
}

void patchRel(uintptr_t base, ElfW(Rel) *relocations, size_t size,
              ElfW(Sym) *symtab, const char *strtab, NativePatchContext *context) {
    if (relocations == nullptr || symtab == nullptr || strtab == nullptr
        || context == nullptr || context->specs == nullptr) {
        return;
    }
    size_t count = size / sizeof(ElfW(Rel));
    for (size_t i = 0; i < count; ++i) {
        ElfW(Rel) *rel = relocations + i;
        const char *name = strtab + symtab[BB_ELF_R_SYM(rel->r_info)].st_name;
        for (size_t j = 0; j < context->spec_count; ++j) {
            NativePatchSpec *spec = context->specs + j;
            if (spec->symbol != nullptr && strcmp(name, spec->symbol) == 0) {
                patchSlot(reinterpret_cast<uintptr_t *>(base + rel->r_offset), spec);
            }
        }
    }
}

int patchLoadedObject(struct dl_phdr_info *info, size_t, void *data) {
    if (info == nullptr || info->dlpi_phdr == nullptr || data == nullptr) {
        return 0;
    }
    const char *name = info->dlpi_name == nullptr ? "" : info->dlpi_name;
    if (shouldSkipPatchObject(name)) {
        return 0;
    }

    uintptr_t base = static_cast<uintptr_t>(info->dlpi_addr);
    ElfW(Dyn) *dynamic = nullptr;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) &phdr = info->dlpi_phdr[i];
        if (phdr.p_type == PT_DYNAMIC) {
            dynamic = reinterpret_cast<ElfW(Dyn) *>(base + phdr.p_vaddr);
            break;
        }
    }
    if (dynamic == nullptr) {
        return 0;
    }

    ElfW(Sym) *symtab = nullptr;
    const char *strtab = nullptr;
    ElfW(Rela) *rela = nullptr;
    ElfW(Rela) *plt_rela = nullptr;
    ElfW(Rel) *rel = nullptr;
    ElfW(Rel) *plt_rel = nullptr;
    size_t rela_size = 0;
    size_t plt_rela_size = 0;
    size_t rel_size = 0;
    size_t plt_rel_size = 0;
    int plt_type = DT_REL;

    for (ElfW(Dyn) *dyn = dynamic; dyn->d_tag != DT_NULL; ++dyn) {
        switch (dyn->d_tag) {
            case DT_SYMTAB:
                symtab = reinterpret_cast<ElfW(Sym) *>(
                        dynamicPtr(base, static_cast<uintptr_t>(dyn->d_un.d_ptr)));
                break;
            case DT_STRTAB:
                strtab = reinterpret_cast<const char *>(
                        dynamicPtr(base, static_cast<uintptr_t>(dyn->d_un.d_ptr)));
                break;
            case DT_RELA:
                rela = reinterpret_cast<ElfW(Rela) *>(
                        dynamicPtr(base, static_cast<uintptr_t>(dyn->d_un.d_ptr)));
                break;
            case DT_RELASZ:
                rela_size = static_cast<size_t>(dyn->d_un.d_val);
                break;
            case DT_REL:
                rel = reinterpret_cast<ElfW(Rel) *>(
                        dynamicPtr(base, static_cast<uintptr_t>(dyn->d_un.d_ptr)));
                break;
            case DT_RELSZ:
                rel_size = static_cast<size_t>(dyn->d_un.d_val);
                break;
            case DT_JMPREL:
                plt_rela = reinterpret_cast<ElfW(Rela) *>(
                        dynamicPtr(base, static_cast<uintptr_t>(dyn->d_un.d_ptr)));
                plt_rel = reinterpret_cast<ElfW(Rel) *>(plt_rela);
                break;
            case DT_PLTRELSZ:
                plt_rela_size = static_cast<size_t>(dyn->d_un.d_val);
                plt_rel_size = static_cast<size_t>(dyn->d_un.d_val);
                break;
            case DT_PLTREL:
                plt_type = static_cast<int>(dyn->d_un.d_val);
                break;
            default:
                break;
        }
    }

    NativePatchContext *context = reinterpret_cast<NativePatchContext *>(data);
    if (plt_type == DT_RELA) {
        patchRela(base, plt_rela, plt_rela_size, symtab, strtab, context);
    } else {
        patchRel(base, plt_rel, plt_rel_size, symtab, strtab, context);
    }
    patchRela(base, rela, rela_size, symtab, strtab, context);
    patchRel(base, rel, rel_size, symtab, strtab, context);
    return 0;
}

bool needsModeArg(int flags) {
    if ((flags & O_CREAT) != 0) {
        return true;
    }
#ifdef O_TMPFILE
    return (flags & O_TMPFILE) == O_TMPFILE;
#else
    return false;
#endif
}

bool containsPathPart(const char *path, const char *needle) {
    return path != nullptr && needle != nullptr && strstr(path, needle) != nullptr;
}

bool endsWithPathPart(const char *path, const char *suffix) {
    if (path == nullptr || suffix == nullptr) {
        return false;
    }
    size_t path_len = strlen(path);
    size_t suffix_len = strlen(suffix);
    return path_len >= suffix_len
           && strcmp(path + path_len - suffix_len, suffix) == 0;
}

bool isProcShimEnabled() {
    return blackbox::native_property::getBool(kProcShimProperty);
}

bool isTransientProcMapsEnabled() {
    return blackbox::native_property::getBool(kTransientProcMapsProperty);
}

bool isProcMapsPathSanitizationEnabled() {
    return blackbox::native_property::getBoolDefaultTrue(kProcMapsPathSanitizeProperty);
}

bool isProcessProbeEnabled() {
    return blackbox::native_property::getBool(kProcessProbeProperty);
}

bool isFileProbeEnabled() {
    return isProcessProbeEnabled()
           || blackbox::native_property::getBool(kFileProbeProperty);
}

bool isDlsymReplacementEnabled() {
    return blackbox::native_property::getBool(kDlsymReplacementProperty);
}

bool isDlsymProbeEnabled() {
    return blackbox::native_property::getBool(kDlsymProbeProperty);
}

bool shouldPatchDlsym() {
    return isDlsymProbeEnabled() || isDlsymReplacementEnabled();
}

bool shouldPatchPthreadCreate() {
    return isProcessProbeEnabled();
}

bool isDlopenProbeEnabled() {
    return blackbox::native_property::getBool(kDlopenProbeProperty);
}

bool isEarlyDlopenRepatchEnabled() {
    return blackbox::native_property::getBool(kEarlyDlopenRepatchProperty);
}

bool shouldPatchDlopen() {
    return isDlopenProbeEnabled() || isEarlyDlopenRepatchEnabled();
}

bool isTerminationProbeEnabled() {
    return blackbox::native_property::getBool(kTerminationProbeProperty);
}

bool isTerminationMemoryDumpEnabled() {
    return blackbox::native_property::getBool(kTerminationMemoryDumpProperty);
}

bool isNativeCrashProbeEnabled() {
    return blackbox::native_property::getBool(kNativeCrashProbeProperty);
}

bool isRawProcVirtualizationEnabled() {
    return blackbox::native_property::getBool(kRawProcVirtualizationProperty);
}

bool isRawSyscallThreadRefreshEnabled() {
    return blackbox::native_property::getBoolDefaultTrue(kRawSyscallThreadRefreshProperty);
}

bool isNativeSandboxEnvironmentConfigured() {
    return gNativeTerminationShieldPackage[0] != '\0';
}

bool isNativeTerminationShieldEnabled() {
    return gNativeTerminationBlockingEnabled && isNativeSandboxEnvironmentConfigured();
}

bool isNativeVirtualUidConfigured() {
    return gNativeVirtualUid >= 0;
}

uid_t virtualUid() {
    return static_cast<uid_t>(gNativeVirtualUid);
}

gid_t virtualGid() {
    return static_cast<gid_t>(gNativeVirtualUid);
}

int rawHostUid() {
    if (gNativeHostUid >= 0) {
        return gNativeHostUid;
    }
    GetUidFn fn = resolveSymbol(&gOrigGetUid, "getuid");
    if (fn == nullptr) {
        return -1;
    }
    gNativeHostUid = static_cast<int>(fn());
    return gNativeHostUid;
}

int rawHostGid() {
    if (gNativeHostGid >= 0) {
        return gNativeHostGid;
    }
    GetGidFn fn = resolveSymbol(&gOrigGetGid, "getgid");
    if (fn == nullptr) {
        return -1;
    }
    gNativeHostGid = static_cast<int>(fn());
    return gNativeHostGid;
}

int virtualAppId() {
    constexpr int kPerUserRange = 100000;
    return gNativeVirtualUid < 0 ? -1 : gNativeVirtualUid % kPerUserRange;
}

int virtualCacheGid() {
    int app_id = virtualAppId();
    if (app_id < 10000 || app_id > 19999) {
        return -1;
    }
    return 20000 + (app_id - 10000);
}

int virtualSharedGid() {
    int app_id = virtualAppId();
    if (app_id < 10000 || app_id > 19999) {
        return -1;
    }
    return 50000 + (app_id - 10000);
}

int buildVirtualGroups(gid_t *groups, int capacity) {
    gid_t values[4] = {
            static_cast<gid_t>(3003),
            static_cast<gid_t>(9997),
            static_cast<gid_t>(virtualCacheGid()),
            static_cast<gid_t>(virtualSharedGid())
    };
    int count = 0;
    for (gid_t value : values) {
        if (value == static_cast<gid_t>(-1)) {
            continue;
        }
        if (groups != nullptr && count < capacity) {
            groups[count] = value;
        }
        ++count;
    }
    return count;
}

int fillVirtualGroups(int size, gid_t *list) {
    int count = buildVirtualGroups(nullptr, 0);
    if (size == 0) {
        return count;
    }
    if (size < count) {
        errno = EINVAL;
        return -1;
    }
    buildVirtualGroups(list, size);
    return count;
}

bool isTerminationSignal(int signal) {
    return signal == kSignalAbort || signal == kSignalKill || signal == kSignalTerm;
}

bool isProtectedSandboxSignalTarget(pid_t pid) {
    if (!isNativeTerminationShieldEnabled()) {
        return false;
    }
    if (pid == getpid()) {
        return true;
    }
    if (gNativeTerminationShieldRootPid > 0 && pid == gNativeTerminationShieldRootPid) {
        return true;
    }
    if (pid == 0 || pid == -1) {
        return true;
    }
    if (gNativeTerminationShieldRootPgid > 0 && pid == -gNativeTerminationShieldRootPgid) {
        return true;
    }
    return false;
}

bool shouldBlockNativeSignal(pid_t pid, int signal) {
    return isNativeTerminationShieldEnabled()
           && isProtectedSandboxSignalTarget(pid)
           && isTerminationSignal(signal);
}

bool shouldBlockNativeThreadSignal(int tgid, int signal) {
    return isNativeTerminationShieldEnabled()
           && (tgid == getpid()
               || (gNativeTerminationShieldRootPid > 0 && tgid == gNativeTerminationShieldRootPid))
           && isTerminationSignal(signal);
}

bool shouldBlockNativeExit() {
    return isNativeTerminationShieldEnabled();
}

pid_t rawThreadId() {
#ifdef __NR_gettid
    long result = callKernelSyscall(__NR_gettid);
    if (result > 0) {
        return static_cast<pid_t>(result);
    }
#endif
    return getpid();
}

int rawKill(pid_t pid, int signal) {
#ifdef __NR_kill
    long result = callKernelSyscall(__NR_kill, static_cast<long>(pid), static_cast<long>(signal));
    return static_cast<int>(result);
#else
    KillFn fn = resolveSymbol(&gOrigKill, "kill");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    return fn(pid, signal);
#endif
}

int rawTkill(pid_t tid, int signal) {
#ifdef __NR_tkill
    long result = callKernelSyscall(__NR_tkill, static_cast<long>(tid), static_cast<long>(signal));
    return static_cast<int>(result);
#else
    KillFn fn = resolveSymbol(&gOrigTkill, "tkill");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    return fn(tid, signal);
#endif
}

int rawTgkill(int tgid, int tid, int signal) {
#ifdef __NR_tgkill
    long result = callKernelSyscall(__NR_tgkill,
                                    static_cast<long>(tgid),
                                    static_cast<long>(tid),
                                    static_cast<long>(signal));
    return static_cast<int>(result);
#else
    TgkillFn fn = resolveSymbol(&gOrigTgkill, "tgkill");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    return fn(tgid, tid, signal);
#endif
}

[[noreturn]] void rawExitProcess(int status) {
#ifdef __NR_exit_group
    callKernelSyscall(__NR_exit_group, static_cast<long>(status));
#endif
#ifdef __NR_exit
    callKernelSyscall(__NR_exit, static_cast<long>(status));
#endif
    for (;;) {
        pause();
    }
}

[[noreturn]] void rawAbortProcess() {
    rawTgkill(getpid(), rawThreadId(), kSignalAbort);
    rawExitProcess(128 + kSignalAbort);
}

struct CallerLocation {
    bool resolved = false;
    uintptr_t offset = 0;
    char path[PATH_MAX] = {};
};

void resolveCallerLocation(void *caller, CallerLocation *location);
uint64_t monotonicTimeNs();

uintptr_t currentStackPointer() {
#if defined(__arm__) || defined(__aarch64__)
    uintptr_t sp = 0;
    __asm__ volatile("mov %0, sp" : "=r"(sp));
    return sp;
#else
    return reinterpret_cast<uintptr_t>(__builtin_frame_address(0));
#endif
}

void copyLogString(char *out, size_t out_size, const char *value, const char *fallback) {
    if (out == nullptr || out_size == 0) {
        return;
    }
    snprintf(out, out_size, "%s", value == nullptr ? fallback : value);
}

void rememberRecentNativeFileProbe(const char *api,
                                   const char *pathname,
                                   const char *redirected,
                                   int flags,
                                   long result,
                                   int result_errno,
                                   void *caller,
                                   const CallerLocation *caller_location) {
    RecentNativeFileProbe event = {};
    event.valid = true;
    event.ts_ns = monotonicTimeNs();
    event.tid = rawThreadId();
    copyLogString(event.api, sizeof(event.api), api, "unknown");
    copyLogString(event.path, sizeof(event.path), pathname, "null");
    copyLogString(event.redirected, sizeof(event.redirected), redirected, "null");
    event.flags = flags;
    event.result = result;
    event.result_errno = result_errno;
    event.caller = caller;
    event.caller_offset = caller_location == nullptr ? 0 : caller_location->offset;
    copyLogString(event.caller_map, sizeof(event.caller_map),
                  caller_location == nullptr ? nullptr : caller_location->path,
                  "unknown");

    pthread_mutex_lock(&gRecentNativeFileProbeLock);
    event.sequence = gRecentNativeFileProbeSequence++;
    gRecentNativeFileProbes[event.sequence % kRecentNativeFileProbeCount] = event;
    pthread_mutex_unlock(&gRecentNativeFileProbeLock);
}

void dumpRecentNativeFileProbesForTermination(const char *api, pid_t termination_tid) {
    if (!isFileProbeEnabled()) {
        return;
    }

    RecentNativeFileProbe selected[kRecentNativeFileProbeDumpCount];
    size_t selected_count = 0;
    pthread_mutex_lock(&gRecentNativeFileProbeLock);
    uint64_t next_sequence = gRecentNativeFileProbeSequence;
    uint64_t available = next_sequence < kRecentNativeFileProbeCount
                         ? next_sequence
                         : kRecentNativeFileProbeCount;
    for (int pass = 0; pass < 2 && selected_count < kRecentNativeFileProbeDumpCount; ++pass) {
        for (uint64_t age = 0; age < available && selected_count < kRecentNativeFileProbeDumpCount; ++age) {
            uint64_t sequence = next_sequence - 1 - age;
            const RecentNativeFileProbe &event =
                    gRecentNativeFileProbes[sequence % kRecentNativeFileProbeCount];
            if (!event.valid || event.sequence != sequence) {
                continue;
            }
            if ((pass == 0 && event.tid != termination_tid)
                || (pass == 1 && event.tid == termination_tid)) {
                continue;
            }
            selected[selected_count++] = event;
        }
    }
    pthread_mutex_unlock(&gRecentNativeFileProbeLock);

    for (size_t emitted = 0; emitted < selected_count; ++emitted) {
        const RecentNativeFileProbe &event = selected[emitted];
        ALOGD("native termination recent file api=%s package=%s index=%zu tid=%d eventTid=%d eventTsNs=%llu fileApi=%s path=%s redirected=%s flags=%d result=%ld errno=%d caller=%p callerOff=0x%lx callerMap=%s",
              api == nullptr ? "unknown" : api,
              gNativeTerminationShieldPackage[0] == '\0' ? "none" : gNativeTerminationShieldPackage,
              emitted,
              static_cast<int>(termination_tid),
              static_cast<int>(event.tid),
              static_cast<unsigned long long>(event.ts_ns),
              event.api,
              event.path,
              event.redirected,
              event.flags,
              event.result,
              event.result_errno,
              event.caller,
              static_cast<unsigned long>(event.caller_offset),
              event.caller_map);
    }
}

size_t captureNativeBacktrace(void **frames, size_t max_frames);

void dumpBlockedNativeTerminationFrames(const char *api) {
    void *frames[kTerminationProbeMaxFrames] = {};
    size_t frame_count = captureNativeBacktrace(frames, kTerminationProbeMaxFrames);
    for (size_t i = 0; i < frame_count; i++) {
        CallerLocation frame_location = {};
        resolveCallerLocation(frames[i], &frame_location);
        ALOGD("native termination blocked frame api=%s index=%zu pc=%p pcOff=0x%lx pcMap=%s",
              api == nullptr ? "unknown" : api,
              i,
              frames[i],
              static_cast<unsigned long>(frame_location.offset),
              frame_location.path);
    }
}

void logNativeTerminationBlocked(const char *api, long target, int signal, int status, void *caller) {
    CallerLocation caller_location = {};
    resolveCallerLocation(caller, &caller_location);
    ALOGD("native termination shield blocked api=%s package=%s target=%ld signal=%d status=%d caller=%p callerOff=0x%lx callerMap=%s",
          api == nullptr ? "unknown" : api,
          gNativeTerminationShieldPackage[0] == '\0' ? "none" : gNativeTerminationShieldPackage,
          target,
          signal,
          status,
          caller,
          static_cast<unsigned long>(caller_location.offset),
          caller_location.path);
    dumpBlockedNativeTerminationFrames(api);
    dumpRecentNativeFileProbesForTermination(api, rawThreadId());
}

void logProcessProbe(const char *api, long flags, const char *path, int result, void *caller) {
    if (!isProcessProbeEnabled()) {
        return;
    }
    ALOGD("native process probe api=%s package=%s parent=%d result=%d flags=0x%lx path=%s caller=%p",
          api == nullptr ? "unknown" : api,
          gNativeTerminationShieldPackage[0] == '\0' ? "none" : gNativeTerminationShieldPackage,
          static_cast<int>(getpid()),
          result,
          static_cast<unsigned long>(flags),
          path == nullptr ? "null" : path,
          caller);
}

bool samePath(const char *left, const char *right) {
    if (left == nullptr || right == nullptr) {
        return left == right;
    }
    return strcmp(left, right) == 0;
}

bool isProcProbePath(const char *pathname) {
    static const char *kProcRoot = "/proc";
    static const char *kProcPrefix = "/proc/";
    return pathname != nullptr
           && (strcmp(pathname, kProcRoot) == 0
               || strncmp(pathname, kProcPrefix, strlen(kProcPrefix)) == 0);
}

bool isSysfsProbePath(const char *pathname) {
    static const char *kSysRoot = "/sys";
    static const char *kSysPrefix = "/sys/";
    return pathname != nullptr
           && (strcmp(pathname, kSysRoot) == 0
               || strncmp(pathname, kSysPrefix, strlen(kSysPrefix)) == 0);
}

bool isAppPrivateDataProbePath(const char *pathname) {
    return containsPathPart(pathname, "/data/data/")
           || containsPathPart(pathname, "/data/user/")
           || containsPathPart(pathname, "/blackbox/data/user/");
}

bool isApkProbePath(const char *pathname) {
    return containsPathPart(pathname, ".apk");
}

bool shouldLogOpenPath(const char *pathname, const char *redirected) {
    if (isProcProbePath(pathname)
           || isProcProbePath(redirected)
           || isApkProbePath(pathname)
           || isApkProbePath(redirected)) {
        return true;
    }
    return !samePath(pathname, redirected)
           && !isAppPrivateDataProbePath(pathname)
           && !isAppPrivateDataProbePath(redirected);
}

bool isRelativeFileProbePath(const char *pathname) {
    return pathname != nullptr && pathname[0] != '\0' && pathname[0] != '/';
}

bool shouldLogAppOwnedNativeFilePath(const char *pathname, const char *redirected) {
    if (!isSysfsProbePath(pathname) && !isSysfsProbePath(redirected)) {
        return isRelativeFileProbePath(pathname)
               || isRelativeFileProbePath(redirected)
               || isProcProbePath(pathname)
               || isProcProbePath(redirected)
               || isApkProbePath(pathname)
               || isApkProbePath(redirected)
               || isAppPrivateDataProbePath(pathname)
               || isAppPrivateDataProbePath(redirected);
    }
    return false;
}

bool shouldLogAppOwnedNativeFileProbe(const char *pathname, const char *redirected, void *caller);

FILE *openRealProcMapsFile();

struct MemoryMapEntry {
    bool resolved = false;
    uintptr_t start = 0;
    uintptr_t end = 0;
    uintptr_t offset = 0;
    char perms[8] = {};
    char path[PATH_MAX] = {};
};

struct CachedMemoryMapEntry {
    bool valid = false;
    uint64_t generation = 0;
    uint64_t expires_at_ns = 0;
    MemoryMapEntry entry = {};
};

pthread_mutex_t gMemoryMapEntryCacheLock = PTHREAD_MUTEX_INITIALIZER;
constexpr size_t kMemoryMapEntryCacheCount = 64;
constexpr uint64_t kMemoryMapEntryCacheTtlNs = 250ULL * 1000ULL * 1000ULL;
CachedMemoryMapEntry gMemoryMapEntryCache[kMemoryMapEntryCacheCount] = {};
uint64_t gMemoryMapEntryCacheCursor = 0;
uint64_t gMemoryMapEntryCacheGeneration = 1;

bool writeExact(int fd, const void *data, size_t length);
uint64_t monotonicTimeNs();

struct NativeBacktraceState {
    void **frames = nullptr;
    size_t max_frames = 0;
    size_t frame_count = 0;
};

_Unwind_Reason_Code captureNativeBacktraceFrame(_Unwind_Context *context, void *arg) {
    NativeBacktraceState *state = reinterpret_cast<NativeBacktraceState *>(arg);
    if (state == nullptr || state->frames == nullptr || state->frame_count >= state->max_frames) {
        return _URC_END_OF_STACK;
    }
    uintptr_t pc = static_cast<uintptr_t>(_Unwind_GetIP(context));
    if (pc != 0) {
        state->frames[state->frame_count++] = reinterpret_cast<void *>(pc);
    }
    return state->frame_count >= state->max_frames ? _URC_END_OF_STACK : _URC_NO_REASON;
}

size_t captureNativeBacktrace(void **frames, size_t max_frames) {
    NativeBacktraceState state = {};
    state.frames = frames;
    state.max_frames = max_frames;
    _Unwind_Backtrace(captureNativeBacktraceFrame, &state);
    return state.frame_count;
}

bool parseMemoryMapLine(const char *line, MemoryMapEntry *entry) {
    if (line == nullptr || entry == nullptr) {
        return false;
    }

    unsigned long start = 0;
    unsigned long end = 0;
    unsigned long map_offset = 0;
    unsigned long inode = 0;
    char perms[8] = {};
    char dev[32] = {};
    char path[PATH_MAX] = {};
    int fields = sscanf(line, "%lx-%lx %7s %lx %31s %lu %4095[^\n]",
                        &start, &end, perms, &map_offset, dev, &inode, path);
    if (fields < 6 || end <= start) {
        return false;
    }

    char *trimmed_path = fields >= 7 ? path : nullptr;
    while (trimmed_path != nullptr && *trimmed_path == ' ') {
        trimmed_path++;
    }

    entry->resolved = true;
    entry->start = static_cast<uintptr_t>(start);
    entry->end = static_cast<uintptr_t>(end);
    entry->offset = static_cast<uintptr_t>(map_offset);
    snprintf(entry->perms, sizeof(entry->perms), "%s", perms);
    snprintf(entry->path, sizeof(entry->path), "%s",
             trimmed_path == nullptr || *trimmed_path == '\0' ? "anonymous" : trimmed_path);
    return true;
}

bool lookupMemoryMapEntryCache(uintptr_t address, MemoryMapEntry *entry) {
    if (address == 0 || entry == nullptr) {
        return false;
    }
    if (pthread_mutex_trylock(&gMemoryMapEntryCacheLock) != 0) {
        return false;
    }

    bool found = false;
    const uint64_t now = monotonicTimeNs();
    const uint64_t generation = gMemoryMapEntryCacheGeneration;
    for (CachedMemoryMapEntry &cached : gMemoryMapEntryCache) {
        if (!cached.valid || cached.generation != generation) {
            continue;
        }
        if (now > cached.expires_at_ns) {
            cached.valid = false;
            continue;
        }
        const MemoryMapEntry &candidate = cached.entry;
        if (candidate.resolved && address >= candidate.start && address < candidate.end) {
            *entry = candidate;
            found = true;
            break;
        }
    }

    pthread_mutex_unlock(&gMemoryMapEntryCacheLock);
    return found;
}

void rememberMemoryMapEntryCache(uintptr_t address, const MemoryMapEntry &entry) {
    if (!entry.resolved || entry.end <= entry.start || address < entry.start || address >= entry.end) {
        return;
    }
    if (pthread_mutex_trylock(&gMemoryMapEntryCacheLock) != 0) {
        return;
    }

    CachedMemoryMapEntry &cached =
            gMemoryMapEntryCache[gMemoryMapEntryCacheCursor++ % kMemoryMapEntryCacheCount];
    cached.valid = true;
    cached.generation = gMemoryMapEntryCacheGeneration;
    cached.expires_at_ns = monotonicTimeNs() + kMemoryMapEntryCacheTtlNs;
    cached.entry = entry;

    pthread_mutex_unlock(&gMemoryMapEntryCacheLock);
}

void invalidateMemoryMapEntryCache() {
    pthread_mutex_lock(&gMemoryMapEntryCacheLock);
    gMemoryMapEntryCacheGeneration++;
    if (gMemoryMapEntryCacheGeneration == 0) {
        gMemoryMapEntryCacheGeneration = 1;
    }
    gMemoryMapEntryCacheCursor = 0;
    memset(gMemoryMapEntryCache, 0, sizeof(gMemoryMapEntryCache));
    pthread_mutex_unlock(&gMemoryMapEntryCacheLock);
}

void resolveCallerLocation(void *caller, CallerLocation *location) {
    if (location == nullptr) {
        return;
    }
    location->resolved = false;
    location->offset = 0;
    snprintf(location->path, sizeof(location->path), "%s", "unknown");
    if (caller == nullptr) {
        return;
    }

    const uintptr_t caller_address = reinterpret_cast<uintptr_t>(caller);
    DladdrFn dladdr_fn = resolveSymbol(&gOrigDladdr, "dladdr");
    Dl_info info = {};
    if (dladdr_fn != nullptr
        && dladdr_fn(caller, &info) != 0
        && info.dli_fname != nullptr
        && info.dli_fbase != nullptr) {
        location->resolved = true;
        location->offset = caller_address - reinterpret_cast<uintptr_t>(info.dli_fbase);
        snprintf(location->path, sizeof(location->path), "%s", info.dli_fname);
        return;
    }

    if (!isProcessProbeEnabled()) {
        return;
    }
    FILE *maps = openRealProcMapsFile();
    if (maps == nullptr) {
        return;
    }
    char line[4096];
    while (fgets(line, sizeof(line), maps) != nullptr) {
        MemoryMapEntry entry = {};
        if (!parseMemoryMapLine(line, &entry)) {
            continue;
        }
        uintptr_t caller = caller_address;
        uintptr_t start = entry.start;
        uintptr_t end = entry.end;
        uintptr_t map_offset = entry.offset;
        if (caller >= start && caller < end) {
            location->resolved = true;
            uintptr_t caller_offset = map_offset + (caller - start);
            location->offset = caller_offset;
            snprintf(location->path, sizeof(location->path), "%s", entry.path);
            break;
        }
    }
    fclose(maps);
}

bool resolveMemoryMapEntry(void *caller, MemoryMapEntry *entry) {
    if (entry == nullptr) {
        return false;
    }
    entry->resolved = false;
    entry->start = 0;
    entry->end = 0;
    entry->offset = 0;
    entry->perms[0] = '\0';
    entry->path[0] = '\0';
    if (caller == nullptr) {
        return false;
    }

    const uintptr_t address = reinterpret_cast<uintptr_t>(caller);
    if (lookupMemoryMapEntryCache(address, entry)) {
        return true;
    }

    FILE *maps = openRealProcMapsFile();
    if (maps == nullptr) {
        return false;
    }

    char line[4096];
    while (fgets(line, sizeof(line), maps) != nullptr) {
        MemoryMapEntry candidate = {};
        if (!parseMemoryMapLine(line, &candidate)) {
            continue;
        }
        if (address < candidate.start || address >= candidate.end) {
            continue;
        }

        *entry = candidate;
        rememberMemoryMapEntryCache(address, candidate);
        break;
    }
    fclose(maps);
    return entry->resolved;
}

void sanitizeFileToken(const char *value, char *out, size_t out_size) {
    if (out == nullptr || out_size == 0) {
        return;
    }
    size_t cursor = 0;
    if (value != nullptr) {
        for (size_t i = 0; value[i] != '\0' && cursor + 1 < out_size; ++i) {
            char c = value[i];
            bool keep = (c >= 'a' && c <= 'z')
                        || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9')
                        || c == '_' || c == '-' || c == '.';
            out[cursor++] = keep ? c : '_';
        }
    }
    if (cursor == 0 && out_size > 1) {
        out[cursor++] = 'x';
    }
    out[cursor] = '\0';
}

void writeTerminationMemoryDumpMetadata(int fd,
                                        const char *api,
                                        void *caller,
                                        const CallerLocation *caller_location,
                                        const MemoryMapEntry *entry,
                                        size_t dump_size,
                                        const char *dump_path) {
    if (fd < 0 || entry == nullptr) {
        return;
    }
    char metadata[2048];
    int length = snprintf(metadata, sizeof(metadata),
                          "{"
                          "\"api\":\"%s\","
                          "\"pid\":%d,"
                          "\"tid\":%ld,"
                          "\"package\":\"%s\","
                          "\"caller\":\"%p\","
                          "\"callerOff\":\"0x%lx\","
                          "\"callerMap\":\"%s\","
                          "\"mapStart\":\"0x%lx\","
                          "\"mapEnd\":\"0x%lx\","
                          "\"mapOffset\":\"0x%lx\","
                          "\"mapPerms\":\"%s\","
                          "\"mapPath\":\"%s\","
                          "\"dumpSize\":%zu,"
                          "\"dumpPath\":\"%s\""
                          "}\n",
                          api == nullptr ? "unknown" : api,
                          getpid(),
#ifdef __NR_gettid
                          callKernelSyscall(__NR_gettid),
#else
                          static_cast<long>(getpid()),
#endif
                          gNativeTerminationShieldPackage[0] == '\0' ? "none" : gNativeTerminationShieldPackage,
                          caller,
                          static_cast<unsigned long>(caller_location == nullptr ? 0 : caller_location->offset),
                          caller_location == nullptr ? "unknown" : caller_location->path,
                          static_cast<unsigned long>(entry->start),
                          static_cast<unsigned long>(entry->end),
                          static_cast<unsigned long>(entry->offset),
                          entry->perms,
                          entry->path,
                          dump_size,
                          dump_path == nullptr ? "" : dump_path);
    if (length > 0) {
        size_t safe_length = static_cast<size_t>(length);
        if (safe_length >= sizeof(metadata)) {
            safe_length = sizeof(metadata) - 1;
        }
        writeExact(fd, metadata, safe_length);
    }
}

void dumpTerminationCallerMemory(const char *api, void *caller, const CallerLocation *caller_location) {
    if (!isTerminationMemoryDumpEnabled()
        || gNativeTerminationShieldPackage[0] == '\0'
        || caller == nullptr) {
        return;
    }

    MemoryMapEntry entry = {};
    if (!resolveMemoryMapEntry(caller, &entry)
        || entry.perms[0] != 'r'
        || entry.end <= entry.start) {
        return;
    }

    size_t map_size = static_cast<size_t>(entry.end - entry.start);
    size_t dump_size = map_size > kTerminationMemoryDumpMaxBytes
                       ? kTerminationMemoryDumpMaxBytes
                       : map_size;
    char dir_path[PATH_MAX];
    int dir_length = snprintf(dir_path, sizeof(dir_path),
                              "/data/user/0/%s/files/native_probe",
                              gNativeTerminationShieldPackage);
    if (dir_length <= 0 || static_cast<size_t>(dir_length) >= sizeof(dir_path)) {
        return;
    }
    ScopedInternalFileProbe internal_probe;
    mkdir(dir_path, 0700);

    char api_token[64];
    sanitizeFileToken(api, api_token, sizeof(api_token));
    uintptr_t caller_offset = caller_location == nullptr ? 0 : caller_location->offset;
    char dump_path[PATH_MAX];
    int dump_length = snprintf(dump_path, sizeof(dump_path),
                               "%s/term_%d_%s_0x%lx_0x%lx-0x%lx.bin",
                               dir_path,
                               getpid(),
                               api_token,
                               static_cast<unsigned long>(caller_offset),
                               static_cast<unsigned long>(entry.start),
                               static_cast<unsigned long>(entry.end));
    if (dump_length <= 0 || static_cast<size_t>(dump_length) >= sizeof(dump_path)) {
        return;
    }

    int fd = open(dump_path, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
    if (fd < 0) {
        ALOGD("native termination memdump failed path=%s errno=%d", dump_path, errno);
        return;
    }
    bool ok = writeExact(fd, reinterpret_cast<const void *>(entry.start), dump_size);
    close(fd);

    char meta_path[PATH_MAX];
    int meta_length = snprintf(meta_path, sizeof(meta_path), "%s.meta", dump_path);
    if (meta_length > 0 && static_cast<size_t>(meta_length) < sizeof(meta_path)) {
        int meta_fd = open(meta_path, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
        if (meta_fd >= 0) {
            writeTerminationMemoryDumpMetadata(meta_fd, api, caller, caller_location, &entry, dump_size, dump_path);
            close(meta_fd);
        }
    }

    ALOGD("native termination memdump meta api=%s package=%s path=%s ok=%d caller=%p callerOff=0x%lx mapStart=0x%lx mapEnd=0x%lx mapOffset=0x%lx perms=%s map=%s size=%zu",
          api == nullptr ? "unknown" : api,
          gNativeTerminationShieldPackage,
          dump_path,
          ok ? 1 : 0,
          caller,
          static_cast<unsigned long>(caller_offset),
          static_cast<unsigned long>(entry.start),
          static_cast<unsigned long>(entry.end),
          static_cast<unsigned long>(entry.offset),
          entry.perms,
          entry.path,
          dump_size);
}

void dumpTerminationStackMemory(const char *api, uintptr_t stack_pointer) {
    if (!isTerminationMemoryDumpEnabled()
        || gNativeTerminationShieldPackage[0] == '\0'
        || stack_pointer == 0) {
        return;
    }

    MemoryMapEntry entry = {};
    if (!resolveMemoryMapEntry(reinterpret_cast<void *>(stack_pointer), &entry)
        || entry.perms[0] != 'r'
        || entry.end <= entry.start) {
        return;
    }

    uintptr_t dump_start = stack_pointer;
    if (dump_start < entry.start) {
        dump_start = entry.start;
    }
    uintptr_t dump_end = dump_start + kTerminationStackDumpMaxBytes;
    if (dump_end < dump_start || dump_end > entry.end) {
        dump_end = entry.end;
    }
    if (dump_end <= dump_start) {
        return;
    }
    size_t dump_size = static_cast<size_t>(dump_end - dump_start);

    char dir_path[PATH_MAX];
    int dir_length = snprintf(dir_path, sizeof(dir_path),
                              "/data/user/0/%s/files/native_probe",
                              gNativeTerminationShieldPackage);
    if (dir_length <= 0 || static_cast<size_t>(dir_length) >= sizeof(dir_path)) {
        return;
    }
    ScopedInternalFileProbe internal_probe;
    mkdir(dir_path, 0700);

    char api_token[64];
    sanitizeFileToken(api, api_token, sizeof(api_token));
    char dump_path[PATH_MAX];
    int dump_length = snprintf(dump_path, sizeof(dump_path),
                               "%s/term_%d_%s_stack_0x%lx-0x%lx.bin",
                               dir_path,
                               getpid(),
                               api_token,
                               static_cast<unsigned long>(dump_start),
                               static_cast<unsigned long>(dump_end));
    if (dump_length <= 0 || static_cast<size_t>(dump_length) >= sizeof(dump_path)) {
        return;
    }

    int fd = open(dump_path, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
    if (fd < 0) {
        ALOGD("native termination stackdump failed path=%s errno=%d", dump_path, errno);
        return;
    }
    bool ok = writeExact(fd, reinterpret_cast<const void *>(dump_start), dump_size);
    close(fd);

    CallerLocation stack_location = {};
    stack_location.resolved = true;
    stack_location.offset = entry.offset + (stack_pointer - entry.start);
    snprintf(stack_location.path, sizeof(stack_location.path), "%s", entry.path);

    char meta_path[PATH_MAX];
    int meta_length = snprintf(meta_path, sizeof(meta_path), "%s.meta", dump_path);
    if (meta_length > 0 && static_cast<size_t>(meta_length) < sizeof(meta_path)) {
        int meta_fd = open(meta_path, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
        if (meta_fd >= 0) {
            writeTerminationMemoryDumpMetadata(meta_fd,
                                               api,
                                               reinterpret_cast<void *>(stack_pointer),
                                               &stack_location,
                                               &entry,
                                               dump_size,
                                               dump_path);
            close(meta_fd);
        }
    }

    ALOGD("native termination stackdump meta api=%s package=%s path=%s ok=%d stack=0x%lx dumpStart=0x%lx dumpEnd=0x%lx mapStart=0x%lx mapEnd=0x%lx perms=%s map=%s size=%zu",
          api == nullptr ? "unknown" : api,
          gNativeTerminationShieldPackage,
          dump_path,
          ok ? 1 : 0,
          static_cast<unsigned long>(stack_pointer),
          static_cast<unsigned long>(dump_start),
          static_cast<unsigned long>(dump_end),
          static_cast<unsigned long>(entry.start),
          static_cast<unsigned long>(entry.end),
          entry.perms,
          entry.path,
          dump_size);
}

uintptr_t memoryMapDistance(const MemoryMapEntry &left, const MemoryMapEntry &right) {
    if (right.end <= left.start) {
        return left.start - right.end;
    }
    if (right.start >= left.end) {
        return right.start - left.end;
    }
    return 0;
}

bool isSameMemoryMap(const MemoryMapEntry &left, const MemoryMapEntry &right) {
    return left.start == right.start && left.end == right.end && left.offset == right.offset;
}

bool isAdjacentReadableMapCandidate(const MemoryMapEntry &caller_entry,
                                    const MemoryMapEntry &candidate,
                                    uintptr_t *distance_out) {
    if (!candidate.resolved
        || candidate.perms[0] != 'r'
        || candidate.end <= candidate.start
        || isSameMemoryMap(caller_entry, candidate)) {
        return false;
    }

    uintptr_t distance = memoryMapDistance(caller_entry, candidate);
    if (distance > kTerminationAdjacentDumpMaxDistance) {
        return false;
    }
    if (distance_out != nullptr) {
        *distance_out = distance;
    }
    return true;
}

void dumpTerminationMemoryMapFile(const char *api,
                                  const char *kind,
                                  size_t index,
                                  void *caller,
                                  const CallerLocation *caller_location,
                                  const MemoryMapEntry *entry,
                                  size_t max_bytes) {
    if (entry == nullptr || entry->perms[0] != 'r' || entry->end <= entry->start) {
        return;
    }

    size_t map_size = static_cast<size_t>(entry->end - entry->start);
    size_t dump_size = map_size > max_bytes ? max_bytes : map_size;
    char dir_path[PATH_MAX];
    int dir_length = snprintf(dir_path, sizeof(dir_path),
                              "/data/user/0/%s/files/native_probe",
                              gNativeTerminationShieldPackage);
    if (dir_length <= 0 || static_cast<size_t>(dir_length) >= sizeof(dir_path)) {
        return;
    }
    ScopedInternalFileProbe internal_probe;
    mkdir(dir_path, 0700);

    char api_token[64];
    sanitizeFileToken(api, api_token, sizeof(api_token));
    char kind_token[64];
    sanitizeFileToken(kind, kind_token, sizeof(kind_token));
    char dump_path[PATH_MAX];
    int dump_length = snprintf(dump_path, sizeof(dump_path),
                               "%s/term_%d_%s_%s_%zu_0x%lx-0x%lx.bin",
                               dir_path,
                               getpid(),
                               api_token,
                               kind_token,
                               index,
                               static_cast<unsigned long>(entry->start),
                               static_cast<unsigned long>(entry->end));
    if (dump_length <= 0 || static_cast<size_t>(dump_length) >= sizeof(dump_path)) {
        return;
    }

    int fd = open(dump_path, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
    if (fd < 0) {
        ALOGD("native termination %s memdump failed path=%s errno=%d",
              kind == nullptr ? "unknown" : kind,
              dump_path,
              errno);
        return;
    }
    bool ok = writeExact(fd, reinterpret_cast<const void *>(entry->start), dump_size);
    close(fd);

    char meta_path[PATH_MAX];
    int meta_length = snprintf(meta_path, sizeof(meta_path), "%s.meta", dump_path);
    if (meta_length > 0 && static_cast<size_t>(meta_length) < sizeof(meta_path)) {
        int meta_fd = open(meta_path, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
        if (meta_fd >= 0) {
            writeTerminationMemoryDumpMetadata(meta_fd,
                                               api,
                                               caller,
                                               caller_location,
                                               entry,
                                               dump_size,
                                               dump_path);
            close(meta_fd);
        }
    }

    ALOGD("native termination adjacent memdump meta kind=%s api=%s package=%s index=%zu path=%s ok=%d caller=%p callerOff=0x%lx mapStart=0x%lx mapEnd=0x%lx mapOffset=0x%lx perms=%s map=%s size=%zu",
          kind == nullptr ? "unknown" : kind,
          api == nullptr ? "unknown" : api,
          gNativeTerminationShieldPackage,
          index,
          dump_path,
          ok ? 1 : 0,
          caller,
          static_cast<unsigned long>(caller_location == nullptr ? 0 : caller_location->offset),
          static_cast<unsigned long>(entry->start),
          static_cast<unsigned long>(entry->end),
          static_cast<unsigned long>(entry->offset),
          entry->perms,
          entry->path,
          dump_size);
}

void dumpTerminationAdjacentReadableMaps(const char *api,
                                         void *caller,
                                         const CallerLocation *caller_location) {
    if (!isTerminationMemoryDumpEnabled()
        || gNativeTerminationShieldPackage[0] == '\0'
        || caller == nullptr) {
        return;
    }

    MemoryMapEntry caller_entry = {};
    if (!resolveMemoryMapEntry(caller, &caller_entry)) {
        return;
    }

    struct AdjacentCandidate {
        MemoryMapEntry entry = {};
        uintptr_t distance = 0;
    };
    AdjacentCandidate selected[kTerminationAdjacentDumpMaxMaps];
    size_t selected_count = 0;

    FILE *maps = openRealProcMapsFile();
    if (maps == nullptr) {
        return;
    }

    char line[4096];
    while (fgets(line, sizeof(line), maps) != nullptr) {
        MemoryMapEntry candidate = {};
        if (!parseMemoryMapLine(line, &candidate)) {
            continue;
        }
        uintptr_t distance = 0;
        if (!isAdjacentReadableMapCandidate(caller_entry, candidate, &distance)) {
            continue;
        }
        size_t slot = selected_count;
        if (selected_count < kTerminationAdjacentDumpMaxMaps) {
            selected_count++;
        } else if (distance >= selected[selected_count - 1].distance) {
            continue;
        } else {
            slot = selected_count - 1;
        }
        while (slot > 0 && selected[slot - 1].distance > distance) {
            selected[slot] = selected[slot - 1];
            slot--;
        }
        selected[slot].entry = candidate;
        selected[slot].distance = distance;
    }
    fclose(maps);

    for (size_t i = 0; i < selected_count; ++i) {
        dumpTerminationMemoryMapFile(api,
                                     "adjacent",
                                     i,
                                     caller,
                                     caller_location,
                                     &selected[i].entry,
                                     kTerminationAdjacentDumpMaxBytes);
    }
}

void logOpenPath(const char *api, const char *pathname, const char *redirected, int flags, long result, void *caller) {
    if (isInternalFileProbe()) {
        return;
    }
    if (!isFileProbeEnabled()) {
        return;
    }
    int result_errno = result < 0 ? errno : 0;
    bool should_log = shouldLogOpenPath(pathname, redirected)
                      || (shouldLogAppOwnedNativeFilePath(pathname, redirected)
                          && shouldLogAppOwnedNativeFileProbe(pathname, redirected, caller));
    if (!should_log) {
        return;
    }
    CallerLocation caller_location = {};
    resolveCallerLocation(caller, &caller_location);
    rememberRecentNativeFileProbe(api, pathname, redirected, flags, result, result_errno, caller, &caller_location);
    ALOGD("native file probe api=%s path=%s redirected=%s flags=%d result=%ld errno=%d caller=%p callerOff=0x%lx callerMap=%s",
          api == nullptr ? "unknown" : api,
          pathname == nullptr ? "null" : pathname,
          redirected == nullptr ? "null" : redirected,
          flags,
          result,
          result_errno,
          caller,
          static_cast<unsigned long>(caller_location.offset),
          caller_location.path);
}

void logNativeTerminationProbe(const char *api,
                               long target,
                               int signal,
                               int status,
                               void *caller,
                               uintptr_t stack_pointer = 0) {
    if (!isTerminationProbeEnabled()) {
        return;
    }

    CallerLocation caller_location = {};
    resolveCallerLocation(caller, &caller_location);
    ALOGD("native termination probe api=%s package=%s target=%ld signal=%d status=%d caller=%p callerOff=0x%lx callerMap=%s",
          api == nullptr ? "unknown" : api,
          gNativeTerminationShieldPackage[0] == '\0' ? "none" : gNativeTerminationShieldPackage,
          target,
          signal,
          status,
          caller,
          static_cast<unsigned long>(caller_location.offset),
          caller_location.path);
    dumpTerminationCallerMemory(api, caller, &caller_location);
    dumpTerminationStackMemory(api, stack_pointer);
    dumpTerminationAdjacentReadableMaps(api, caller, &caller_location);

    void *frames[kTerminationProbeMaxFrames] = {};
    size_t frame_count = captureNativeBacktrace(frames, kTerminationProbeMaxFrames);
    for (size_t i = 0; i < frame_count; i++) {
        CallerLocation frame_location = {};
        resolveCallerLocation(frames[i], &frame_location);
        ALOGD("native termination frame api=%s index=%zu pc=%p pcOff=0x%lx pcMap=%s",
              api == nullptr ? "unknown" : api,
              i,
              frames[i],
              static_cast<unsigned long>(frame_location.offset),
              frame_location.path);
    }
}

NativeCrashSignalAction *findNativeCrashSignalAction(int signo) {
    for (NativeCrashSignalAction &action : gNativeCrashSignalActions) {
        if (action.signo == signo) {
            return &action;
        }
    }
    return nullptr;
}

uintptr_t crashContextPc(void *context_raw) {
    if (context_raw == nullptr) {
        return 0;
    }
    ucontext_t *context = reinterpret_cast<ucontext_t *>(context_raw);
#if defined(__arm__)
    return static_cast<uintptr_t>(context->uc_mcontext.arm_pc);
#elif defined(__aarch64__)
    return static_cast<uintptr_t>(context->uc_mcontext.pc);
#else
    (void) context;
    return 0;
#endif
}

uintptr_t crashContextLr(void *context_raw) {
    if (context_raw == nullptr) {
        return 0;
    }
    ucontext_t *context = reinterpret_cast<ucontext_t *>(context_raw);
#if defined(__arm__)
    return static_cast<uintptr_t>(context->uc_mcontext.arm_lr);
#elif defined(__aarch64__)
    return static_cast<uintptr_t>(context->uc_mcontext.regs[30]);
#else
    (void) context;
    return 0;
#endif
}

uintptr_t crashContextSp(void *context_raw) {
    if (context_raw == nullptr) {
        return 0;
    }
    ucontext_t *context = reinterpret_cast<ucontext_t *>(context_raw);
#if defined(__arm__)
    return static_cast<uintptr_t>(context->uc_mcontext.arm_sp);
#elif defined(__aarch64__)
    return static_cast<uintptr_t>(context->uc_mcontext.sp);
#else
    (void) context;
    return 0;
#endif
}

void resolveCrashAddressLocation(uintptr_t address, CallerLocation *location) {
    if (location == nullptr) {
        return;
    }
    resolveCallerLocation(reinterpret_cast<void *>(address), location);
    if (location->resolved) {
        return;
    }
    MemoryMapEntry entry = {};
    if (!resolveMemoryMapEntry(reinterpret_cast<void *>(address), &entry)) {
        return;
    }
    location->resolved = true;
    location->offset = entry.offset + (address - entry.start);
    snprintf(location->path, sizeof(location->path), "%s", entry.path);
}

void forwardNativeCrashSignal(int signo, siginfo_t *info, void *context_raw) {
    (void) info;
    (void) context_raw;
    NativeCrashSignalAction *action = findNativeCrashSignalAction(signo);
    if (action != nullptr && action->has_previous) {
        sigaction(signo, &action->previous, nullptr);
    } else {
        signal(signo, SIG_DFL);
    }
#ifdef __NR_tgkill
    callKernelSyscall(__NR_tgkill,
                      static_cast<long>(getpid()),
                      static_cast<long>(rawThreadId()),
                      static_cast<long>(signo));
#else
    raise(signo);
#endif
    rawExitProcess(128 + signo);
}

void nativeCrashProbeHandler(int signo, siginfo_t *info, void *context_raw) {
    if (gNativeCrashProbeHandling) {
        forwardNativeCrashSignal(signo, info, context_raw);
        return;
    }
    gNativeCrashProbeHandling = true;

    uintptr_t pc = crashContextPc(context_raw);
    uintptr_t lr = crashContextLr(context_raw);
    uintptr_t sp = crashContextSp(context_raw);
    CallerLocation pc_location = {};
    CallerLocation lr_location = {};
    resolveCrashAddressLocation(pc, &pc_location);
    resolveCrashAddressLocation(lr, &lr_location);

    ALOGE("native crash probe signal=%d si_code=%d package=%s tid=%d fault=%p pc=%p lr=%p sp=%p pcOff=0x%lx pcMap=%s lrOff=0x%lx lrMap=%s",
          signo,
          info == nullptr ? 0 : info->si_code,
          gNativeTerminationShieldPackage[0] == '\0' ? "none" : gNativeTerminationShieldPackage,
          static_cast<int>(rawThreadId()),
          info == nullptr ? nullptr : info->si_addr,
          reinterpret_cast<void *>(pc),
          reinterpret_cast<void *>(lr),
          reinterpret_cast<void *>(sp),
          static_cast<unsigned long>(pc_location.offset),
          pc_location.path,
          static_cast<unsigned long>(lr_location.offset),
          lr_location.path);

    gNativeCrashProbeHandling = false;
    forwardNativeCrashSignal(signo, info, context_raw);
}

void installNativeCrashProbeSignal(int signo, struct sigaction *previous) {
    if (previous == nullptr) {
        return;
    }
    struct sigaction action = {};
    action.sa_sigaction = nativeCrashProbeHandler;
    sigemptyset(&action.sa_mask);
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    if (sigaction(signo, &action, previous) != 0) {
        ALOGD("native crash probe signal install failed signal=%d errno=%d", signo, errno);
        return;
    }
    NativeCrashSignalAction *stored = findNativeCrashSignalAction(signo);
    if (stored != nullptr) {
        stored->has_previous = true;
    }
}

void installNativeCrashProbe() {
    if (gNativeCrashProbeInstalled) {
        return;
    }
    installNativeCrashProbeSignal(SIGSEGV, &gNativeCrashSignalActions[0].previous);
    installNativeCrashProbeSignal(SIGBUS, &gNativeCrashSignalActions[1].previous);
    installNativeCrashProbeSignal(SIGILL, &gNativeCrashSignalActions[2].previous);
    gNativeCrashProbeInstalled = true;
    ALOGD("native crash probe installed");
}

void logPthreadCreateProbe(const char *api, void *start_routine, int result, void *caller) {
    if (!isProcessProbeEnabled()) {
        return;
    }

    CallerLocation caller_location = {};
    CallerLocation start_location = {};
    resolveCallerLocation(caller, &caller_location);
    resolveCallerLocation(start_routine, &start_location);
    ALOGD("native process probe api=%s package=%s parent=%d result=%d flags=0x%lx path=%s caller=%p callerOff=0x%lx callerMap=%s startRoutine=%p startOff=0x%lx startMap=%s",
          api == nullptr ? "unknown" : api,
          gNativeTerminationShieldPackage[0] == '\0' ? "none" : gNativeTerminationShieldPackage,
          static_cast<int>(getpid()),
          result,
          static_cast<unsigned long>(0),
          "pthread",
          caller,
          static_cast<unsigned long>(caller_location.offset),
          caller_location.path,
          start_routine,
          static_cast<unsigned long>(start_location.offset),
          start_location.path);

    void *frames[kProcessProbeMaxFrames] = {};
    size_t frame_count = captureNativeBacktrace(frames, kProcessProbeMaxFrames);
    for (size_t i = 0; i < frame_count; i++) {
        CallerLocation frame_location = {};
        resolveCallerLocation(frames[i], &frame_location);
        ALOGD("native process frame api=%s index=%zu pc=%p pcOff=0x%lx pcMap=%s",
              api == nullptr ? "unknown" : api,
              i,
              frames[i],
              static_cast<unsigned long>(frame_location.offset),
              frame_location.path);
    }
}

void logDlopenProbe(const char *api, const char *filename, void *result, void *caller) {
    if (!isProcessProbeEnabled()) {
        return;
    }

    CallerLocation caller_location = {};
    resolveCallerLocation(caller, &caller_location);
    ALOGD("native dlopen probe api=%s package=%s path=%s result=%p caller=%p callerOff=0x%lx callerMap=%s",
          api == nullptr ? "unknown" : api,
          gNativeTerminationShieldPackage[0] == '\0' ? "none" : gNativeTerminationShieldPackage,
          filename == nullptr ? "null" : filename,
          result,
          caller,
          static_cast<unsigned long>(caller_location.offset),
          caller_location.path);
}

void patchAfterDynamicLoad(const char *api, const char *filename, void *result, void *caller) {
    if (result != nullptr) {
        invalidateMemoryMapEntryCache();
        if (isEarlyDlopenRepatchEnabled()) {
            installNativeFileHooks();
        }
    }
    logDlopenProbe(api, filename, result, caller);
}

void *resolveDlsymReplacement(const char *symbol) {
    if (!isDlsymReplacementEnabled()) {
        return nullptr;
    }
    if (symbol == nullptr) {
        return nullptr;
    }
    if (strcmp(symbol, "pthread_create") == 0) {
        return reinterpret_cast<void *>(static_cast<PthreadCreateFn>(pthread_create));
    }
    return nullptr;
}

void logDlsymProbe(const char *symbol, void *result, bool replaced, void *caller) {
    if (!isProcessProbeEnabled()
        || symbol == nullptr
        || strcmp(symbol, "pthread_create") != 0) {
        return;
    }

    CallerLocation caller_location = {};
    CallerLocation result_location = {};
    resolveCallerLocation(caller, &caller_location);
    resolveCallerLocation(result, &result_location);
    ALOGD("native dlsym probe symbol=%s replacement=%d result=%p resultOff=0x%lx resultMap=%s caller=%p callerOff=0x%lx callerMap=%s",
          symbol,
          replaced ? 1 : 0,
          result,
          static_cast<unsigned long>(result_location.offset),
          result_location.path,
          caller,
          static_cast<unsigned long>(caller_location.offset),
          caller_location.path);
}

void logDirPath(const char *api, const char *pathname, const char *redirected, DIR *result) {
    if (isInternalFileProbe()) {
        return;
    }
    if (!isFileProbeEnabled()) {
        return;
    }
    if (!shouldLogOpenPath(pathname, redirected)) {
        return;
    }
    ALOGD("native dir probe api=%s path=%s redirected=%s result=%d",
          api == nullptr ? "unknown" : api,
          pathname == nullptr ? "null" : pathname,
          redirected == nullptr ? "null" : redirected,
          result == nullptr ? -1 : 0);
}

void logMkdirPath(const char *api, const char *pathname, const char *redirected, mode_t mode, int result, void *caller) {
    if (isInternalFileProbe()) {
        return;
    }
    if (!isFileProbeEnabled()) {
        return;
    }
    int result_errno = result < 0 ? errno : 0;
    if (!shouldLogOpenPath(pathname, redirected)) {
        return;
    }
    CallerLocation caller_location = {};
    resolveCallerLocation(caller, &caller_location);
    rememberRecentNativeFileProbe(api, pathname, redirected, static_cast<int>(mode), result,
                                  result_errno, caller, &caller_location);
    ALOGD("native mkdir probe api=%s path=%s redirected=%s mode=%o result=%d errno=%d caller=%p callerOff=0x%lx callerMap=%s",
          api == nullptr ? "unknown" : api,
          pathname == nullptr ? "null" : pathname,
          redirected == nullptr ? "null" : redirected,
          static_cast<unsigned int>(mode),
          result,
          result_errno,
          caller,
          static_cast<unsigned long>(caller_location.offset),
          caller_location.path);
}

bool isProcShimFd(int fd) {
    return fd >= kProcShimFdStart && fd <= kProcShimFdEnd;
}

bool isProcShimFdAvailable(int fd) {
    if (!isProcShimFd(fd)) {
        return false;
    }
    errno = 0;
    return fcntl(fd, F_GETFD) != -1 || errno != EBADF;
}

bool isProcMapsShimReadyForRedirect() {
    return isProcShimEnabled() && (gEarlyProcMapsReady || isProtectedProcMapsShimReady());
}

bool isActiveProcShimFd(int fd) {
    if (!isProcShimFdAvailable(fd)) {
        return false;
    }
    if (fd == kProcMapsFd) {
        return isProcMapsShimReadyForRedirect();
    }
    return isProtectedProcMapsShimReady();
}

void refreshProcMapsShimForRedirect() {
    if (isProtectedProcMapsShimReady()) {
        refreshProtectedProcMapsShim();
    }
}

FILE *openRealProcMapsFile();
void refreshEarlyProcMapsShim();
void prepareEarlyProcMapsShim(const char *package_name);
bool isProcShimFdPath(const char *pathname, int *fd);

bool isCurrentProcessProcPath(const char *pathname, const char *entry) {
    if (pathname == nullptr || entry == nullptr) {
        return false;
    }
    char self_path[PATH_MAX];
    snprintf(self_path, sizeof(self_path), "/proc/self/%s", entry);
    if (strcmp(pathname, self_path) == 0) {
        return true;
    }
    char pid_path[PATH_MAX];
    snprintf(pid_path, sizeof(pid_path), "/proc/%d/%s", getpid(), entry);
    return strcmp(pathname, pid_path) == 0;
}

const char *redirectProcProbeToShim(const char *pathname) {
    if (isCurrentProcessProcPath(pathname, "comm") && isActiveProcShimFd(kProcCommFd)) {
        return kProcCommFdPath;
    }
    if (isCurrentProcessProcPath(pathname, "cmdline") && isActiveProcShimFd(kProcCmdlineFd)) {
        return kProcCmdlineFdPath;
    }
    if (isCurrentProcessProcPath(pathname, "maps")
        && isProcMapsShimReadyForRedirect()
        && isActiveProcShimFd(kProcMapsFd)) {
        refreshProcMapsShimForRedirect();
        return kProcMapsFdPath;
    }
    if (pathname != nullptr && strcmp(pathname, "/proc/meminfo") == 0
        && isActiveProcShimFd(kProcMeminfoFd)) {
        return kProcMeminfoFdPath;
    }
    if (pathname != nullptr && strcmp(pathname, "/proc/version") == 0
        && isActiveProcShimFd(kProcVersionFd)) {
        return kProcVersionFdPath;
    }
    return nullptr;
}

int procShimFdForReadPath(const char *pathname) {
    int fd = -1;
    if (isProcShimFdPath(pathname, &fd) && isActiveProcShimFd(fd)) {
        return fd;
    }
    if (isCurrentProcessProcPath(pathname, "comm") && isActiveProcShimFd(kProcCommFd)) {
        return kProcCommFd;
    }
    if (isCurrentProcessProcPath(pathname, "cmdline") && isActiveProcShimFd(kProcCmdlineFd)) {
        return kProcCmdlineFd;
    }
    if (isCurrentProcessProcPath(pathname, "maps")
        && isProcMapsShimReadyForRedirect()
        && isActiveProcShimFd(kProcMapsFd)) {
        refreshProcMapsShimForRedirect();
        return kProcMapsFd;
    }
    if (pathname != nullptr && strcmp(pathname, "/proc/meminfo") == 0
        && isActiveProcShimFd(kProcMeminfoFd)) {
        return kProcMeminfoFd;
    }
    if (pathname != nullptr && strcmp(pathname, "/proc/version") == 0
        && isActiveProcShimFd(kProcVersionFd)) {
        return kProcVersionFd;
    }
    return -1;
}

int openProcShimFdForRead(const char *pathname) {
    int shim_fd = procShimFdForReadPath(pathname);
    if (shim_fd < 0) {
        return -1;
    }
    if (lseek(shim_fd, 0, SEEK_SET) < 0) {
        return -1;
    }
    int result = dup(shim_fd);
    if (result < 0) {
        return -1;
    }
    lseek(result, 0, SEEK_SET);
    return result;
}

bool isReadOnlyOpenFlags(int flags) {
    return (flags & O_ACCMODE) == O_RDONLY;
}

bool isReadOnlyFopenMode(const char *mode) {
    return mode != nullptr && mode[0] == 'r' && strchr(mode, '+') == nullptr;
}

bool parseIntSuffix(const char *value, int *result) {
    if (value == nullptr || value[0] == '\0') {
        return false;
    }
    char *end = nullptr;
    long parsed = strtol(value, &end, 10);
    if (end == value || end == nullptr || *end != '\0' || parsed < 0 || parsed > INT_MAX) {
        return false;
    }
    if (result != nullptr) {
        *result = static_cast<int>(parsed);
    }
    return true;
}

bool isProcShimFdPath(const char *pathname, int *fd) {
    static const char *kDevFd = "/dev/fd/";
    static const char *kProcSelfFd = "/proc/self/fd/";

    if (pathname == nullptr) {
        return false;
    }

    int parsed = -1;
    if (strncmp(pathname, kDevFd, strlen(kDevFd)) == 0
        && parseIntSuffix(pathname + strlen(kDevFd), &parsed)
        && isActiveProcShimFd(parsed)) {
        if (fd != nullptr) {
            *fd = parsed;
        }
        return true;
    }

    if (strncmp(pathname, kProcSelfFd, strlen(kProcSelfFd)) == 0
        && parseIntSuffix(pathname + strlen(kProcSelfFd), &parsed)
        && isActiveProcShimFd(parsed)) {
        if (fd != nullptr) {
            *fd = parsed;
        }
        return true;
    }

    const char *prefix = "/proc/";
    const char *middle = "/fd/";
    if (strncmp(pathname, prefix, strlen(prefix)) == 0) {
        const char *pid_start = pathname + strlen(prefix);
        const char *fd_marker = strstr(pid_start, middle);
        if (fd_marker != nullptr && fd_marker != pid_start) {
            char pid_part[32];
            size_t pid_len = static_cast<size_t>(fd_marker - pid_start);
            if (pid_len < sizeof(pid_part)) {
                memcpy(pid_part, pid_start, pid_len);
                pid_part[pid_len] = '\0';
                int pid = -1;
                if (parseIntSuffix(pid_part, &pid)
                    && pid == getpid()
                    && parseIntSuffix(fd_marker + strlen(middle), &parsed)
                    && isActiveProcShimFd(parsed)) {
                    if (fd != nullptr) {
                        *fd = parsed;
                    }
                    return true;
                }
            }
        }
    }
    return false;
}

const char *procShimReadlinkTarget(int fd, char *buffer, size_t buffer_size) {
    if (buffer == nullptr || buffer_size == 0) {
        return nullptr;
    }
    switch (fd) {
        case 90:
            snprintf(buffer, buffer_size, "/proc/%d/comm", getpid());
            return buffer;
        case 91:
            snprintf(buffer, buffer_size, "/proc/%d/cmdline", getpid());
            return buffer;
        case 92:
            return "/proc/meminfo";
        case 93:
            return "/proc/self/maps";
        case 94:
            return "/proc/version";
        default:
            return nullptr;
    }
}

ssize_t copyReadlinkTarget(const char *target, char *buf, size_t bufsiz) {
    if (target == nullptr || buf == nullptr || bufsiz == 0) {
        errno = EINVAL;
        return -1;
    }
    size_t length = strlen(target);
    size_t copy_length = length < bufsiz ? length : bufsiz;
    memcpy(buf, target, copy_length);
    return static_cast<ssize_t>(copy_length);
}

ssize_t reverseRedirectedReadlinkResult(char *buf, size_t bufsiz, ssize_t result) {
    if (result <= 0 || buf == nullptr || bufsiz == 0) {
        return result;
    }

    size_t length = static_cast<size_t>(result);
    if (length > bufsiz) {
        length = bufsiz;
    }
    if (length >= PATH_MAX) {
        return result;
    }

    char visible_target[PATH_MAX] = {};
    memcpy(visible_target, buf, length);
    visible_target[length] = '\0';

    const char *reversed = IO::reverseRedirectPath(visible_target);
    if (reversed == nullptr || reversed == visible_target) {
        return result;
    }

    size_t reversed_length = strlen(reversed);
    size_t copy_length = reversed_length < bufsiz ? reversed_length : bufsiz;
    memcpy(buf, reversed, copy_length);
    free(const_cast<char *>(reversed));
    return static_cast<ssize_t>(copy_length);
}

ssize_t callRawReadlink(const char *pathname, char *buf, size_t bufsiz) {
    ReadlinkAtFn fn = resolveSymbol(&gOrigReadlinkAt, "readlinkat");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    return fn(AT_FDCWD, pathname, buf, bufsiz);
}

bool isVirtualProcMapsFdTarget(const char *target) {
    return target != nullptr && strstr(target, "bb_proc_maps") != nullptr;
}

const char *virtualProcMapsReadlinkTarget(const char *pathname, char *target_buffer, size_t target_buffer_size) {
    if (pathname == nullptr || target_buffer == nullptr || target_buffer_size == 0) {
        return nullptr;
    }
    ssize_t length = callRawReadlink(pathname, target_buffer, target_buffer_size - 1);
    if (length <= 0) {
        return nullptr;
    }
    target_buffer[length] = '\0';
    if (!isVirtualProcMapsFdTarget(target_buffer)) {
        return nullptr;
    }
    return "/proc/self/maps";
}

bool isVirtualProcMapsFd(int fd) {
    if (fd < 0) {
        return false;
    }
    char fd_path[64];
    char target[PATH_MAX];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
    ssize_t length = callRawReadlink(fd_path, target, sizeof(target) - 1);
    if (length <= 0) {
        return false;
    }
    target[length] = '\0';
    return isVirtualProcMapsFdTarget(target);
}

bool isBlackBoxProcCmdlinePath(const char *pathname) {
    return containsPathPart(pathname, "/blackbox/proc/")
           && endsWithPathPart(pathname, "/cmdline");
}

bool isProcCmdlineProbePath(const char *pathname, const char *redirected) {
    return isCurrentProcessProcPath(pathname, "cmdline")
           || isBlackBoxProcCmdlinePath(pathname)
           || isBlackBoxProcCmdlinePath(redirected);
}

bool isProcCmdlineFd(int fd) {
    if (fd < 0) {
        return false;
    }
    char fd_path[64];
    char target[PATH_MAX];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
    ssize_t length = callRawReadlink(fd_path, target, sizeof(target) - 1);
    if (length <= 0) {
        return false;
    }
    target[length] = '\0';
    return isProcCmdlineProbePath(target, nullptr);
}

void sanitizeProcMapsStat(struct stat *buf) {
    if (buf == nullptr) {
        return;
    }
    buf->st_mode = S_IFREG | 0444;
    buf->st_nlink = 1;
    buf->st_size = 0;
    if (isNativeVirtualUidConfigured()) {
        buf->st_uid = virtualUid();
        buf->st_gid = virtualGid();
    }
}

void maybeSanitizeProcMapsFdStat(int result, int fd, struct stat *buf) {
    if (result == 0 && isVirtualProcMapsFd(fd)) {
        sanitizeProcMapsStat(buf);
    }
}

void sanitizeProcCmdlineStat(struct stat *buf) {
    if (buf == nullptr) {
        return;
    }
    buf->st_mode = S_IFREG | 0444;
    buf->st_nlink = 1;
    buf->st_size = 0;
    if (isNativeVirtualUidConfigured()) {
        buf->st_uid = virtualUid();
        buf->st_gid = virtualGid();
    }
}

void maybeSanitizeProcCmdlineStat(int result,
                                  const char *pathname,
                                  const char *redirected,
                                  struct stat *buf) {
    if (result == 0 && isProcCmdlineProbePath(pathname, redirected)) {
        sanitizeProcCmdlineStat(buf);
    }
}

void maybeSanitizeProcCmdlineFdStat(int result, int fd, struct stat *buf) {
    if (result == 0 && isProcCmdlineFd(fd)) {
        sanitizeProcCmdlineStat(buf);
    }
}

bool denyProcCmdlineAccessIfNeeded(const char *pathname, const char *redirected, int mode) {
    if (!isProcCmdlineProbePath(pathname, redirected)) {
        return false;
    }
    if ((mode & (W_OK | X_OK)) == 0) {
        return false;
    }
    errno = EACCES;
    return true;
}

bool isProcStatusProbePath(const char *pathname, const char *redirected);

bool isProcStatusFd(int fd) {
    if (fd < 0) {
        return false;
    }
    char fd_path[64];
    char target[PATH_MAX];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", fd);
    ssize_t length = callRawReadlink(fd_path, target, sizeof(target) - 1);
    if (length <= 0) {
        return false;
    }
    target[length] = '\0';
    return isProcStatusProbePath(target, nullptr)
           || strstr(target, "bb_proc_status") != nullptr;
}

void sanitizeProcStatusStat(struct stat *buf) {
    if (buf == nullptr) {
        return;
    }
    buf->st_mode = S_IFREG | 0444;
    buf->st_nlink = 1;
    buf->st_size = 0;
    if (isNativeVirtualUidConfigured()) {
        buf->st_uid = virtualUid();
        buf->st_gid = virtualGid();
    }
}

void maybeSanitizeProcStatusStat(int result,
                                 const char *pathname,
                                 const char *redirected,
                                 struct stat *buf) {
    if (result == 0 && isProcStatusProbePath(pathname, redirected)) {
        sanitizeProcStatusStat(buf);
    }
}

void maybeSanitizeProcStatusFdStat(int result, int fd, struct stat *buf) {
    if (result == 0 && isProcStatusFd(fd)) {
        sanitizeProcStatusStat(buf);
    }
}

void sanitizeVirtualOwnerStat(struct stat *buf) {
    if (buf == nullptr || !isNativeVirtualUidConfigured()) {
        return;
    }
    int host_uid = rawHostUid();
    int host_gid = rawHostGid();
    if (host_uid >= 0 && buf->st_uid == static_cast<uid_t>(gNativeHostUid)) {
        buf->st_uid = virtualUid();
    }
    if (host_gid >= 0 && buf->st_gid == static_cast<gid_t>(gNativeHostGid)) {
        buf->st_gid = virtualGid();
    }
}

void maybeSanitizeVirtualOwnerStat(int result, struct stat *buf) {
    if (result == 0) {
        sanitizeVirtualOwnerStat(buf);
    }
}

void maybeSanitizeVirtualOwnerFdStat(int result, int fd, struct stat *buf) {
    (void) fd;
    maybeSanitizeVirtualOwnerStat(result, buf);
}

void sanitizeVirtualOwnerStatx(struct statx *buf) {
    if (buf == nullptr || !isNativeVirtualUidConfigured()) {
        return;
    }
    int host_uid = rawHostUid();
    int host_gid = rawHostGid();
    if (host_uid >= 0 && buf->stx_uid == static_cast<__u32>(gNativeHostUid)) {
        buf->stx_uid = static_cast<__u32>(virtualUid());
    }
    if (host_gid >= 0 && buf->stx_gid == static_cast<__u32>(gNativeHostGid)) {
        buf->stx_gid = static_cast<__u32>(virtualGid());
    }
}

void maybeSanitizeVirtualOwnerStatx(int result, struct statx *buf) {
    if (result == 0) {
        sanitizeVirtualOwnerStatx(buf);
    }
}

void sanitizeProcShimStat(struct stat *buf) {
    if (buf == nullptr) {
        return;
    }
    buf->st_mode = (buf->st_mode & ~S_IFMT) | S_IFREG;
    buf->st_nlink = 1;
    buf->st_size = 0;
}

#if !defined(__LP64__)
void sanitizeProcShimStat64(struct stat64 *buf) {
    if (buf == nullptr) {
        return;
    }
    buf->st_mode = (buf->st_mode & ~S_IFMT) | S_IFREG;
    buf->st_nlink = 1;
    buf->st_size = 0;
}

void sanitizeProcMapsStat64(struct stat64 *buf) {
    if (buf == nullptr) {
        return;
    }
    buf->st_mode = S_IFREG | 0444;
    buf->st_nlink = 1;
    buf->st_size = 0;
    if (isNativeVirtualUidConfigured()) {
        buf->st_uid = virtualUid();
        buf->st_gid = virtualGid();
    }
}

void maybeSanitizeProcMapsFdStat64(int result, int fd, struct stat64 *buf) {
    if (result == 0 && isVirtualProcMapsFd(fd)) {
        sanitizeProcMapsStat64(buf);
    }
}

void sanitizeProcCmdlineStat64(struct stat64 *buf) {
    if (buf == nullptr) {
        return;
    }
    buf->st_mode = S_IFREG | 0444;
    buf->st_nlink = 1;
    buf->st_size = 0;
    if (isNativeVirtualUidConfigured()) {
        buf->st_uid = virtualUid();
        buf->st_gid = virtualGid();
    }
}

void maybeSanitizeProcCmdlineStat64(int result,
                                    const char *pathname,
                                    const char *redirected,
                                    struct stat64 *buf) {
    if (result == 0 && isProcCmdlineProbePath(pathname, redirected)) {
        sanitizeProcCmdlineStat64(buf);
    }
}

void maybeSanitizeProcCmdlineFdStat64(int result, int fd, struct stat64 *buf) {
    if (result == 0 && isProcCmdlineFd(fd)) {
        sanitizeProcCmdlineStat64(buf);
    }
}

void sanitizeProcStatusStat64(struct stat64 *buf) {
    if (buf == nullptr) {
        return;
    }
    buf->st_mode = S_IFREG | 0444;
    buf->st_nlink = 1;
    buf->st_size = 0;
    if (isNativeVirtualUidConfigured()) {
        buf->st_uid = virtualUid();
        buf->st_gid = virtualGid();
    }
}

void maybeSanitizeProcStatusStat64(int result,
                                   const char *pathname,
                                   const char *redirected,
                                   struct stat64 *buf) {
    if (result == 0 && isProcStatusProbePath(pathname, redirected)) {
        sanitizeProcStatusStat64(buf);
    }
}

void maybeSanitizeProcStatusFdStat64(int result, int fd, struct stat64 *buf) {
    if (result == 0 && isProcStatusFd(fd)) {
        sanitizeProcStatusStat64(buf);
    }
}

void sanitizeVirtualOwnerStat64(struct stat64 *buf) {
    if (buf == nullptr || !isNativeVirtualUidConfigured()) {
        return;
    }
    int host_uid = rawHostUid();
    int host_gid = rawHostGid();
    if (host_uid >= 0 && buf->st_uid == static_cast<uid_t>(gNativeHostUid)) {
        buf->st_uid = virtualUid();
    }
    if (host_gid >= 0 && buf->st_gid == static_cast<gid_t>(gNativeHostGid)) {
        buf->st_gid = virtualGid();
    }
}

void maybeSanitizeVirtualOwnerStat64(int result, struct stat64 *buf) {
    if (result == 0) {
        sanitizeVirtualOwnerStat64(buf);
    }
}

void maybeSanitizeVirtualOwnerFdStat64(int result, int fd, struct stat64 *buf) {
    (void) fd;
    maybeSanitizeVirtualOwnerStat64(result, buf);
}
#endif

void maybeSanitizeProcShimStat(int result, int fd, struct stat *buf) {
    if (result == 0 && isActiveProcShimFd(fd)) {
        sanitizeProcShimStat(buf);
    }
}

#if !defined(__LP64__) && (!defined(__ANDROID_API__) || __ANDROID_API__ < 21)
void maybeSanitizeProcShimStat64(int result, int fd, struct stat64 *buf) {
    if (result == 0 && isActiveProcShimFd(fd)) {
        sanitizeProcShimStat64(buf);
    }
}
#endif

void logStatPath(const char *api, const char *pathname, const char *redirected, int result, bool proc_shim, void *caller) {
    if (isInternalFileProbe()) {
        return;
    }
    if (!isFileProbeEnabled()) {
        return;
    }
    int result_errno = result < 0 ? errno : 0;
    if (!proc_shim && !shouldLogOpenPath(pathname, redirected)) {
        return;
    }
    CallerLocation caller_location = {};
    resolveCallerLocation(caller, &caller_location);
    rememberRecentNativeFileProbe(api, pathname, redirected, proc_shim ? 1 : 0, result,
                                  result_errno, caller, &caller_location);
    ALOGD("native stat probe api=%s path=%s redirected=%s procShim=%d result=%d errno=%d caller=%p callerOff=0x%lx callerMap=%s",
          api == nullptr ? "unknown" : api,
          pathname == nullptr ? "null" : pathname,
          redirected == nullptr ? "null" : redirected,
          proc_shim ? 1 : 0,
          result,
          result_errno,
          caller,
          static_cast<unsigned long>(caller_location.offset),
          caller_location.path);
}

void logFdStat(const char *api, int fd, int result) {
    if (isInternalFileProbe()) {
        return;
    }
    if (!isFileProbeEnabled()) {
        return;
    }
    if (!isActiveProcShimFd(fd)) {
        return;
    }
    ALOGD("native stat probe api=%s fd=%d procShim=1 result=%d",
          api == nullptr ? "unknown" : api,
          fd,
          result);
}

void logReadlinkPath(const char *api,
                     const char *pathname,
                     const char *redirected,
                     const char *target,
                     ssize_t result,
                     bool proc_shim,
                     void *caller) {
    if (isInternalFileProbe()) {
        return;
    }
    if (!isFileProbeEnabled()) {
        return;
    }
    int result_errno = result < 0 ? errno : 0;
    if (!proc_shim && !shouldLogOpenPath(pathname, redirected)) {
        return;
    }
    CallerLocation caller_location = {};
    resolveCallerLocation(caller, &caller_location);
    rememberRecentNativeFileProbe(api, pathname, redirected, proc_shim ? 1 : 0,
                                  static_cast<long>(result), result_errno, caller,
                                  &caller_location);
    ALOGD("native readlink probe api=%s path=%s redirected=%s target=%s procShim=%d result=%ld errno=%d caller=%p callerOff=0x%lx callerMap=%s",
          api == nullptr ? "unknown" : api,
          pathname == nullptr ? "null" : pathname,
          redirected == nullptr ? "null" : redirected,
          target == nullptr ? "null" : target,
          proc_shim ? 1 : 0,
          static_cast<long>(result),
          result_errno,
          caller,
          static_cast<unsigned long>(caller_location.offset),
          caller_location.path);
}

const char *sanitizeDladdrPath(const char *path) {
    static const char *kBlackBoxDataUser = "/blackbox/data/user/";
    static const char *kAndroidDataData = "/data/data/";

    if (path == nullptr) {
        return path;
    }

    const char *marker = strstr(path, kBlackBoxDataUser);
    if (marker == nullptr) {
        return path;
    }

    const char *user_id = marker + strlen(kBlackBoxDataUser);
    const char *package_name = strchr(user_id, '/');
    if (package_name == nullptr || package_name[1] == '\0') {
        return path;
    }
    package_name++;

    const char *package_end = strchr(package_name, '/');
    if (package_end == nullptr || package_end == package_name) {
        return path;
    }

    size_t package_length = static_cast<size_t>(package_end - package_name);
    int written = snprintf(gSanitizedDladdrPath,
                           sizeof(gSanitizedDladdrPath),
                           "%s%.*s%s",
                           kAndroidDataData,
                           static_cast<int>(package_length),
                           package_name,
                           package_end);
    if (written <= 0 || static_cast<size_t>(written) >= sizeof(gSanitizedDladdrPath)) {
        return path;
    }
    return gSanitizedDladdrPath;
}

void logDladdrPath(const char *original, const char *sanitized, int result) {
    if (samePath(original, sanitized)
        && !containsPathPart(original, "/blackbox/data/user/")) {
        return;
    }
    ALOGD("native dladdr probe path=%s sanitized=%s result=%d",
          original == nullptr ? "null" : original,
          sanitized == nullptr ? "null" : sanitized,
          result);
}

mode_t takeModeArg(int flags, va_list args) {
    if (!needsModeArg(flags)) {
        return 0;
    }
    return static_cast<mode_t>(va_arg(args, int));
}

const char *redirectAbsolutePath(const char *pathname) {
    if (pathname == nullptr || pathname[0] == '\0') {
        return pathname;
    }
    if (pathname[0] != '/') {
        return pathname;
    }
    const char *proc_shim = redirectProcProbeToShim(pathname);
    if (proc_shim != nullptr) {
        return proc_shim;
    }
    return IO::redirectPath(pathname);
}

const char *redirectMetadataPath(const char *pathname) {
    return redirectAbsolutePath(pathname);
}

const char *redirectDirectoryPath(const char *pathname) {
    return redirectAbsolutePath(pathname);
}

const char *redirectFilesystemPath(const char *pathname) {
    return redirectAbsolutePath(pathname);
}

bool resolveDirFd(int dirfd, char *buffer, size_t buffer_size) {
    if (dirfd < 0 || buffer == nullptr || buffer_size == 0) {
        return false;
    }
    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", dirfd);
    ssize_t length = callRawReadlink(fd_path, buffer, buffer_size - 1);
    if (length <= 0) {
        return false;
    }
    buffer[length] = '\0';
    return true;
}

const char *redirectOpenAtPath(int dirfd, const char *pathname, bool *use_absolute) {
    if (use_absolute != nullptr) {
        *use_absolute = false;
    }
    if (pathname == nullptr || pathname[0] == '\0') {
        return pathname;
    }
    if (pathname[0] == '/') {
        return redirectAbsolutePath(pathname);
    }

    char dir_path[PATH_MAX];
    if (!resolveDirFd(dirfd, dir_path, sizeof(dir_path))) {
        return pathname;
    }
    if (strcmp(dir_path, "/proc") != 0 && strncmp(dir_path, "/proc/", 6) != 0) {
        return pathname;
    }

    char absolute_path[PATH_MAX];
    snprintf(absolute_path, sizeof(absolute_path), "%s/%s", dir_path, pathname);
    const char *redirected = redirectAbsolutePath(absolute_path);
    if (redirected == absolute_path) {
        return pathname;
    }
    if (use_absolute != nullptr) {
        *use_absolute = true;
    }
    return redirected;
}

const char *redirectMetadataAtPath(int dirfd, const char *pathname, bool *use_absolute) {
    return redirectOpenAtPath(dirfd, pathname, use_absolute);
}

const char *redirectDirectoryAtPath(int dirfd, const char *pathname, bool *use_absolute) {
    return redirectOpenAtPath(dirfd, pathname, use_absolute);
}

ResolvedPath resolveOpenAtPathForLog(int dirfd, const char *pathname) {
    ResolvedPath resolved = {};
    resolved.path = pathname;
    if (pathname == nullptr || pathname[0] == '\0' || pathname[0] == '/') {
        return resolved;
    }

    char dir_path[PATH_MAX];
    if (!resolveDirFd(dirfd, dir_path, sizeof(dir_path))) {
        return resolved;
    }

    int required = snprintf(resolved.storage, sizeof(resolved.storage), "%s/%s", dir_path, pathname);
    if (required <= 0 || static_cast<size_t>(required) >= sizeof(resolved.storage)) {
        return resolved;
    }
    resolved.path = resolved.storage;
    return resolved;
}

bool resolveAtPathForProcShim(int dirfd, const char *pathname, char *buffer, size_t buffer_size, int *fd) {
    if (pathname == nullptr || pathname[0] == '\0') {
        return false;
    }
    if (pathname[0] == '/') {
        return isProcShimFdPath(pathname, fd);
    }
    if (dirfd == AT_FDCWD) {
        return isProcShimFdPath(pathname, fd);
    }

    char dir_path[PATH_MAX];
    if (!resolveDirFd(dirfd, dir_path, sizeof(dir_path))) {
        return false;
    }
    int required = snprintf(buffer, buffer_size, "%s/%s", dir_path, pathname);
    if (required <= 0 || static_cast<size_t>(required) >= buffer_size) {
        return false;
    }
    return isProcShimFdPath(buffer, fd);
}

int callOpen(OpenFn fn, const char *pathname, int flags, mode_t mode) {
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    if (needsModeArg(flags)) {
        return fn(pathname, flags, mode);
    }
    return fn(pathname, flags);
}

int callOpenAt(OpenAtFn fn, int dirfd, const char *pathname, int flags, mode_t mode) {
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    if (needsModeArg(flags)) {
        return fn(dirfd, pathname, flags, mode);
    }
    return fn(dirfd, pathname, flags);
}

long callSyscall(SyscallFn fn, long number, long args[6]) {
    (void) fn;
    return callKernelSyscall(number, args);
}

int rawDirectOpenAt(int dirfd, const char *pathname, int flags, mode_t mode) {
#ifdef __NR_openat
    return static_cast<int>(callKernelSyscall(__NR_openat,
                                              static_cast<long>(dirfd),
                                              reinterpret_cast<long>(pathname),
                                              static_cast<long>(flags),
                                              static_cast<long>(mode)));
#else
    if (dirfd != AT_FDCWD) {
        errno = ENOSYS;
        return -1;
    }
#ifdef __NR_open
    return static_cast<int>(callKernelSyscall(__NR_open,
                                              reinterpret_cast<long>(pathname),
                                              static_cast<long>(flags),
                                              static_cast<long>(mode)));
#else
    errno = ENOSYS;
    return -1;
#endif
#endif
}

bool openFlagsRequireMode(long flags) {
    if ((flags & O_CREAT) != 0) {
        return true;
    }
#ifdef O_TMPFILE
    return (flags & O_TMPFILE) == O_TMPFILE;
#else
    return false;
#endif
}

int syscallArgumentCount(long number) {
    switch (number) {
#ifdef __NR_getuid
        case __NR_getuid:
            return 0;
#endif
#if defined(__NR_getuid32) && (!defined(__NR_getuid) || __NR_getuid32 != __NR_getuid)
        case __NR_getuid32:
            return 0;
#endif
#ifdef __NR_geteuid
        case __NR_geteuid:
            return 0;
#endif
#if defined(__NR_geteuid32) && (!defined(__NR_geteuid) || __NR_geteuid32 != __NR_geteuid)
        case __NR_geteuid32:
            return 0;
#endif
#ifdef __NR_getgid
        case __NR_getgid:
            return 0;
#endif
#if defined(__NR_getgid32) && (!defined(__NR_getgid) || __NR_getgid32 != __NR_getgid)
        case __NR_getgid32:
            return 0;
#endif
#ifdef __NR_getegid
        case __NR_getegid:
            return 0;
#endif
#if defined(__NR_getegid32) && (!defined(__NR_getegid) || __NR_getegid32 != __NR_getegid)
        case __NR_getegid32:
            return 0;
#endif
#ifdef __NR_getgroups
        case __NR_getgroups:
            return 2;
#endif
#if defined(__NR_getgroups32) && (!defined(__NR_getgroups) || __NR_getgroups32 != __NR_getgroups)
        case __NR_getgroups32:
            return 2;
#endif
#ifdef __NR_exit
        case __NR_exit:
            return 1;
#endif
#ifdef __NR_exit_group
        case __NR_exit_group:
            return 1;
#endif
#ifdef __NR_kill
        case __NR_kill:
            return 2;
#endif
#ifdef __NR_tkill
        case __NR_tkill:
            return 2;
#endif
#ifdef __NR_tgkill
        case __NR_tgkill:
            return 3;
#endif
#ifdef __NR_fork
        case __NR_fork:
            return 0;
#endif
#ifdef __NR_vfork
        case __NR_vfork:
            return 0;
#endif
#ifdef __NR_clone
        case __NR_clone:
            return 5;
#endif
#ifdef __NR_execve
        case __NR_execve:
            return 3;
#endif
#ifdef __NR_mkdir
        case __NR_mkdir:
            return 2;
#endif
#ifdef __NR_mkdirat
        case __NR_mkdirat:
            return 3;
#endif
#ifdef __NR_faccessat
        case __NR_faccessat:
            return 4;
#endif
#ifdef __NR_newfstatat
        case __NR_newfstatat:
            return 4;
#endif
#if defined(__NR_fstatat64) && (!defined(__NR_newfstatat) || __NR_fstatat64 != __NR_newfstatat)
        case __NR_fstatat64:
            return 4;
#endif
#ifdef __NR_statx
        case __NR_statx:
            return 5;
#endif
#ifdef __NR_statfs
        case __NR_statfs:
            return 2;
#endif
#ifdef __NR_statfs64
        case __NR_statfs64:
            return 3;
#endif
#ifdef __NR_readlinkat
        case __NR_readlinkat:
            return 4;
#endif
        default:
            return 6;
    }
}

void takeFixedSyscallArgs(va_list args, long values[6], int count) {
    if (count < 0) {
        count = 0;
    }
    if (count > 6) {
        count = 6;
    }
    for (int i = 0; i < count; i++) {
        values[i] = va_arg(args, long);
    }
}

void takeOpenSyscallArgs(va_list args, long values[6]) {
    values[0] = va_arg(args, long);
    values[1] = va_arg(args, long);
    if (openFlagsRequireMode(values[1])) {
        values[2] = va_arg(args, long);
    }
}

void takeOpenAtSyscallArgs(va_list args, long values[6]) {
    values[0] = va_arg(args, long);
    values[1] = va_arg(args, long);
    values[2] = va_arg(args, long);
    if (openFlagsRequireMode(values[2])) {
        values[3] = va_arg(args, long);
    }
}

void takeSyscallArgsForNumber(long number, va_list args, long values[6]) {
    switch (number) {
#ifdef __NR_open
        case __NR_open:
            takeOpenSyscallArgs(args, values);
            return;
#endif
#ifdef __NR_openat
        case __NR_openat:
            takeOpenAtSyscallArgs(args, values);
            return;
#endif
        default:
            takeFixedSyscallArgs(args, values, syscallArgumentCount(number));
            return;
    }
}

bool writeExact(int fd, const void *data, size_t length) {
    const uint8_t *cursor = reinterpret_cast<const uint8_t *>(data);
    size_t remaining = length;
    while (remaining > 0) {
        ssize_t written = write(fd, cursor, remaining);
        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            return false;
        }
        if (written == 0) {
            return false;
        }
        cursor += written;
        remaining -= static_cast<size_t>(written);
    }
    return true;
}

FILE *openRealProcMapsFile() {
    ScopedInternalFileProbe internal_probe;
    static FopenFn real_fopen = nullptr;
    if (real_fopen == nullptr) {
        real_fopen = reinterpret_cast<FopenFn>(dlsym(RTLD_NEXT, "fopen"));
    }
    if (real_fopen == nullptr) {
        return nullptr;
    }
    return real_fopen("/proc/self/maps", "re");
}

FILE *openRealProcStatusFile() {
    ScopedInternalFileProbe internal_probe;
    static FopenFn real_fopen = nullptr;
    if (real_fopen == nullptr) {
        real_fopen = reinterpret_cast<FopenFn>(dlsym(RTLD_NEXT, "fopen"));
    }
    if (real_fopen == nullptr) {
        return nullptr;
    }
    return real_fopen("/proc/self/status", "re");
}

FILE *openRealProcCgroupFile() {
    ScopedInternalFileProbe internal_probe;
    static FopenFn real_fopen = nullptr;
    if (real_fopen == nullptr) {
        real_fopen = reinterpret_cast<FopenFn>(dlsym(RTLD_NEXT, "fopen"));
    }
    if (real_fopen == nullptr) {
        return nullptr;
    }
    return real_fopen("/proc/self/cgroup", "re");
}

FILE *openRealProcAttrCurrentFile() {
    ScopedInternalFileProbe internal_probe;
    static FopenFn real_fopen = nullptr;
    if (real_fopen == nullptr) {
        real_fopen = reinterpret_cast<FopenFn>(dlsym(RTLD_NEXT, "fopen"));
    }
    if (real_fopen == nullptr) {
        return nullptr;
    }
    return real_fopen("/proc/self/attr/current", "re");
}

void replaceAll(std::string *value, const char *needle, const char *replacement) {
    if (value == nullptr || needle == nullptr || replacement == nullptr || needle[0] == '\0') {
        return;
    }
    size_t pos = 0;
    const size_t needle_len = strlen(needle);
    const size_t replacement_len = strlen(replacement);
    while ((pos = value->find(needle, pos)) != std::string::npos) {
        value->replace(pos, needle_len, replacement);
        pos += replacement_len;
    }
}

void replaceFirstNumericToken(std::string *value, const char *prefix, int replacement) {
    if (value == nullptr || prefix == nullptr || prefix[0] == '\0') {
        return;
    }
    size_t pos = value->find(prefix);
    if (pos == std::string::npos) {
        return;
    }
    size_t number_start = pos + strlen(prefix);
    size_t number_end = number_start;
    while (number_end < value->size() && (*value)[number_end] >= '0' && (*value)[number_end] <= '9') {
        number_end++;
    }
    if (number_end == number_start) {
        return;
    }
    char replacement_buffer[32];
    snprintf(replacement_buffer, sizeof(replacement_buffer), "%d", replacement);
    value->replace(number_start, number_end - number_start, replacement_buffer);
}

bool shouldHideEarlyMapsLine(const char *line) {
    return containsPathPart(line, kBlackBoxHostPackagePrefix)
           || containsPathPart(line, "libblackbox.so")
           || containsPathPart(line, "libblackhook.so")
           || containsPathPart(line, "libblackdex.so")
           || containsPathPart(line, "libpine.so")
           || containsPathPart(line, "[anon:pine codes]");
}

bool shouldHideEarlyRawMapsLine(const char *line) {
    return !containsPathPart(line, "/blackbox/data/user/")
           && shouldHideEarlyMapsLine(line);
}

bool isWritableExecutableProcMapsLine(const char *line) {
    if (line == nullptr) {
        return false;
    }
    const char *perms = strchr(line, ' ');
    if (perms == nullptr) {
        return false;
    }
    while (*perms == ' ') {
        perms++;
    }
    return perms[0] != '\0'
           && perms[1] == 'w'
           && perms[2] == 'x';
}

const char *currentVirtualPackageForProcMaps() {
    if (gEarlyProcMapsPackage[0] != '\0') {
        return gEarlyProcMapsPackage;
    }
    return gNativeTerminationShieldPackage;
}

const char *currentVirtualProcessNameForProcStatus() {
    if (gNativeSandboxProcessName[0] != '\0') {
        return gNativeSandboxProcessName;
    }
    return currentVirtualPackageForProcMaps();
}

std::string linuxTaskCommForProcessName(const char *process_name) {
    const char *name = process_name == nullptr || process_name[0] == '\0'
                       ? currentVirtualPackageForProcMaps()
                       : process_name;
    size_t length = strlen(name);
    if (length <= kLinuxTaskCommMaxBytes) {
        return std::string(name);
    }
    return std::string(name + length - kLinuxTaskCommMaxBytes);
}

bool isFrameworkOrHookNativeCallerPath(const char *path) {
    if (path == nullptr || path[0] == '\0' || strcmp(path, "unknown") == 0 || strcmp(path, "anonymous") == 0) {
        return true;
    }
    if (containsPathPart(path, "/apex/")
        || containsPathPart(path, "/system/")
        || containsPathPart(path, "/system_ext/")
        || containsPathPart(path, "/vendor/")
        || containsPathPart(path, "/product/")) {
        return true;
    }
    if (containsPathPart(path, "libblackbox.so")
        || containsPathPart(path, "libblackhook.so")
        || containsPathPart(path, "libblackdex.so")
        || containsPathPart(path, "libpine.so")) {
        return true;
    }
    return containsPathPart(path, kBlackBoxHostPackagePrefix)
           && !containsPathPart(path, "/blackbox/data/user/");
}

bool isAppOwnedNativeCallerPath(const char *path) {
    const char *package_name = currentVirtualPackageForProcMaps();
    if (package_name[0] == '\0' || path == nullptr || !containsPathPart(path, package_name)) {
        return false;
    }
    if (isFrameworkOrHookNativeCallerPath(path)) {
        return false;
    }
    return containsPathPart(path, "/blackbox/data/user/")
           || containsPathPart(path, "/data/user/")
           || containsPathPart(path, "/data/data/")
           || containsPathPart(path, "/data/app/");
}

bool isAppOwnedNativeAddress(void *address) {
    CallerLocation location = {};
    resolveCallerLocation(address, &location);
    if (location.resolved && isAppOwnedNativeCallerPath(location.path)) {
        return true;
    }

    MemoryMapEntry entry = {};
    return resolveMemoryMapEntry(address, &entry) && isAppOwnedNativeCallerPath(entry.path);
}

bool isAnonymousExecutableAppCode(void *address) {
    if (currentVirtualPackageForProcMaps()[0] == '\0') {
        return false;
    }

    MemoryMapEntry entry = {};
    if (!resolveMemoryMapEntry(address, &entry) || entry.perms[2] != 'x') {
        return false;
    }
    if (isFrameworkOrHookNativeCallerPath(entry.path)) {
        return false;
    }
    if (containsPathPart(entry.path, "dalvik")
        || containsPathPart(entry.path, "jit")) {
        return false;
    }
    return strcmp(entry.path, "anonymous") == 0
           || strncmp(entry.path, "[anon:", 6) == 0
           || strncmp(entry.path, "/memfd:", 7) == 0;
}

uint64_t monotonicTimeNs() {
    struct timespec now = {};
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
        return 0;
    }
    return static_cast<uint64_t>(now.tv_sec) * 1000ULL * 1000ULL * 1000ULL
           + static_cast<uint64_t>(now.tv_nsec);
}

bool isWithinAppNativeLoaderMapsWindow() {
    return gAppNativeLoaderMapsTrustUntilNs != 0
           && monotonicTimeNs() <= gAppNativeLoaderMapsTrustUntilNs;
}

bool isBionicLibcCallerPath(const char *path) {
    return containsPathPart(path, "/bionic/libc.so")
           || containsPathPart(path, "/bionic/lib64/libc.so");
}

bool isAppOwnedNativeLibraryPath(const char *path) {
    const char *package_name = currentVirtualPackageForProcMaps();
    if (package_name[0] == '\0'
        || path == nullptr
        || !containsPathPart(path, package_name)
        || !endsWithPathPart(path, ".so")) {
        return false;
    }
    return containsPathPart(path, "/blackbox/data/user/")
           || containsPathPart(path, "/data/user/")
           || containsPathPart(path, "/data/data/")
           || containsPathPart(path, "/data/app/");
}

bool isAppPrivateNativeLibraryPath(const char *path) {
    const char *package_name = currentVirtualPackageForProcMaps();
    if (package_name[0] == '\0'
        || path == nullptr
        || !containsPathPart(path, package_name)
        || !endsWithPathPart(path, ".so")) {
        return false;
    }
    return containsPathPart(path, "/blackbox/data/user/")
           || containsPathPart(path, "/data/user/")
           || containsPathPart(path, "/data/data/");
}

void markAppNativeLoaderMapsWindow(const char *path) {
    uint64_t now = monotonicTimeNs();
    if (now == 0) {
        return;
    }
    gAppNativeLoaderMapsTrustUntilNs = now + kAppNativeLoaderMapsTrustWindowNs;
    if (isFileProbeEnabled()) {
        ALOGD("native app loader maps window package=%s path=%s untilNs=%llu",
              currentVirtualPackageForProcMaps(),
              path == nullptr ? "null" : path,
              static_cast<unsigned long long>(gAppNativeLoaderMapsTrustUntilNs));
    }
}

void maybeMarkAppNativeLoaderMapsWindow(const char *pathname, const char *redirected, long result) {
    if (result < 0) {
        return;
    }
    if (isAppPrivateNativeLibraryPath(pathname)) {
        markAppNativeLoaderMapsWindow(pathname);
    } else if (isAppPrivateNativeLibraryPath(redirected)) {
        markAppNativeLoaderMapsWindow(redirected);
    }
}

bool hasAppOwnedNativeFrame() {
    void *frames[kProcessProbeMaxFrames] = {};
    size_t frame_count = captureNativeBacktrace(frames, kProcessProbeMaxFrames);
    for (size_t i = 0; i < frame_count; ++i) {
        CallerLocation frame_location = {};
        resolveCallerLocation(frames[i], &frame_location);
        if (frame_location.resolved && isAppOwnedNativeCallerPath(frame_location.path)) {
            return true;
        }

        MemoryMapEntry entry = {};
        if (resolveMemoryMapEntry(frames[i], &entry)
            && isAppOwnedNativeCallerPath(entry.path)) {
            return true;
        }
    }
    return false;
}

bool shouldMarkAppOwnedThread(void *start_routine, void *caller) {
    if (isAppOwnedNativeAddress(start_routine) || isAppOwnedNativeAddress(caller)) {
        return true;
    }
    if (isAnonymousExecutableAppCode(start_routine)) {
        return true;
    }
    return hasAppOwnedNativeFrame();
}

void rememberAppOwnedNativeThread(pthread_t thread) {
    pthread_mutex_lock(&gAppOwnedNativeThreadsLock);
    for (size_t i = 0; i < gAppOwnedNativeThreadCount; ++i) {
        if (pthread_equal(gAppOwnedNativeThreads[i], thread)) {
            pthread_mutex_unlock(&gAppOwnedNativeThreadsLock);
            return;
        }
    }
    if (gAppOwnedNativeThreadCount < kMaxAppOwnedNativeThreads) {
        gAppOwnedNativeThreads[gAppOwnedNativeThreadCount++] = thread;
    } else {
        gAppOwnedNativeThreads[kMaxAppOwnedNativeThreads - 1] = thread;
    }
    pthread_mutex_unlock(&gAppOwnedNativeThreadsLock);
}

bool isCurrentThreadMarkedAppOwnedNative() {
    pthread_t current = pthread_self();
    bool marked = false;
    pthread_mutex_lock(&gAppOwnedNativeThreadsLock);
    for (size_t i = 0; i < gAppOwnedNativeThreadCount; ++i) {
        if (pthread_equal(gAppOwnedNativeThreads[i], current)) {
            marked = true;
            break;
        }
    }
    pthread_mutex_unlock(&gAppOwnedNativeThreadsLock);
    return marked;
}

struct AppOwnedPthreadStartContext {
    void *(*start_routine)(void *) = nullptr;
    void *arg = nullptr;
};

void *appOwnedPthreadStartTrampoline(void *opaque) {
    rememberAppOwnedNativeThread(pthread_self());
    if (isRawSyscallThreadRefreshEnabled()) {
        blackbox::rawsyscall::refreshRawSyscallProbeMaps();
    }
    auto *context = reinterpret_cast<AppOwnedPthreadStartContext *>(opaque);
    if (context == nullptr) {
        return nullptr;
    }

    void *(*start_routine)(void *) = context->start_routine;
    void *arg = context->arg;
    free(context);
    if (start_routine == nullptr) {
        return nullptr;
    }
    return start_routine(arg);
}

bool shouldUseAppVisibleProcMapsForCaller(void *caller) {
    if (isCurrentThreadMarkedAppOwnedNative()) {
        return true;
    }
    if (caller == nullptr) {
        return false;
    }
    CallerLocation caller_location = {};
    resolveCallerLocation(caller, &caller_location);
    if (caller_location.resolved && isAppOwnedNativeCallerPath(caller_location.path)) {
        return true;
    }
    if (isWithinAppNativeLoaderMapsWindow()
        && caller_location.resolved
        && isBionicLibcCallerPath(caller_location.path)) {
        return true;
    }
    return hasAppOwnedNativeFrame();
}

bool shouldLogAppOwnedNativeFileProbe(const char *pathname, const char *redirected, void *caller) {
    if (isAppPrivateDataProbePath(pathname) || isAppPrivateDataProbePath(redirected)) {
        return isCurrentThreadMarkedAppOwnedNative() || isAppOwnedNativeAddress(caller);
    }
    if (isCurrentThreadMarkedAppOwnedNative()) {
        return true;
    }
    if (isAppOwnedNativeAddress(caller)) {
        return true;
    }
    return hasAppOwnedNativeFrame();
}

void replaceBlackBoxDataUserRoots(std::string *value) {
    if (value == nullptr) {
        return;
    }
    static const char *kMarker = "/blackbox/data/user/";
    static const char *kDataData = "/data/data/";
    static const char *kPublicRoot = "/data/user/";

    size_t pos = 0;
    while ((pos = value->find(kMarker, pos)) != std::string::npos) {
        size_t prefix = value->rfind(kDataData, pos);
        size_t replace_start = prefix == std::string::npos ? pos : prefix;
        size_t replace_end = pos + strlen(kMarker);
        value->replace(replace_start, replace_end - replace_start, kPublicRoot);
        pos = replace_start + strlen(kPublicRoot);
    }
}

std::string sanitizeProcMapsPathOnlyLine(const char *line) {
    std::string sanitized(line == nullptr ? "" : line);
    replaceBlackBoxDataUserRoots(&sanitized);
    replaceAll(&sanitized, "/blackbox/data/user/", "/data/user/");
    return sanitized;
}

std::string sanitizeEarlyMapsLineForPackage(const char *line, const char *package_name);

std::string sanitizeEarlyMapsLine(const char *line) {
    return sanitizeEarlyMapsLineForPackage(line, currentVirtualPackageForProcMaps());
}

std::string sanitizeEarlyMapsLineForPackage(const char *line, const char *package_name) {
    std::string sanitized(line == nullptr ? "" : line);
    replaceBlackBoxDataUserRoots(&sanitized);
    if (package_name != nullptr && package_name[0] != '\0') {
        replaceAll(&sanitized, kBlackBoxHostPackagePrefix, package_name);
    }
    replaceAll(&sanitized, "/blackbox/data/user/", "/data/user/");
    replaceAll(&sanitized, "/blackbox/", "/data/");
    return sanitized;
}

std::string virtualGroupsSpaceSeparated() {
    gid_t groups[8] = {};
    int count = buildVirtualGroups(groups, 8);
    std::string out;
    char scratch[32];
    for (int i = 0; i < count; ++i) {
        if (!out.empty()) {
            out += " ";
        }
        snprintf(scratch, sizeof(scratch), "%d", static_cast<int>(groups[i]));
        out += scratch;
    }
    return out;
}

std::string rewriteProcStatusIdentityLine(const char *line) {
    if (!isNativeVirtualUidConfigured() || line == nullptr) {
        return std::string(line == nullptr ? "" : line);
    }
    char buffer[256];
    if (strncmp(line, "Name:", 5) == 0) {
        std::string task_comm = linuxTaskCommForProcessName(currentVirtualProcessNameForProcStatus());
        snprintf(buffer, sizeof(buffer), "Name:\t%s\n", task_comm.c_str());
        return buffer;
    }
    if (strncmp(line, "Uid:", 4) == 0) {
        snprintf(buffer, sizeof(buffer), "Uid:\t%d\t%d\t%d\t%d\n",
                 gNativeVirtualUid, gNativeVirtualUid, gNativeVirtualUid, gNativeVirtualUid);
        return buffer;
    }
    if (strncmp(line, "Gid:", 4) == 0) {
        snprintf(buffer, sizeof(buffer), "Gid:\t%d\t%d\t%d\t%d\n",
                 gNativeVirtualUid, gNativeVirtualUid, gNativeVirtualUid, gNativeVirtualUid);
        return buffer;
    }
    if (strncmp(line, "Groups:", 7) == 0) {
        std::string groups = "Groups:\t";
        groups += virtualGroupsSpaceSeparated();
        groups += " \n";
        return groups;
    }
    return std::string(line);
}

std::string rewriteProcCgroupIdentityLine(const char *line) {
    std::string rewritten(line == nullptr ? "" : line);
    if (!isNativeVirtualUidConfigured() || rewritten.find(":cpuacct:") == std::string::npos) {
        return rewritten;
    }
    replaceFirstNumericToken(&rewritten, "/uid_", virtualUid());
    replaceFirstNumericToken(&rewritten, "/pid_", getpid());
    return rewritten;
}

bool writeVirtualProcCgroupFallback(int fd) {
    if (!isNativeVirtualUidConfigured()) {
        return false;
    }
    char fallback[256];
    int written = snprintf(fallback, sizeof(fallback),
                           "7:schedtune:/top-app\n"
                           "6:memory:/\n"
                           "5:freezer:/\n"
                           "4:cpuset:/top-app\n"
                           "3:cpuacct:/uid_%d/pid_%d\n"
                           "2:cpu:/\n"
                           "1:blkio:/\n"
                           "0::/\n",
                           virtualUid(), getpid());
    return written > 0
           && static_cast<size_t>(written) < sizeof(fallback)
           && writeExact(fd, fallback, static_cast<size_t>(written));
}

bool writeVirtualProcCgroupFile(int fd) {
    FILE *cgroup = openRealProcCgroupFile();
    if (cgroup == nullptr) {
        return writeVirtualProcCgroupFallback(fd);
    }

    char line[4096];
    bool wrote_any = false;
    bool ok = true;
    while (fgets(line, sizeof(line), cgroup) != nullptr) {
        std::string rewritten = rewriteProcCgroupIdentityLine(line);
        if (!writeExact(fd, rewritten.data(), rewritten.size())) {
            ok = false;
            break;
        }
        wrote_any = true;
    }
    fclose(cgroup);
    return ok && (wrote_any || writeVirtualProcCgroupFallback(fd));
}

int selinuxCategoryForUid(int uid) {
    int app_id = uid % 100000;
    if (app_id < 0) {
        app_id += 100000;
    }
    return app_id >= 10000 ? app_id - 10000 : app_id;
}

std::string fallbackProcAttrCurrent() {
    char fallback[128];
    snprintf(fallback, sizeof(fallback),
             "u:r:untrusted_app:s0:c%d,c256,c512,c768",
             selinuxCategoryForUid(virtualUid()));
    std::string result(fallback);
    result.push_back('\0');
    return result;
}

std::string rewriteProcAttrCurrentText(const std::string &value) {
    if (!isNativeVirtualUidConfigured()) {
        return value;
    }
    std::string rewritten(value);
    if (rewritten.empty()) {
        return fallbackProcAttrCurrent();
    }

    int host_uid = rawHostUid();
    int host_category = host_uid >= 0 ? selinuxCategoryForUid(host_uid) : -1;
    int virtual_category = selinuxCategoryForUid(virtualUid());
    if (host_category >= 0 && host_category != virtual_category) {
        char host_token[32];
        char virtual_token[32];
        snprintf(host_token, sizeof(host_token), "c%d", host_category);
        snprintf(virtual_token, sizeof(virtual_token), "c%d", virtual_category);
        size_t pos = rewritten.find(host_token);
        if (pos != std::string::npos) {
            rewritten.replace(pos, strlen(host_token), virtual_token);
            return rewritten;
        }
    }
    replaceFirstNumericToken(&rewritten, ":s0:c", virtual_category);
    return rewritten;
}

bool writeVirtualProcAttrCurrentFile(int fd) {
    FILE *attr = openRealProcAttrCurrentFile();
    if (attr == nullptr) {
        std::string fallback = fallbackProcAttrCurrent();
        return writeExact(fd, fallback.data(), fallback.size());
    }

    char buffer[512] = {};
    size_t read_bytes = fread(buffer, 1, sizeof(buffer) - 1, attr);
    fclose(attr);
    if (read_bytes == 0) {
        std::string fallback = fallbackProcAttrCurrent();
        return writeExact(fd, fallback.data(), fallback.size());
    }
    buffer[read_bytes] = '\0';
    std::string raw(buffer, read_bytes);
    std::string rewritten = rewriteProcAttrCurrentText(raw);
    return writeExact(fd, rewritten.data(), rewritten.size());
}

bool writeVirtualProcStatusFile(int fd) {
    FILE *status = openRealProcStatusFile();
    if (status == nullptr) {
        if (!isNativeVirtualUidConfigured()) {
            return false;
        }
        std::string task_comm = linuxTaskCommForProcessName(currentVirtualProcessNameForProcStatus());
        char fallback[512];
        int written = snprintf(fallback, sizeof(fallback),
                               "Name:\t%s\n"
                               "State:\tR (running)\n"
                               "Tgid:\t%d\n"
                               "Pid:\t%d\n"
                               "PPid:\t%d\n"
                               "TracerPid:\t0\n"
                               "Uid:\t%d\t%d\t%d\t%d\n"
                               "Gid:\t%d\t%d\t%d\t%d\n"
                               "Groups:\t%s \n"
                               "NoNewPrivs:\t0\n"
                               "Seccomp:\t2\n",
                               task_comm.c_str(),
                               getpid(), getpid(), getppid(),
                               gNativeVirtualUid, gNativeVirtualUid, gNativeVirtualUid, gNativeVirtualUid,
                               gNativeVirtualUid, gNativeVirtualUid, gNativeVirtualUid, gNativeVirtualUid,
                               virtualGroupsSpaceSeparated().c_str());
        return written > 0
               && static_cast<size_t>(written) < sizeof(fallback)
               && writeExact(fd, fallback, static_cast<size_t>(written));
    }

    char line[4096];
    bool ok = true;
    while (fgets(line, sizeof(line), status) != nullptr) {
        std::string rewritten = rewriteProcStatusIdentityLine(line);
        if (!writeExact(fd, rewritten.data(), rewritten.size())) {
            ok = false;
            break;
        }
    }
    fclose(status);
    return ok;
}

bool writeProcMapsPathOnlyFile(int fd) {
    FILE *maps = openRealProcMapsFile();
    if (maps == nullptr) {
        return false;
    }

    char line[4096];
    bool wrote_any = false;
    bool ok = true;
    while (fgets(line, sizeof(line), maps) != nullptr) {
        if (shouldHideEarlyRawMapsLine(line)) {
            continue;
        }
        std::string sanitized = sanitizeProcMapsPathOnlyLine(line);
        if (isWritableExecutableProcMapsLine(sanitized.c_str())
            || shouldHideEarlyMapsLine(sanitized.c_str())) {
            continue;
        }
        if (!writeExact(fd, sanitized.data(), sanitized.size())) {
            ok = false;
            break;
        }
        wrote_any = true;
    }
    fclose(maps);
    if (!ok) {
        return false;
    }
    return wrote_any;
}

bool writeEarlyProcMapsFileForPackage(int fd, const char *package_name) {
    FILE *maps = openRealProcMapsFile();
    if (maps == nullptr) {
        return false;
    }

    char line[4096];
    bool wrote_any = false;
    bool ok = true;
    while (fgets(line, sizeof(line), maps) != nullptr) {
        if (shouldHideEarlyRawMapsLine(line)) {
            continue;
        }
        std::string sanitized = sanitizeEarlyMapsLineForPackage(line, package_name);
        if (shouldHideEarlyMapsLine(sanitized.c_str())) {
            continue;
        }
        if (!writeExact(fd, sanitized.data(), sanitized.size())) {
            ok = false;
            break;
        }
        wrote_any = true;
    }
    fclose(maps);
    if (!ok) {
        return false;
    }
    return wrote_any;
}

bool writeEarlyProcMapsFile(int fd) {
    return writeEarlyProcMapsFileForPackage(fd, currentVirtualPackageForProcMaps());
}

bool shouldHideAppVisibleMapsLine(const char *line) {
    return shouldHideEarlyRawMapsLine(line);
}

bool writeAppVisibleProcMapsFile(int fd) {
    FILE *maps = openRealProcMapsFile();
    if (maps == nullptr) {
        return false;
    }

    char line[4096];
    bool wrote_any = false;
    bool ok = true;
    while (fgets(line, sizeof(line), maps) != nullptr) {
        if (shouldHideAppVisibleMapsLine(line)) {
            continue;
        }
        std::string sanitized = sanitizeEarlyMapsLine(line);
        if (shouldHideEarlyMapsLine(sanitized.c_str())) {
            continue;
        }
        if (!writeExact(fd, sanitized.data(), sanitized.size())) {
            ok = false;
            break;
        }
        wrote_any = true;
    }
    fclose(maps);
    if (!ok) {
        return false;
    }
    return wrote_any;
}

int createAnonymousProcFd(const char *name) {
#ifdef __NR_memfd_create
    SyscallFn fn = resolveSymbol(&gOrigSyscall, "syscall");
    long args[6] = {
            reinterpret_cast<long>(name == nullptr ? "bb_proc" : name),
            static_cast<long>(MFD_CLOEXEC),
            0, 0, 0, 0
    };
    int fd = static_cast<int>(callSyscall(fn, __NR_memfd_create, args));
    if (fd >= 0) {
        return fd;
    }
#endif
    errno = ENOSYS;
    return -1;
}

int createEarlyProcMapsFd() {
    return createAnonymousProcFd("bb_proc_maps");
}

bool isProcStatusProbePath(const char *pathname, const char *redirected) {
    return isCurrentProcessProcPath(pathname, "status")
           || isCurrentProcessProcPath(redirected, "status");
}

bool isProcCgroupProbePath(const char *pathname) {
    return isCurrentProcessProcPath(pathname, "cgroup");
}

bool isProcAttrCurrentProbePath(const char *pathname) {
    return isCurrentProcessProcPath(pathname, "attr/current");
}

int openVirtualProcFdForRead(const char *pathname,
                             const char *name,
                             bool (*writer)(int)) {
    if (!isNativeVirtualUidConfigured()) {
        return -1;
    }
    int fd = createAnonymousProcFd(name);
    if (fd < 0) {
        return -1;
    }
    if (!writer(fd) || lseek(fd, 0, SEEK_SET) < 0) {
        close(fd);
        return -1;
    }
    ALOGD("native proc identity virtualized path=%s fd=%d",
          pathname == nullptr ? "null" : pathname, fd);
    return fd;
}

int openVirtualProcStatusFdForRead(const char *pathname) {
    if (!isNativeVirtualUidConfigured() || !isProcStatusProbePath(pathname, nullptr)) {
        return -1;
    }
    return openVirtualProcFdForRead(pathname, "bb_proc_status", writeVirtualProcStatusFile);
}

int openVirtualProcIdentityFdForRead(const char *pathname) {
    if (isProcStatusProbePath(pathname, nullptr)) {
        return openVirtualProcStatusFdForRead(pathname);
    }
    if (isProcCgroupProbePath(pathname)) {
        return openVirtualProcFdForRead(pathname, "bb_proc_cgroup", writeVirtualProcCgroupFile);
    }
    if (isProcAttrCurrentProbePath(pathname)) {
        return openVirtualProcFdForRead(pathname, "bb_proc_attr_current", writeVirtualProcAttrCurrentFile);
    }
    return -1;
}

bool shouldUseTransientProcMaps(const char *pathname) {
    return isNativeSandboxEnvironmentConfigured()
           && !isProcShimEnabled()
           && isCurrentProcessProcPath(pathname, "maps");
}

int openTransientProcMapsFdForRead(const char *pathname, void *caller) {
    if (!shouldUseTransientProcMaps(pathname)) {
        return -1;
    }
    const bool transient_hide = isTransientProcMapsEnabled();
    if (transient_hide) {
        int fd = createAnonymousProcFd("bb_proc_maps");
        if (fd < 0) {
            return -1;
        }
        bool ok = writeEarlyProcMapsFile(fd);
        if (!ok || lseek(fd, 0, SEEK_SET) < 0) {
            close(fd);
            return -1;
        }
        if (isFileProbeEnabled()) {
            ALOGD("native proc maps transient sanitized package=%s fd=%d",
                  currentVirtualPackageForProcMaps(), fd);
        }
        return fd;
    }
    if (!isProcMapsPathSanitizationEnabled()) {
        return -1;
    }
    const bool app_visible = shouldUseAppVisibleProcMapsForCaller(caller);
    int fd = createAnonymousProcFd(app_visible ? "bb_proc_maps_app" : "bb_proc_maps_public");
    if (fd < 0) {
        return -1;
    }
    bool ok = app_visible ? writeAppVisibleProcMapsFile(fd) : writeProcMapsPathOnlyFile(fd);
    if (!ok || lseek(fd, 0, SEEK_SET) < 0) {
        close(fd);
        return -1;
    }
    if (isFileProbeEnabled()) {
        CallerLocation caller_location = {};
        resolveCallerLocation(caller, &caller_location);
        ALOGD("native proc maps %s sanitized package=%s fd=%d caller=%p callerOff=0x%lx callerMap=%s",
              app_visible ? "app-visible" : "path-only",
              currentVirtualPackageForProcMaps(),
              fd,
              caller,
              static_cast<unsigned long>(caller_location.offset),
              caller_location.path);
    }
    return fd;
}

void resetEarlyProcMapsShim() {
    if (gEarlyProcMapsReady && isProcShimFdAvailable(kProcMapsFd)) {
        close(kProcMapsFd);
    }
    gEarlyProcMapsReady = false;
    gRefreshingEarlyProcMapsShim = false;
    gEarlyProcMapsPackage[0] = '\0';
}

void prepareEarlyProcMapsShim(const char *package_name) {
    if (package_name == nullptr || package_name[0] == '\0') {
        resetEarlyProcMapsShim();
        return;
    }
    snprintf(gEarlyProcMapsPackage, sizeof(gEarlyProcMapsPackage), "%s", package_name);

    int fd = createEarlyProcMapsFd();
    if (fd < 0) {
        ALOGE("early proc maps shim create failed package=%s errno=%d", gEarlyProcMapsPackage, errno);
        gEarlyProcMapsReady = false;
        return;
    }
    if (!writeEarlyProcMapsFile(fd) || lseek(fd, 0, SEEK_SET) < 0) {
        ALOGE("early proc maps shim write failed package=%s errno=%d", gEarlyProcMapsPackage, errno);
        close(fd);
        gEarlyProcMapsReady = false;
        return;
    }
    if (fd != kProcMapsFd) {
        if (dup2(fd, kProcMapsFd) < 0) {
            ALOGE("early proc maps shim dup2 failed fd=%d target=%d errno=%d", fd, kProcMapsFd, errno);
            close(fd);
            gEarlyProcMapsReady = false;
            return;
        }
        close(fd);
    }
    lseek(kProcMapsFd, 0, SEEK_SET);
    gEarlyProcMapsReady = true;
    ALOGD("early proc maps shim prepared package=%s maps=%s",
          gEarlyProcMapsPackage, kProcMapsFdPath);
}

void refreshEarlyProcMapsShim() {
    if (!gEarlyProcMapsReady || gRefreshingEarlyProcMapsShim) {
        return;
    }
    if (!isProcShimFdAvailable(kProcMapsFd)) {
        return;
    }

    gRefreshingEarlyProcMapsShim = true;
    bool ok = ftruncate(kProcMapsFd, 0) == 0
              && lseek(kProcMapsFd, 0, SEEK_SET) == 0
              && writeEarlyProcMapsFile(kProcMapsFd)
              && lseek(kProcMapsFd, 0, SEEK_SET) == 0;
    if (!ok) {
        ALOGE("early proc maps shim refresh failed package=%s errno=%d",
              gEarlyProcMapsPackage, errno);
        lseek(kProcMapsFd, 0, SEEK_SET);
    }
    gRefreshingEarlyProcMapsShim = false;
}

} // namespace

void installDirectLibcTerminationHooks();
void installDirectLibcProcMapsHooks();
void installDirectLibcMetadataHooks();
void installDirectLibcPthreadCreateHook();
auto createNativeFunctionBackup(void *target, size_t replaced_size) -> void *;

extern "C" void setNativeFileVirtualUid(int virtual_uid) {
    rawHostUid();
    rawHostGid();
    gNativeVirtualUid = virtual_uid;
}

void setNativeSandboxEnvironmentInternal(const char *package_name, const char *process_name) {
    invalidateMemoryMapEntryCache();
    if (package_name == nullptr || package_name[0] == '\0') {
        resetEarlyProcMapsShim();
        gNativeSandboxProcessName[0] = '\0';
        gAppNativeLoaderMapsTrustUntilNs = 0;
        gNativeTerminationShieldPackage[0] = '\0';
        gNativeTerminationBlockingEnabled = false;
        gNativeTerminationShieldRootPid = 0;
        gNativeTerminationShieldRootPgid = 0;
        blackbox::rawsyscall::setRawSyscallTerminationBlocking(false);
        return;
    }
    gAppNativeLoaderMapsTrustUntilNs = 0;
    snprintf(gNativeTerminationShieldPackage, sizeof(gNativeTerminationShieldPackage), "%s", package_name);
    snprintf(gNativeSandboxProcessName,
             sizeof(gNativeSandboxProcessName),
             "%s",
             process_name == nullptr || process_name[0] == '\0' ? package_name : process_name);
    gNativeTerminationBlockingEnabled = false;
    installDirectLibcProcMapsHooks();
    installDirectLibcMetadataHooks();
    installDirectLibcPthreadCreateHook();
    if (isNativeCrashProbeEnabled()) {
        installNativeCrashProbe();
    }
    if (isTerminationProbeEnabled()) {
        installDirectLibcTerminationHooks();
    }
    if (isProcShimEnabled()) {
        prepareEarlyProcMapsShim(package_name);
    } else {
        resetEarlyProcMapsShim();
    }
    gNativeTerminationShieldRootPid = getpid();
    gNativeTerminationShieldRootPgid = getpgrp();
}

extern "C" void setNativeSandboxEnvironment(const char *package_name, const char *process_name) {
    setNativeSandboxEnvironmentInternal(package_name, process_name);
}

extern "C" void setNativeSandboxEnvironmentPackage(const char *package_name) {
    setNativeSandboxEnvironmentInternal(package_name, package_name);
}

extern "C" void setNativeTerminationShieldPackage(const char *package_name) {
    if (package_name == nullptr || package_name[0] == '\0') {
        setNativeSandboxEnvironmentInternal(package_name, nullptr);
        return;
    }
    const char *process_name = gNativeSandboxProcessName[0] == '\0'
                               ? package_name
                               : gNativeSandboxProcessName;
    setNativeSandboxEnvironmentInternal(package_name, process_name);
    gNativeTerminationBlockingEnabled = true;
    installDirectLibcTerminationHooks();
    blackbox::rawsyscall::installRawSyscallTerminationProbe();
}

extern "C" void disableEarlyProcMapsShim() {
    bool was_ready = gEarlyProcMapsReady;
    gEarlyProcMapsReady = false;
    gRefreshingEarlyProcMapsShim = false;
    gEarlyProcMapsPackage[0] = '\0';
    if (!isProtectedProcMapsShimReady()) {
        if (isProcShimFdAvailable(kProcMapsFd)) {
            close(kProcMapsFd);
        }
    }
    if (was_ready) {
        ALOGD("early proc maps shim disabled protectedReady=%d",
              isProtectedProcMapsShimReady() ? 1 : 0);
    }
}

extern "C" int blackbox_open_virtual_proc_fd_for_raw_syscall(int dirfd,
                                                             const char *pathname,
                                                             int flags,
                                                             void *caller) {
    if (!isRawProcVirtualizationEnabled()) {
        return -1;
    }
    if (!isReadOnlyOpenFlags(flags)) {
        return -1;
    }

    ResolvedPath resolved = resolveOpenAtPathForLog(dirfd, pathname);
    int transient_maps = openTransientProcMapsFdForRead(resolved.path, caller);
    if (transient_maps >= 0) {
        return transient_maps;
    }
    int shim_result = openProcShimFdForRead(resolved.path);
    if (shim_result >= 0) {
        return shim_result;
    }
    return openVirtualProcIdentityFdForRead(resolved.path);
}

extern "C" void enterNativeInternalFileProbe() {
    gInternalFileProbeDepth++;
}

extern "C" void leaveNativeInternalFileProbe() {
    if (gInternalFileProbeDepth > 0) {
        gInternalFileProbeDepth--;
    }
}

extern "C" bool writeSanitizedProcMapsSnapshot(const char *output_path, const char *package_name) {
    if (output_path == nullptr || output_path[0] == '\0') {
        return false;
    }
    (void) package_name;
    ScopedInternalFileProbe internal_probe;
    int fd = open(output_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        return false;
    }
    bool ok = writeProcMapsPathOnlyFile(fd);
    if (close(fd) != 0) {
        ok = false;
    }
    if (!ok) {
        unlink(output_path);
    }
    return ok;
}

bool shouldVirtualizeDirectProcMapsOpen(void *caller) {
    if (isCurrentThreadMarkedAppOwnedNative()) {
        return true;
    }
    if (caller == nullptr) {
        return false;
    }

    CallerLocation caller_location = {};
    resolveCallerLocation(caller, &caller_location);
    if (caller_location.resolved && isAppOwnedNativeCallerPath(caller_location.path)) {
        return true;
    }
    if (isWithinAppNativeLoaderMapsWindow()
        && caller_location.resolved
        && isBionicLibcCallerPath(caller_location.path)) {
        return true;
    }
    if (isProcessProbeEnabled()) {
        return true;
    }

    MemoryMapEntry entry = {};
    if (resolveMemoryMapEntry(caller, &entry) && isAppOwnedNativeCallerPath(entry.path)) {
        return true;
    }
    return hasAppOwnedNativeFrame();
}

bool isProcShimReadCandidatePath(const char *pathname) {
    return procShimFdForReadPath(pathname) >= 0;
}

bool isVirtualProcIdentityReadCandidatePath(const char *pathname) {
    return isProcStatusProbePath(pathname, nullptr)
           || isProcCgroupProbePath(pathname)
           || isProcAttrCurrentProbePath(pathname);
}

bool shouldCheckDirectVirtualProcRead(const char *pathname) {
    return shouldUseTransientProcMaps(pathname)
           || isProcShimReadCandidatePath(pathname)
           || isVirtualProcIdentityReadCandidatePath(pathname);
}

extern "C" int blackbox_direct_open(const char *pathname, int flags, ...) {
    va_list args;
    va_start(args, flags);
    mode_t mode = takeModeArg(flags, args);
    va_end(args);

    if (isInternalFileProbe()) {
        return rawDirectOpenAt(AT_FDCWD, pathname, flags, mode);
    }

    const char *redirected = redirectAbsolutePath(pathname);
    if (isReadOnlyOpenFlags(flags)) {
        bool virtualize_proc_maps = shouldCheckDirectVirtualProcRead(pathname)
                                    && shouldVirtualizeDirectProcMapsOpen(__builtin_return_address(0));
        if (virtualize_proc_maps) {
            int transient_maps = openTransientProcMapsFdForRead(pathname, __builtin_return_address(0));
            if (transient_maps >= 0) {
                if (isFileProbeEnabled()) {
                    ALOGD("native direct proc maps open api=direct.open package=%s path=%s fd=%d",
                          currentVirtualPackageForProcMaps(),
                          pathname == nullptr ? "null" : pathname,
                          transient_maps);
                }
                logOpenPath("direct.open", pathname, redirected, flags, transient_maps, __builtin_return_address(0));
                return transient_maps;
            }
        }
        if (virtualize_proc_maps) {
            int shim_result = openProcShimFdForRead(pathname);
            if (shim_result >= 0) {
                logOpenPath("direct.open", pathname, redirected, flags, shim_result, __builtin_return_address(0));
                return shim_result;
            }
            int status_result = openVirtualProcIdentityFdForRead(pathname);
            if (status_result >= 0) {
                logOpenPath("direct.open", pathname, redirected, flags, status_result, __builtin_return_address(0));
                return status_result;
            }
        }
    }

    int result = rawDirectOpenAt(AT_FDCWD, redirected, flags, mode);
    maybeMarkAppNativeLoaderMapsWindow(pathname, redirected, result);
    logOpenPath("direct.open", pathname, redirected, flags, result, __builtin_return_address(0));
    return result;
}

extern "C" int blackbox_direct_open_2(const char *pathname, int flags) {
    if (isInternalFileProbe()) {
        return rawDirectOpenAt(AT_FDCWD, pathname, flags, 0);
    }

    const char *redirected = redirectAbsolutePath(pathname);
    if (isReadOnlyOpenFlags(flags)) {
        bool virtualize_proc_maps = shouldCheckDirectVirtualProcRead(pathname)
                                    && shouldVirtualizeDirectProcMapsOpen(__builtin_return_address(0));
        if (virtualize_proc_maps) {
            int transient_maps = openTransientProcMapsFdForRead(pathname, __builtin_return_address(0));
            if (transient_maps >= 0) {
                if (isFileProbeEnabled()) {
                    ALOGD("native direct proc maps open api=direct.__open_2 package=%s path=%s fd=%d",
                          currentVirtualPackageForProcMaps(),
                          pathname == nullptr ? "null" : pathname,
                          transient_maps);
                }
                logOpenPath("direct.__open_2", pathname, redirected, flags, transient_maps, __builtin_return_address(0));
                return transient_maps;
            }
        }
        if (virtualize_proc_maps) {
            int shim_result = openProcShimFdForRead(pathname);
            if (shim_result >= 0) {
                logOpenPath("direct.__open_2", pathname, redirected, flags, shim_result, __builtin_return_address(0));
                return shim_result;
            }
            int status_result = openVirtualProcIdentityFdForRead(pathname);
            if (status_result >= 0) {
                logOpenPath("direct.__open_2", pathname, redirected, flags, status_result, __builtin_return_address(0));
                return status_result;
            }
        }
    }

    int result = rawDirectOpenAt(AT_FDCWD, redirected, flags, 0);
    maybeMarkAppNativeLoaderMapsWindow(pathname, redirected, result);
    logOpenPath("direct.__open_2", pathname, redirected, flags, result, __builtin_return_address(0));
    return result;
}

extern "C" int blackbox_direct_openat(int dirfd, const char *pathname, int flags, ...) {
    va_list args;
    va_start(args, flags);
    mode_t mode = takeModeArg(flags, args);
    va_end(args);

    if (isInternalFileProbe()) {
        return rawDirectOpenAt(dirfd, pathname, flags, mode);
    }

    bool use_absolute = false;
    ResolvedPath resolved_log = resolveOpenAtPathForLog(dirfd, pathname);
    const char *redirected = redirectOpenAtPath(dirfd, pathname, &use_absolute);
    if (isReadOnlyOpenFlags(flags)) {
        bool virtualize_proc_maps = shouldCheckDirectVirtualProcRead(resolved_log.path)
                                    && shouldVirtualizeDirectProcMapsOpen(__builtin_return_address(0));
        if (virtualize_proc_maps) {
            int transient_maps = openTransientProcMapsFdForRead(resolved_log.path, __builtin_return_address(0));
            if (transient_maps >= 0) {
                const char *redirected_log = use_absolute ? redirected : resolved_log.path;
                if (isFileProbeEnabled()) {
                    ALOGD("native direct proc maps open api=direct.openat package=%s path=%s fd=%d",
                          currentVirtualPackageForProcMaps(),
                          resolved_log.path == nullptr ? "null" : resolved_log.path,
                          transient_maps);
                }
                logOpenPath("direct.openat", resolved_log.path, redirected_log, flags, transient_maps, __builtin_return_address(0));
                return transient_maps;
            }
        }
        if (virtualize_proc_maps) {
            int shim_result = openProcShimFdForRead(resolved_log.path);
            if (shim_result >= 0) {
                const char *redirected_log = use_absolute ? redirected : redirectProcProbeToShim(resolved_log.path);
                logOpenPath("direct.openat", resolved_log.path,
                            redirected_log == nullptr ? resolved_log.path : redirected_log,
                            flags,
                            shim_result,
                            __builtin_return_address(0));
                return shim_result;
            }
            int status_result = openVirtualProcIdentityFdForRead(resolved_log.path);
            if (status_result >= 0) {
                const char *redirected_log = use_absolute ? redirected : resolved_log.path;
                logOpenPath("direct.openat", resolved_log.path, redirected_log, flags, status_result, __builtin_return_address(0));
                return status_result;
            }
        }
    }

    int result = rawDirectOpenAt(use_absolute ? AT_FDCWD : dirfd, redirected, flags, mode);
    const char *redirected_log = use_absolute ? redirected : resolved_log.path;
    maybeMarkAppNativeLoaderMapsWindow(resolved_log.path, redirected_log, result);
    logOpenPath("direct.openat", resolved_log.path, redirected_log, flags, result, __builtin_return_address(0));
    return result;
}

extern "C" int blackbox_direct_openat_2(int dirfd, const char *pathname, int flags) {
    if (isInternalFileProbe()) {
        return rawDirectOpenAt(dirfd, pathname, flags, 0);
    }

    bool use_absolute = false;
    ResolvedPath resolved_log = resolveOpenAtPathForLog(dirfd, pathname);
    const char *redirected = redirectOpenAtPath(dirfd, pathname, &use_absolute);
    if (isReadOnlyOpenFlags(flags)) {
        bool virtualize_proc_maps = shouldCheckDirectVirtualProcRead(resolved_log.path)
                                    && shouldVirtualizeDirectProcMapsOpen(__builtin_return_address(0));
        if (virtualize_proc_maps) {
            int transient_maps = openTransientProcMapsFdForRead(resolved_log.path, __builtin_return_address(0));
            if (transient_maps >= 0) {
                const char *redirected_log = use_absolute ? redirected : resolved_log.path;
                if (isFileProbeEnabled()) {
                    ALOGD("native direct proc maps open api=direct.__openat_2 package=%s path=%s fd=%d",
                          currentVirtualPackageForProcMaps(),
                          resolved_log.path == nullptr ? "null" : resolved_log.path,
                          transient_maps);
                }
                logOpenPath("direct.__openat_2", resolved_log.path, redirected_log, flags, transient_maps, __builtin_return_address(0));
                return transient_maps;
            }
        }
        if (virtualize_proc_maps) {
            int shim_result = openProcShimFdForRead(resolved_log.path);
            if (shim_result >= 0) {
                const char *redirected_log = use_absolute ? redirected : redirectProcProbeToShim(resolved_log.path);
                logOpenPath("direct.__openat_2", resolved_log.path,
                            redirected_log == nullptr ? resolved_log.path : redirected_log,
                            flags,
                            shim_result,
                            __builtin_return_address(0));
                return shim_result;
            }
            int status_result = openVirtualProcIdentityFdForRead(resolved_log.path);
            if (status_result >= 0) {
                const char *redirected_log = use_absolute ? redirected : resolved_log.path;
                logOpenPath("direct.__openat_2", resolved_log.path, redirected_log, flags, status_result, __builtin_return_address(0));
                return status_result;
            }
        }
    }

    int result = rawDirectOpenAt(use_absolute ? AT_FDCWD : dirfd, redirected, flags, 0);
    const char *redirected_log = use_absolute ? redirected : resolved_log.path;
    maybeMarkAppNativeLoaderMapsWindow(resolved_log.path, redirected_log, result);
    logOpenPath("direct.__openat_2", resolved_log.path, redirected_log, flags, result, __builtin_return_address(0));
    return result;
}

extern "C" int open(const char *pathname, int flags, ...) {
    va_list args;
    va_start(args, flags);
    mode_t mode = takeModeArg(flags, args);
    va_end(args);

    if (isInternalFileProbe()) {
        return callOpen(resolveSymbol(&gOrigOpen, "open"), pathname, flags, mode);
    }

    const char *redirected = redirectAbsolutePath(pathname);
    if (isReadOnlyOpenFlags(flags)) {
        int transient_maps = openTransientProcMapsFdForRead(pathname, __builtin_return_address(0));
        if (transient_maps >= 0) {
            logOpenPath("open", pathname, redirected, flags, transient_maps, __builtin_return_address(0));
            return transient_maps;
        }
        int shim_result = openProcShimFdForRead(pathname);
        if (shim_result >= 0) {
            logOpenPath("open", pathname, redirected, flags, shim_result, __builtin_return_address(0));
            return shim_result;
        }
        int status_result = openVirtualProcIdentityFdForRead(pathname);
        if (status_result >= 0) {
            logOpenPath("open", pathname, redirected, flags, status_result, __builtin_return_address(0));
            return status_result;
        }
    }
    int result = callOpen(resolveSymbol(&gOrigOpen, "open"), redirected, flags, mode);
    maybeMarkAppNativeLoaderMapsWindow(pathname, redirected, result);
    logOpenPath("open", pathname, redirected, flags, result, __builtin_return_address(0));
    return result;
}

extern "C" int open64(const char *pathname, int flags, ...) {
    va_list args;
    va_start(args, flags);
    mode_t mode = takeModeArg(flags, args);
    va_end(args);

    if (isInternalFileProbe()) {
        return callOpen(resolveSymbol(&gOrigOpen64, "open64"), pathname, flags, mode);
    }

    const char *redirected = redirectAbsolutePath(pathname);
    if (isReadOnlyOpenFlags(flags)) {
        int transient_maps = openTransientProcMapsFdForRead(pathname, __builtin_return_address(0));
        if (transient_maps >= 0) {
            logOpenPath("open64", pathname, redirected, flags, transient_maps, __builtin_return_address(0));
            return transient_maps;
        }
        int shim_result = openProcShimFdForRead(pathname);
        if (shim_result >= 0) {
            logOpenPath("open64", pathname, redirected, flags, shim_result, __builtin_return_address(0));
            return shim_result;
        }
        int status_result = openVirtualProcIdentityFdForRead(pathname);
        if (status_result >= 0) {
            logOpenPath("open64", pathname, redirected, flags, status_result, __builtin_return_address(0));
            return status_result;
        }
    }
    int result = callOpen(resolveSymbol(&gOrigOpen64, "open64"), redirected, flags, mode);
    maybeMarkAppNativeLoaderMapsWindow(pathname, redirected, result);
    logOpenPath("open64", pathname, redirected, flags, result, __builtin_return_address(0));
    return result;
}

extern "C" int __open_2(const char *pathname, int flags) {
    if (needsModeArg(flags)) {
        errno = EINVAL;
        return -1;
    }

    if (isInternalFileProbe()) {
        Open2Fn fn = resolveSymbol(&gOrigOpen2, "__open_2");
        if (fn != nullptr) {
            return fn(pathname, flags);
        }
        return callOpen(resolveSymbol(&gOrigOpen, "open"), pathname, flags, 0);
    }

    const char *redirected = redirectAbsolutePath(pathname);
    if (isReadOnlyOpenFlags(flags)) {
        int transient_maps = openTransientProcMapsFdForRead(pathname, __builtin_return_address(0));
        if (transient_maps >= 0) {
            logOpenPath("__open_2", pathname, redirected, flags, transient_maps, __builtin_return_address(0));
            return transient_maps;
        }
        int shim_result = openProcShimFdForRead(pathname);
        if (shim_result >= 0) {
            logOpenPath("__open_2", pathname, redirected, flags, shim_result, __builtin_return_address(0));
            return shim_result;
        }
        int status_result = openVirtualProcIdentityFdForRead(pathname);
        if (status_result >= 0) {
            logOpenPath("__open_2", pathname, redirected, flags, status_result, __builtin_return_address(0));
            return status_result;
        }
    }

    Open2Fn fn = resolveSymbol(&gOrigOpen2, "__open_2");
    int result = -1;
    if (fn != nullptr) {
        result = fn(redirected, flags);
    } else {
        result = callOpen(resolveSymbol(&gOrigOpen, "open"), redirected, flags, 0);
    }
    maybeMarkAppNativeLoaderMapsWindow(pathname, redirected, result);
    logOpenPath("__open_2", pathname, redirected, flags, result, __builtin_return_address(0));
    return result;
}

extern "C" int openat(int dirfd, const char *pathname, int flags, ...) {
    va_list args;
    va_start(args, flags);
    mode_t mode = takeModeArg(flags, args);
    va_end(args);

    if (isInternalFileProbe()) {
        return callOpenAt(resolveSymbol(&gOrigOpenAt, "openat"), dirfd, pathname, flags, mode);
    }

    bool use_absolute = false;
    ResolvedPath resolved_log = resolveOpenAtPathForLog(dirfd, pathname);
    const char *redirected = redirectOpenAtPath(dirfd, pathname, &use_absolute);
    if (isReadOnlyOpenFlags(flags)) {
        int transient_maps = openTransientProcMapsFdForRead(resolved_log.path, __builtin_return_address(0));
        if (transient_maps >= 0) {
            const char *redirected_log = use_absolute ? redirected : resolved_log.path;
            logOpenPath("openat", resolved_log.path, redirected_log, flags, transient_maps, __builtin_return_address(0));
            return transient_maps;
        }
        int shim_result = openProcShimFdForRead(resolved_log.path);
        if (shim_result >= 0) {
            const char *redirected_log = use_absolute ? redirected : redirectProcProbeToShim(resolved_log.path);
            logOpenPath("openat", resolved_log.path,
                        redirected_log == nullptr ? resolved_log.path : redirected_log,
                        flags,
                        shim_result,
                        __builtin_return_address(0));
            return shim_result;
        }
        int status_result = openVirtualProcIdentityFdForRead(resolved_log.path);
        if (status_result >= 0) {
            const char *redirected_log = use_absolute ? redirected : resolved_log.path;
            logOpenPath("openat", resolved_log.path, redirected_log, flags, status_result, __builtin_return_address(0));
            return status_result;
        }
    }
    int result = callOpenAt(resolveSymbol(&gOrigOpenAt, "openat"),
                            use_absolute ? AT_FDCWD : dirfd, redirected, flags, mode);
    const char *redirected_log = use_absolute ? redirected : resolved_log.path;
    maybeMarkAppNativeLoaderMapsWindow(resolved_log.path, redirected_log, result);
    logOpenPath("openat", resolved_log.path, redirected_log, flags, result, __builtin_return_address(0));
    return result;
}

extern "C" int __openat_2(int dirfd, const char *pathname, int flags) {
    if (needsModeArg(flags)) {
        errno = EINVAL;
        return -1;
    }

    if (isInternalFileProbe()) {
        OpenAt2Fn fn = resolveSymbol(&gOrigOpenAt2, "__openat_2");
        if (fn != nullptr) {
            return fn(dirfd, pathname, flags);
        }
        return callOpenAt(resolveSymbol(&gOrigOpenAt, "openat"), dirfd, pathname, flags, 0);
    }

    bool use_absolute = false;
    ResolvedPath resolved_log = resolveOpenAtPathForLog(dirfd, pathname);
    const char *redirected = redirectOpenAtPath(dirfd, pathname, &use_absolute);
    if (isReadOnlyOpenFlags(flags)) {
        int transient_maps = openTransientProcMapsFdForRead(resolved_log.path, __builtin_return_address(0));
        if (transient_maps >= 0) {
            const char *redirected_log = use_absolute ? redirected : resolved_log.path;
            logOpenPath("__openat_2", resolved_log.path, redirected_log, flags, transient_maps, __builtin_return_address(0));
            return transient_maps;
        }
        int shim_result = openProcShimFdForRead(resolved_log.path);
        if (shim_result >= 0) {
            const char *redirected_log = use_absolute ? redirected : redirectProcProbeToShim(resolved_log.path);
            logOpenPath("__openat_2", resolved_log.path,
                        redirected_log == nullptr ? resolved_log.path : redirected_log,
                        flags,
                        shim_result,
                        __builtin_return_address(0));
            return shim_result;
        }
        int status_result = openVirtualProcIdentityFdForRead(resolved_log.path);
        if (status_result >= 0) {
            const char *redirected_log = use_absolute ? redirected : resolved_log.path;
            logOpenPath("__openat_2", resolved_log.path, redirected_log, flags, status_result, __builtin_return_address(0));
            return status_result;
        }
    }

    OpenAt2Fn fn = resolveSymbol(&gOrigOpenAt2, "__openat_2");
    int call_dirfd = use_absolute ? AT_FDCWD : dirfd;
    int result = -1;
    if (fn != nullptr) {
        result = fn(call_dirfd, redirected, flags);
    } else {
        result = callOpenAt(resolveSymbol(&gOrigOpenAt, "openat"), call_dirfd, redirected, flags, 0);
    }
    const char *redirected_log = use_absolute ? redirected : resolved_log.path;
    maybeMarkAppNativeLoaderMapsWindow(resolved_log.path, redirected_log, result);
    logOpenPath("__openat_2", resolved_log.path, redirected_log, flags, result, __builtin_return_address(0));
    return result;
}

extern "C" FILE *fopen(const char *pathname, const char *mode) {
    FopenFn fn = resolveSymbol(&gOrigFopen, "fopen");
    if (fn == nullptr) {
        errno = ENOSYS;
        return nullptr;
    }
    if (isInternalFileProbe()) {
        return fn(pathname, mode);
    }
    const char *redirected = redirectAbsolutePath(pathname);
    if (isReadOnlyFopenMode(mode)) {
        int transient_maps = openTransientProcMapsFdForRead(pathname, __builtin_return_address(0));
        if (transient_maps >= 0) {
            FILE *transient_result = fdopen(transient_maps, mode);
            if (transient_result != nullptr) {
                logOpenPath("fopen", pathname, redirected, 0, 0, __builtin_return_address(0));
                return transient_result;
            }
            close(transient_maps);
        }
        int shim_fd = openProcShimFdForRead(pathname);
        if (shim_fd >= 0) {
            FILE *shim_result = fdopen(shim_fd, mode);
            if (shim_result != nullptr) {
                logOpenPath("fopen", pathname, redirected, 0, 0, __builtin_return_address(0));
                return shim_result;
            }
            close(shim_fd);
        }
        int status_fd = openVirtualProcIdentityFdForRead(pathname);
        if (status_fd >= 0) {
            FILE *status_result = fdopen(status_fd, mode);
            if (status_result != nullptr) {
                logOpenPath("fopen", pathname, redirected, 0, 0, __builtin_return_address(0));
                return status_result;
            }
            close(status_fd);
        }
    }
    FILE *result = fn(redirected, mode);
    maybeMarkAppNativeLoaderMapsWindow(pathname, redirected, result == nullptr ? -1 : 0);
    logOpenPath("fopen", pathname, redirected, 0, result == nullptr ? -1 : 0, __builtin_return_address(0));
    return result;
}

extern "C" FILE *fopen64(const char *pathname, const char *mode) {
    FopenFn fn = resolveSymbol(&gOrigFopen64, "fopen64");
    if (fn == nullptr) {
        errno = ENOSYS;
        return nullptr;
    }
    if (isInternalFileProbe()) {
        return fn(pathname, mode);
    }
    const char *redirected = redirectAbsolutePath(pathname);
    if (isReadOnlyFopenMode(mode)) {
        int transient_maps = openTransientProcMapsFdForRead(pathname, __builtin_return_address(0));
        if (transient_maps >= 0) {
            FILE *transient_result = fdopen(transient_maps, mode);
            if (transient_result != nullptr) {
                logOpenPath("fopen64", pathname, redirected, 0, 0, __builtin_return_address(0));
                return transient_result;
            }
            close(transient_maps);
        }
        int shim_fd = openProcShimFdForRead(pathname);
        if (shim_fd >= 0) {
            FILE *shim_result = fdopen(shim_fd, mode);
            if (shim_result != nullptr) {
                logOpenPath("fopen64", pathname, redirected, 0, 0, __builtin_return_address(0));
                return shim_result;
            }
            close(shim_fd);
        }
        int status_fd = openVirtualProcIdentityFdForRead(pathname);
        if (status_fd >= 0) {
            FILE *status_result = fdopen(status_fd, mode);
            if (status_result != nullptr) {
                logOpenPath("fopen64", pathname, redirected, 0, 0, __builtin_return_address(0));
                return status_result;
            }
            close(status_fd);
        }
    }
    FILE *result = fn(redirected, mode);
    maybeMarkAppNativeLoaderMapsWindow(pathname, redirected, result == nullptr ? -1 : 0);
    logOpenPath("fopen64", pathname, redirected, 0, result == nullptr ? -1 : 0, __builtin_return_address(0));
    return result;
}

extern "C" DIR *opendir(const char *pathname) {
    OpendirFn fn = resolveSymbol(&gOrigOpendir, "opendir");
    if (fn == nullptr) {
        errno = ENOSYS;
        return nullptr;
    }
    const char *redirected = redirectAbsolutePath(pathname);
    DIR *result = fn(redirected);
    logDirPath("opendir", pathname, redirected, result);
    return result;
}

extern "C" char *realpath(const char *pathname, char *resolved) {
    RealpathFn fn = resolveSymbol(&gOrigRealpath, "realpath");
    if (fn == nullptr) {
        errno = ENOSYS;
        return nullptr;
    }
    const char *redirected = redirectMetadataPath(pathname);
    char *result = fn(redirected, resolved);
    if (result == nullptr) {
        logOpenPath("realpath", pathname, redirected, 0, -1, __builtin_return_address(0));
        return nullptr;
    }

    const char *reversed = IO::reverseRedirectPath(result);
    if (reversed != result) {
        if (resolved != nullptr) {
            snprintf(resolved, PATH_MAX, "%s", reversed);
            free(const_cast<char *>(reversed));
            result = resolved;
        } else {
            char *copy = strdup(reversed);
            free(result);
            free(const_cast<char *>(reversed));
            result = copy;
        }
    }
    logOpenPath("realpath", pathname, redirected, 0, result == nullptr ? -1 : 0, __builtin_return_address(0));
    return result;
}

extern "C" int access(const char *pathname, int mode) {
    FaccessatFn fn = resolveSymbol(&gOrigFaccessat, "faccessat");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    const char *redirected = redirectMetadataPath(pathname);
    if (denyProcCmdlineAccessIfNeeded(pathname, redirected, mode)) {
        logOpenPath("access", pathname, redirected, mode, -1, __builtin_return_address(0));
        return -1;
    }
    int result = fn(AT_FDCWD, redirected, mode, 0);
    logOpenPath("access", pathname, redirected, mode, result, __builtin_return_address(0));
    return result;
}

extern "C" int faccessat(int dirfd, const char *pathname, int mode, int flags) {
    FaccessatFn fn = resolveSymbol(&gOrigFaccessat, "faccessat");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    bool use_absolute = false;
    ResolvedPath resolved_log = resolveOpenAtPathForLog(dirfd, pathname);
    const char *redirected = redirectMetadataAtPath(dirfd, pathname, &use_absolute);
    const char *redirected_log = use_absolute ? redirected : resolved_log.path;
    if (denyProcCmdlineAccessIfNeeded(resolved_log.path, redirected, mode)) {
        logOpenPath("faccessat", resolved_log.path, redirected_log, mode, -1, __builtin_return_address(0));
        return -1;
    }
    int result = fn(use_absolute ? AT_FDCWD : dirfd, redirected, mode, flags);
    logOpenPath("faccessat", resolved_log.path, redirected_log, mode, result, __builtin_return_address(0));
    return result;
}

extern "C" int mkdir(const char *pathname, mode_t mode) {
    MkdirAtFn fn = resolveSymbol(&gOrigMkdirAt, "mkdirat");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    const char *redirected = redirectDirectoryPath(pathname);
    int result = fn(AT_FDCWD, redirected, mode);
    logMkdirPath("mkdir", pathname, redirected, mode, result, __builtin_return_address(0));
    return result;
}

extern "C" int mkdirat(int dirfd, const char *pathname, mode_t mode) {
    MkdirAtFn fn = resolveSymbol(&gOrigMkdirAt, "mkdirat");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    bool use_absolute = false;
    ResolvedPath resolved_log = resolveOpenAtPathForLog(dirfd, pathname);
    const char *redirected = redirectDirectoryAtPath(dirfd, pathname, &use_absolute);
    int result = fn(use_absolute ? AT_FDCWD : dirfd, redirected, mode);
    const char *redirected_log = use_absolute ? redirected : resolved_log.path;
    logMkdirPath("mkdirat", resolved_log.path, redirected_log, mode, result, __builtin_return_address(0));
    return result;
}

extern "C" int stat(const char *pathname, struct stat *buf) {
    FstatatFn fn = resolveSymbol(&gOrigFstatat, "fstatat");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    int shim_fd = -1;
    bool proc_shim = isProcShimFdPath(pathname, &shim_fd);
    const char *redirected = redirectMetadataPath(pathname);
    if (!proc_shim) {
        proc_shim = isProcShimFdPath(redirected, &shim_fd);
    }
    int result = fn(AT_FDCWD, redirected, buf, 0);
    maybeSanitizeProcShimStat(result, shim_fd, buf);
    maybeSanitizeProcCmdlineStat(result, pathname, redirected, buf);
    maybeSanitizeProcStatusStat(result, pathname, redirected, buf);
    maybeSanitizeVirtualOwnerStat(result, buf);
    logStatPath("stat", pathname, redirected, result, proc_shim, __builtin_return_address(0));
    return result;
}

extern "C" int lstat(const char *pathname, struct stat *buf) {
    FstatatFn fn = resolveSymbol(&gOrigFstatat, "fstatat");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    int shim_fd = -1;
    bool proc_shim = isProcShimFdPath(pathname, &shim_fd);
    const char *redirected = redirectMetadataPath(pathname);
    if (!proc_shim) {
        proc_shim = isProcShimFdPath(redirected, &shim_fd);
    }
    int result = fn(AT_FDCWD, redirected, buf, AT_SYMLINK_NOFOLLOW);
    maybeSanitizeProcShimStat(result, shim_fd, buf);
    maybeSanitizeProcCmdlineStat(result, pathname, redirected, buf);
    maybeSanitizeProcStatusStat(result, pathname, redirected, buf);
    maybeSanitizeVirtualOwnerStat(result, buf);
    logStatPath("lstat", pathname, redirected, result, proc_shim, __builtin_return_address(0));
    return result;
}

extern "C" int fstat(int fd, struct stat *buf) {
    FstatFn fn = resolveSymbol(&gOrigFstat, "fstat");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    int result = fn(fd, buf);
    maybeSanitizeProcShimStat(result, fd, buf);
    maybeSanitizeProcMapsFdStat(result, fd, buf);
    maybeSanitizeProcCmdlineFdStat(result, fd, buf);
    maybeSanitizeProcStatusFdStat(result, fd, buf);
    maybeSanitizeVirtualOwnerFdStat(result, fd, buf);
    logFdStat("fstat", fd, result);
    return result;
}

extern "C" int fstatat(int dirfd, const char *pathname, struct stat *buf, int flags) {
    FstatatFn fn = resolveSymbol(&gOrigFstatat, "fstatat");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    bool use_absolute = false;
    ResolvedPath resolved_log = resolveOpenAtPathForLog(dirfd, pathname);
    const char *redirected = redirectMetadataAtPath(dirfd, pathname, &use_absolute);
    int result = fn(use_absolute ? AT_FDCWD : dirfd, redirected, buf, flags);
    maybeSanitizeProcCmdlineStat(result, resolved_log.path, redirected, buf);
    maybeSanitizeProcStatusStat(result, resolved_log.path, redirected, buf);
    maybeSanitizeVirtualOwnerStat(result, buf);
    const char *redirected_log = use_absolute ? redirected : resolved_log.path;
    logStatPath("fstatat", resolved_log.path, redirected_log, result, false, __builtin_return_address(0));
    return result;
}

extern "C" int statx(int dirfd, const char *pathname, int flags, unsigned int mask, struct statx *buf) {
    StatxFn fn = resolveSymbol(&gOrigStatx, "statx");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    bool use_absolute = false;
    ResolvedPath resolved_log = resolveOpenAtPathForLog(dirfd, pathname);
    const char *redirected = redirectMetadataAtPath(dirfd, pathname, &use_absolute);
    int result = fn(use_absolute ? AT_FDCWD : dirfd, redirected, flags, mask, buf);
    maybeSanitizeVirtualOwnerStatx(result, buf);
    const char *redirected_log = use_absolute ? redirected : resolved_log.path;
    logStatPath("statx", resolved_log.path, redirected_log, result, false, __builtin_return_address(0));
    return result;
}

extern "C" int statfs(const char *pathname, struct statfs *buf) {
    StatfsFn fn = resolveSymbol(&gOrigStatfs, "statfs");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    const char *redirected = redirectFilesystemPath(pathname);
    int result = fn(redirected, buf);
    logStatPath("statfs", pathname, redirected, result, false, __builtin_return_address(0));
    return result;
}

extern "C" int statfs64(const char *pathname, struct statfs64 *buf) {
    Statfs64Fn fn = resolveSymbol(&gOrigStatfs64, "statfs64");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    const char *redirected = redirectFilesystemPath(pathname);
    int result = fn(redirected, buf);
    logStatPath("statfs64", pathname, redirected, result, false, __builtin_return_address(0));
    return result;
}

#if !defined(__LP64__) && (!defined(__ANDROID_API__) || __ANDROID_API__ < 21)
extern "C" int stat64(const char *pathname, struct stat64 *buf) {
    Stat64Fn fn = resolveSymbol(&gOrigStat64, "stat64");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    int shim_fd = -1;
    bool proc_shim = isProcShimFdPath(pathname, &shim_fd);
    const char *redirected = redirectMetadataPath(pathname);
    if (!proc_shim) {
        proc_shim = isProcShimFdPath(redirected, &shim_fd);
    }
    int result = fn(redirected, buf);
    maybeSanitizeProcShimStat64(result, shim_fd, buf);
    maybeSanitizeProcCmdlineStat64(result, pathname, redirected, buf);
    maybeSanitizeProcStatusStat64(result, pathname, redirected, buf);
    maybeSanitizeVirtualOwnerStat64(result, buf);
    logStatPath("stat64", pathname, redirected, result, proc_shim, __builtin_return_address(0));
    return result;
}

extern "C" int lstat64(const char *pathname, struct stat64 *buf) {
    Stat64Fn fn = resolveSymbol(&gOrigLstat64, "lstat64");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    int shim_fd = -1;
    bool proc_shim = isProcShimFdPath(pathname, &shim_fd);
    const char *redirected = redirectMetadataPath(pathname);
    if (!proc_shim) {
        proc_shim = isProcShimFdPath(redirected, &shim_fd);
    }
    int result = fn(redirected, buf);
    maybeSanitizeProcShimStat64(result, shim_fd, buf);
    maybeSanitizeProcCmdlineStat64(result, pathname, redirected, buf);
    maybeSanitizeProcStatusStat64(result, pathname, redirected, buf);
    maybeSanitizeVirtualOwnerStat64(result, buf);
    logStatPath("lstat64", pathname, redirected, result, proc_shim, __builtin_return_address(0));
    return result;
}

extern "C" int fstat64(int fd, struct stat64 *buf) {
    Fstat64Fn fn = resolveSymbol(&gOrigFstat64, "fstat64");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    int result = fn(fd, buf);
    maybeSanitizeProcShimStat64(result, fd, buf);
    maybeSanitizeProcMapsFdStat64(result, fd, buf);
    maybeSanitizeProcCmdlineFdStat64(result, fd, buf);
    maybeSanitizeProcStatusFdStat64(result, fd, buf);
    maybeSanitizeVirtualOwnerFdStat64(result, fd, buf);
    logFdStat("fstat64", fd, result);
    return result;
}
#endif

extern "C" ssize_t readlink(const char *pathname, char *buf, size_t bufsiz) {
    int shim_fd = -1;
    char target_buffer[PATH_MAX];
    const char *target = nullptr;
    bool proc_shim = isProcShimFdPath(pathname, &shim_fd);
    if (proc_shim) {
        target = procShimReadlinkTarget(shim_fd, target_buffer, sizeof(target_buffer));
        ssize_t result = copyReadlinkTarget(target, buf, bufsiz);
        logReadlinkPath("readlink", pathname, pathname, target, result, true, __builtin_return_address(0));
        return result;
    }
    target = virtualProcMapsReadlinkTarget(pathname, target_buffer, sizeof(target_buffer));
    if (target != nullptr) {
        ssize_t result = copyReadlinkTarget(target, buf, bufsiz);
        logReadlinkPath("readlink", pathname, pathname, target, result, true, __builtin_return_address(0));
        return result;
    }

    const char *redirected = redirectMetadataPath(pathname);
    ssize_t result = callRawReadlink(redirected, buf, bufsiz);
    result = reverseRedirectedReadlinkResult(buf, bufsiz, result);
    logReadlinkPath("readlink", pathname, redirected, nullptr, result, false, __builtin_return_address(0));
    return result;
}

extern "C" ssize_t __readlink_chk(const char *pathname, char *buf, size_t bufsiz, size_t buf_size) {
    if (bufsiz > buf_size) {
        errno = ERANGE;
        return -1;
    }
    return readlink(pathname, buf, bufsiz);
}

extern "C" ssize_t readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz) {
    char resolved_path[PATH_MAX] = {};
    int shim_fd = -1;
    char target_buffer[PATH_MAX];
    const char *target = nullptr;
    bool proc_shim = resolveAtPathForProcShim(dirfd, pathname, resolved_path, sizeof(resolved_path), &shim_fd);
    if (proc_shim) {
        target = procShimReadlinkTarget(shim_fd, target_buffer, sizeof(target_buffer));
        ssize_t result = copyReadlinkTarget(target, buf, bufsiz);
        logReadlinkPath("readlinkat", pathname, resolved_path, target, result, true, __builtin_return_address(0));
        return result;
    }
    const char *resolved_for_virtual = resolved_path[0] != '\0' ? resolved_path : pathname;
    target = virtualProcMapsReadlinkTarget(resolved_for_virtual, target_buffer, sizeof(target_buffer));
    if (target != nullptr) {
        ssize_t result = copyReadlinkTarget(target, buf, bufsiz);
        logReadlinkPath("readlinkat", pathname, resolved_for_virtual, target, result, true, __builtin_return_address(0));
        return result;
    }

    ReadlinkAtFn fn = resolveSymbol(&gOrigReadlinkAt, "readlinkat");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    bool use_absolute = false;
    const char *redirected = redirectMetadataAtPath(dirfd, pathname, &use_absolute);
    ssize_t result = fn(use_absolute ? AT_FDCWD : dirfd, redirected, buf, bufsiz);
    result = reverseRedirectedReadlinkResult(buf, bufsiz, result);
    logReadlinkPath("readlinkat", pathname, redirected, nullptr, result, false, __builtin_return_address(0));
    return result;
}

extern "C" ssize_t __readlinkat_chk(int dirfd, const char *pathname, char *buf, size_t bufsiz, size_t buf_size) {
    if (bufsiz > buf_size) {
        errno = ERANGE;
        return -1;
    }
    return readlinkat(dirfd, pathname, buf, bufsiz);
}

extern "C" uid_t getuid() {
    if (isNativeVirtualUidConfigured()) {
        return virtualUid();
    }
    GetUidFn fn = resolveSymbol(&gOrigGetUid, "getuid");
    return fn == nullptr ? static_cast<uid_t>(-1) : fn();
}

extern "C" uid_t geteuid() {
    if (isNativeVirtualUidConfigured()) {
        return virtualUid();
    }
    GetUidFn fn = resolveSymbol(&gOrigGetEuid, "geteuid");
    return fn == nullptr ? static_cast<uid_t>(-1) : fn();
}

extern "C" gid_t getgid() {
    if (isNativeVirtualUidConfigured()) {
        return virtualGid();
    }
    GetGidFn fn = resolveSymbol(&gOrigGetGid, "getgid");
    return fn == nullptr ? static_cast<gid_t>(-1) : fn();
}

extern "C" gid_t getegid() {
    if (isNativeVirtualUidConfigured()) {
        return virtualGid();
    }
    GetGidFn fn = resolveSymbol(&gOrigGetEgid, "getegid");
    return fn == nullptr ? static_cast<gid_t>(-1) : fn();
}

extern "C" int getgroups(int size, gid_t *list) {
    if (isNativeVirtualUidConfigured()) {
        return fillVirtualGroups(size, list);
    }
    GetGroupsFn fn = resolveSymbol(&gOrigGetGroups, "getgroups");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    return fn(size, list);
}

extern "C" long syscall(long number, ...) {
    long args[6] = {};
    va_list va_args;
    va_start(va_args, number);
    takeSyscallArgsForNumber(number, va_args, args);
    va_end(va_args);

    SyscallFn fn = resolveSymbol(&gOrigSyscall, "syscall");
    if (isInternalFileProbe()) {
        return callSyscall(fn, number, args);
    }
    switch (number) {
#ifdef __NR_getuid
        case __NR_getuid:
            return isNativeVirtualUidConfigured() ? virtualUid() : callSyscall(fn, number, args);
#endif
#if defined(__NR_getuid32) && (!defined(__NR_getuid) || __NR_getuid32 != __NR_getuid)
        case __NR_getuid32:
            return isNativeVirtualUidConfigured() ? virtualUid() : callSyscall(fn, number, args);
#endif
#ifdef __NR_geteuid
        case __NR_geteuid:
            return isNativeVirtualUidConfigured() ? virtualUid() : callSyscall(fn, number, args);
#endif
#if defined(__NR_geteuid32) && (!defined(__NR_geteuid) || __NR_geteuid32 != __NR_geteuid)
        case __NR_geteuid32:
            return isNativeVirtualUidConfigured() ? virtualUid() : callSyscall(fn, number, args);
#endif
#ifdef __NR_getgid
        case __NR_getgid:
            return isNativeVirtualUidConfigured() ? virtualGid() : callSyscall(fn, number, args);
#endif
#if defined(__NR_getgid32) && (!defined(__NR_getgid) || __NR_getgid32 != __NR_getgid)
        case __NR_getgid32:
            return isNativeVirtualUidConfigured() ? virtualGid() : callSyscall(fn, number, args);
#endif
#ifdef __NR_getegid
        case __NR_getegid:
            return isNativeVirtualUidConfigured() ? virtualGid() : callSyscall(fn, number, args);
#endif
#if defined(__NR_getegid32) && (!defined(__NR_getegid) || __NR_getegid32 != __NR_getegid)
        case __NR_getegid32:
            return isNativeVirtualUidConfigured() ? virtualGid() : callSyscall(fn, number, args);
#endif
#ifdef __NR_getgroups
        case __NR_getgroups:
            return isNativeVirtualUidConfigured()
                   ? fillVirtualGroups(static_cast<int>(args[0]), reinterpret_cast<gid_t *>(args[1]))
                   : callSyscall(fn, number, args);
#endif
#if defined(__NR_getgroups32) && (!defined(__NR_getgroups) || __NR_getgroups32 != __NR_getgroups)
        case __NR_getgroups32:
            return isNativeVirtualUidConfigured()
                   ? fillVirtualGroups(static_cast<int>(args[0]), reinterpret_cast<gid_t *>(args[1]))
                   : callSyscall(fn, number, args);
#endif
#ifdef __NR_exit
        case __NR_exit:
            logNativeTerminationProbe("syscall.exit", getpid(), 0, static_cast<int>(args[0]), __builtin_return_address(0), currentStackPointer());
            if (shouldBlockNativeExit()) {
                logNativeTerminationBlocked("syscall.exit", getpid(), 0, static_cast<int>(args[0]), __builtin_return_address(0));
                return 0;
            }
            return callSyscall(fn, number, args);
#endif
#ifdef __NR_exit_group
        case __NR_exit_group:
            logNativeTerminationProbe("syscall.exit_group", getpid(), 0, static_cast<int>(args[0]), __builtin_return_address(0), currentStackPointer());
            if (shouldBlockNativeExit()) {
                logNativeTerminationBlocked("syscall.exit_group", getpid(), 0, static_cast<int>(args[0]), __builtin_return_address(0));
                return 0;
            }
            return callSyscall(fn, number, args);
#endif
#ifdef __NR_kill
        case __NR_kill:
            logNativeTerminationProbe("syscall.kill", args[0], static_cast<int>(args[1]), 0, __builtin_return_address(0), currentStackPointer());
            if (shouldBlockNativeSignal(static_cast<pid_t>(args[0]), static_cast<int>(args[1]))) {
                logNativeTerminationBlocked("syscall.kill", args[0], static_cast<int>(args[1]), 0, __builtin_return_address(0));
                return 0;
            }
            return callSyscall(fn, number, args);
#endif
#ifdef __NR_tkill
        case __NR_tkill:
            logNativeTerminationProbe("syscall.tkill", args[0], static_cast<int>(args[1]), 0, __builtin_return_address(0), currentStackPointer());
            if (isNativeTerminationShieldEnabled()
                && isTerminationSignal(static_cast<int>(args[1]))) {
                logNativeTerminationBlocked("syscall.tkill", args[0], static_cast<int>(args[1]), 0, __builtin_return_address(0));
                return 0;
            }
            return callSyscall(fn, number, args);
#endif
#ifdef __NR_tgkill
        case __NR_tgkill:
            logNativeTerminationProbe("syscall.tgkill", args[1], static_cast<int>(args[2]), 0, __builtin_return_address(0), currentStackPointer());
            if (shouldBlockNativeThreadSignal(static_cast<int>(args[0]), static_cast<int>(args[2]))) {
                logNativeTerminationBlocked("syscall.tgkill", args[1], static_cast<int>(args[2]), 0, __builtin_return_address(0));
                return 0;
            }
            return callSyscall(fn, number, args);
#endif
#ifdef __NR_fork
        case __NR_fork: {
            long result = callSyscall(fn, number, args);
            logProcessProbe("syscall.fork", 0, nullptr, static_cast<int>(result), __builtin_return_address(0));
            return result;
        }
#endif
#ifdef __NR_vfork
        case __NR_vfork: {
            long result = callSyscall(fn, number, args);
            if (result != 0) {
                logProcessProbe("syscall.vfork", 0, nullptr, static_cast<int>(result), __builtin_return_address(0));
            }
            return result;
        }
#endif
#ifdef __NR_clone
        case __NR_clone: {
            long result = callSyscall(fn, number, args);
            if ((args[0] & CLONE_VFORK) == 0 || result != 0) {
                logProcessProbe("syscall.clone", args[0], nullptr, static_cast<int>(result), __builtin_return_address(0));
            }
            return result;
        }
#endif
#ifdef __NR_execve
        case __NR_execve: {
            const char *pathname = reinterpret_cast<const char *>(args[0]);
            logProcessProbe("syscall.execve.before", 0, pathname, 0, __builtin_return_address(0));
            long result = callSyscall(fn, number, args);
            logProcessProbe("syscall.execve", 0, pathname, static_cast<int>(result), __builtin_return_address(0));
            return result;
        }
#endif
#ifdef __NR_open
        case __NR_open: {
            const char *pathname = reinterpret_cast<const char *>(args[0]);
            const char *redirected = redirectAbsolutePath(pathname);
            if (isReadOnlyOpenFlags(static_cast<int>(args[1]))) {
                int transient_maps = openTransientProcMapsFdForRead(pathname, __builtin_return_address(0));
                if (transient_maps >= 0) {
                    logOpenPath("syscall.open", pathname, redirected, static_cast<int>(args[1]), transient_maps, __builtin_return_address(0));
                    return transient_maps;
                }
                int shim_result = openProcShimFdForRead(pathname);
                if (shim_result >= 0) {
                    logOpenPath("syscall.open", pathname, redirected, static_cast<int>(args[1]), shim_result, __builtin_return_address(0));
                    return shim_result;
                }
                int status_result = openVirtualProcIdentityFdForRead(pathname);
                if (status_result >= 0) {
                    logOpenPath("syscall.open", pathname, redirected, static_cast<int>(args[1]), status_result, __builtin_return_address(0));
                    return status_result;
                }
            }
            args[0] = reinterpret_cast<long>(redirected);
            long result = callSyscall(fn, number, args);
            maybeMarkAppNativeLoaderMapsWindow(pathname, redirected, result);
            logOpenPath("syscall.open", pathname, redirected, static_cast<int>(args[1]), result, __builtin_return_address(0));
            return result;
        }
#endif
#ifdef __NR_mkdir
        case __NR_mkdir: {
            const char *pathname = reinterpret_cast<const char *>(args[0]);
            const char *redirected = redirectDirectoryPath(pathname);
            args[0] = reinterpret_cast<long>(redirected);
            long result = callSyscall(fn, number, args);
            logMkdirPath("syscall.mkdir", pathname, redirected, static_cast<mode_t>(args[1]), static_cast<int>(result), __builtin_return_address(0));
            return result;
        }
#endif
#ifdef __NR_mkdirat
        case __NR_mkdirat: {
            bool use_absolute = false;
            const char *pathname = reinterpret_cast<const char *>(args[1]);
            ResolvedPath resolved_log = resolveOpenAtPathForLog(static_cast<int>(args[0]), pathname);
            const char *redirected = redirectDirectoryAtPath(static_cast<int>(args[0]), pathname, &use_absolute);
            if (use_absolute) {
                args[0] = AT_FDCWD;
            }
            args[1] = reinterpret_cast<long>(redirected);
            long result = callSyscall(fn, number, args);
            const char *redirected_log = use_absolute ? redirected : resolved_log.path;
            logMkdirPath("syscall.mkdirat", resolved_log.path, redirected_log, static_cast<mode_t>(args[2]), static_cast<int>(result), __builtin_return_address(0));
            return result;
        }
#endif
        case __NR_openat: {
            bool use_absolute = false;
            const char *pathname = reinterpret_cast<const char *>(args[1]);
            ResolvedPath resolved_log = resolveOpenAtPathForLog(static_cast<int>(args[0]), pathname);
            const char *redirected = redirectOpenAtPath(static_cast<int>(args[0]),
                                                        pathname,
                                                        &use_absolute);
            if (isReadOnlyOpenFlags(static_cast<int>(args[2]))) {
                int transient_maps = openTransientProcMapsFdForRead(resolved_log.path, __builtin_return_address(0));
                if (transient_maps >= 0) {
                    const char *redirected_log = use_absolute ? redirected : resolved_log.path;
                    logOpenPath("syscall.openat", resolved_log.path, redirected_log,
                                static_cast<int>(args[2]),
                                transient_maps,
                                __builtin_return_address(0));
                    return transient_maps;
                }
                int shim_result = openProcShimFdForRead(resolved_log.path);
                if (shim_result >= 0) {
                    const char *redirected_log = use_absolute ? redirected : redirectProcProbeToShim(resolved_log.path);
                    logOpenPath("syscall.openat", resolved_log.path,
                                redirected_log == nullptr ? resolved_log.path : redirected_log,
                                static_cast<int>(args[2]),
                                shim_result,
                                __builtin_return_address(0));
                    return shim_result;
                }
                int status_result = openVirtualProcIdentityFdForRead(resolved_log.path);
                if (status_result >= 0) {
                    const char *redirected_log = use_absolute ? redirected : resolved_log.path;
                    logOpenPath("syscall.openat", resolved_log.path,
                                redirected_log,
                                static_cast<int>(args[2]),
                                status_result,
                                __builtin_return_address(0));
                    return status_result;
                }
            }
            if (use_absolute) {
                args[0] = AT_FDCWD;
            }
            args[1] = reinterpret_cast<long>(redirected);
            long result = callSyscall(fn, number, args);
            const char *redirected_log = use_absolute ? redirected : resolved_log.path;
            maybeMarkAppNativeLoaderMapsWindow(resolved_log.path, redirected_log, result);
            logOpenPath("syscall.openat", resolved_log.path, redirected_log, static_cast<int>(args[2]), result, __builtin_return_address(0));
            return result;
        }
#ifdef __NR_faccessat
        case __NR_faccessat: {
            bool use_absolute = false;
            const char *pathname = reinterpret_cast<const char *>(args[1]);
            ResolvedPath resolved_log = resolveOpenAtPathForLog(static_cast<int>(args[0]), pathname);
            const char *redirected = redirectMetadataAtPath(static_cast<int>(args[0]), pathname, &use_absolute);
            const char *redirected_log = use_absolute ? redirected : resolved_log.path;
            if (denyProcCmdlineAccessIfNeeded(resolved_log.path, redirected, static_cast<int>(args[2]))) {
                logOpenPath("syscall.faccessat", resolved_log.path, redirected_log,
                            static_cast<int>(args[2]), -1, __builtin_return_address(0));
                return -1;
            }
            if (use_absolute) {
                args[0] = AT_FDCWD;
            }
            args[1] = reinterpret_cast<long>(redirected);
            long result = callSyscall(fn, number, args);
            logOpenPath("syscall.faccessat", resolved_log.path, redirected_log,
                        static_cast<int>(args[2]), result, __builtin_return_address(0));
            return result;
        }
#endif
#ifdef __NR_newfstatat
        case __NR_newfstatat: {
            bool use_absolute = false;
            const char *pathname = reinterpret_cast<const char *>(args[1]);
            ResolvedPath resolved_log = resolveOpenAtPathForLog(static_cast<int>(args[0]), pathname);
            const char *redirected = redirectMetadataAtPath(static_cast<int>(args[0]), pathname, &use_absolute);
            if (use_absolute) {
                args[0] = AT_FDCWD;
            }
            args[1] = reinterpret_cast<long>(redirected);
            long result = callSyscall(fn, number, args);
            struct stat *buf = reinterpret_cast<struct stat *>(args[2]);
            maybeSanitizeProcCmdlineStat(static_cast<int>(result), resolved_log.path, redirected, buf);
            maybeSanitizeProcStatusStat(static_cast<int>(result), resolved_log.path, redirected, buf);
            maybeSanitizeVirtualOwnerStat(static_cast<int>(result), buf);
            const char *redirected_log = use_absolute ? redirected : resolved_log.path;
            logStatPath("syscall.newfstatat", resolved_log.path, redirected_log, static_cast<int>(result), false, __builtin_return_address(0));
            return result;
        }
#endif
#if defined(__NR_fstatat64) && (!defined(__NR_newfstatat) || __NR_fstatat64 != __NR_newfstatat)
        case __NR_fstatat64: {
            bool use_absolute = false;
            const char *pathname = reinterpret_cast<const char *>(args[1]);
            ResolvedPath resolved_log = resolveOpenAtPathForLog(static_cast<int>(args[0]), pathname);
            const char *redirected = redirectMetadataAtPath(static_cast<int>(args[0]), pathname, &use_absolute);
            if (use_absolute) {
                args[0] = AT_FDCWD;
            }
            args[1] = reinterpret_cast<long>(redirected);
            long result = callSyscall(fn, number, args);
#if !defined(__LP64__)
            struct stat64 *buf = reinterpret_cast<struct stat64 *>(args[2]);
            maybeSanitizeProcCmdlineStat64(static_cast<int>(result), resolved_log.path, redirected, buf);
            maybeSanitizeProcStatusStat64(static_cast<int>(result), resolved_log.path, redirected, buf);
            maybeSanitizeVirtualOwnerStat64(static_cast<int>(result), buf);
#endif
            const char *redirected_log = use_absolute ? redirected : resolved_log.path;
            logStatPath("syscall.fstatat64", resolved_log.path, redirected_log, static_cast<int>(result), false, __builtin_return_address(0));
            return result;
        }
#endif
#ifdef __NR_statx
        case __NR_statx: {
            bool use_absolute = false;
            const char *pathname = reinterpret_cast<const char *>(args[1]);
            ResolvedPath resolved_log = resolveOpenAtPathForLog(static_cast<int>(args[0]), pathname);
            const char *redirected = redirectMetadataAtPath(static_cast<int>(args[0]), pathname, &use_absolute);
            if (use_absolute) {
                args[0] = AT_FDCWD;
            }
            args[1] = reinterpret_cast<long>(redirected);
            long result = callSyscall(fn, number, args);
            maybeSanitizeVirtualOwnerStatx(result, reinterpret_cast<struct statx *>(args[4]));
            const char *redirected_log = use_absolute ? redirected : resolved_log.path;
            logStatPath("syscall.statx", resolved_log.path, redirected_log, static_cast<int>(result), false, __builtin_return_address(0));
            return result;
        }
#endif
#ifdef __NR_statfs
        case __NR_statfs: {
            const char *pathname = reinterpret_cast<const char *>(args[0]);
            const char *redirected = redirectFilesystemPath(pathname);
            args[0] = reinterpret_cast<long>(redirected);
            long result = callSyscall(fn, number, args);
            logStatPath("syscall.statfs", pathname, redirected, static_cast<int>(result), false, __builtin_return_address(0));
            return result;
        }
#endif
#ifdef __NR_statfs64
        case __NR_statfs64: {
            const char *pathname = reinterpret_cast<const char *>(args[0]);
            const char *redirected = redirectFilesystemPath(pathname);
            args[0] = reinterpret_cast<long>(redirected);
            long result = callSyscall(fn, number, args);
            logStatPath("syscall.statfs64", pathname, redirected, static_cast<int>(result), false, __builtin_return_address(0));
            return result;
        }
#endif
#ifdef __NR_readlinkat
        case __NR_readlinkat: {
            const char *pathname = reinterpret_cast<const char *>(args[1]);
            char resolved_path[PATH_MAX] = {};
            int shim_fd = -1;
            char target_buffer[PATH_MAX];
            const char *target = nullptr;
            bool proc_shim = resolveAtPathForProcShim(static_cast<int>(args[0]),
                                                      pathname,
                                                      resolved_path,
                                                      sizeof(resolved_path),
                                                      &shim_fd);
            if (proc_shim) {
                target = procShimReadlinkTarget(shim_fd, target_buffer, sizeof(target_buffer));
                ssize_t result = copyReadlinkTarget(target,
                                                    reinterpret_cast<char *>(args[2]),
                                                    static_cast<size_t>(args[3]));
                logReadlinkPath("syscall.readlinkat", pathname, resolved_path, target, result, true, __builtin_return_address(0));
                return result;
            }
            const char *resolved_for_virtual = resolved_path[0] != '\0' ? resolved_path : pathname;
            target = virtualProcMapsReadlinkTarget(resolved_for_virtual, target_buffer, sizeof(target_buffer));
            if (target != nullptr) {
                ssize_t result = copyReadlinkTarget(target,
                                                    reinterpret_cast<char *>(args[2]),
                                                    static_cast<size_t>(args[3]));
                logReadlinkPath("syscall.readlinkat", pathname, resolved_for_virtual, target, result, true, __builtin_return_address(0));
                return result;
            }

            bool use_absolute = false;
            ResolvedPath resolved_log = resolveOpenAtPathForLog(static_cast<int>(args[0]), pathname);
            const char *redirected = redirectMetadataAtPath(static_cast<int>(args[0]), pathname, &use_absolute);
            if (use_absolute) {
                args[0] = AT_FDCWD;
            }
            args[1] = reinterpret_cast<long>(redirected);
            long result = callSyscall(fn, number, args);
            result = reverseRedirectedReadlinkResult(reinterpret_cast<char *>(args[2]),
                                                     static_cast<size_t>(args[3]),
                                                     static_cast<ssize_t>(result));
            const char *redirected_log = use_absolute ? redirected : resolved_log.path;
            logReadlinkPath("syscall.readlinkat", resolved_log.path, redirected_log, nullptr,
                            static_cast<ssize_t>(result), false, __builtin_return_address(0));
            return result;
        }
#endif
        default:
            return callSyscall(fn, number, args);
    }
}

extern "C" void *dlopen(const char *filename, int flags) {
    void *caller = __builtin_return_address(0);
    DlopenFn fn = resolveSymbol(&gOrigDlopen, "dlopen");
    void *result = fn == nullptr ? nullptr : fn(filename, flags);
    patchAfterDynamicLoad("dlopen", filename, result, caller);
    return result;
}

extern "C" void *android_dlopen_ext(const char *filename, int flags, const android_dlextinfo *info) {
    void *caller = __builtin_return_address(0);
    AndroidDlopenExtFn fn = resolveSymbol(&gOrigAndroidDlopenExt, "android_dlopen_ext");
    void *result = fn == nullptr ? nullptr : fn(filename, flags, info);
    patchAfterDynamicLoad("android_dlopen_ext", filename, result, caller);
    return result;
}

extern "C" void *blackbox_dlsym(void *handle, const char *symbol) {
    void *caller = __builtin_return_address(0);
    void *replacement = resolveDlsymReplacement(symbol);
    DlsymFn fn = gOrigDlsym == nullptr ? reinterpret_cast<DlsymFn>(dlsym) : gOrigDlsym;
    void *real = fn == nullptr ? nullptr : fn(handle, symbol);
    void *result = replacement != nullptr ? replacement : real;
    logDlsymProbe(symbol, result, replacement != nullptr, caller);
    return result;
}

extern "C" int dladdr(const void *addr, Dl_info *info) {
    DladdrFn fn = resolveSymbol(&gOrigDladdr, "dladdr");
    if (fn == nullptr) {
        errno = ENOSYS;
        return 0;
    }

    int result = fn(addr, info);
    const char *original = result != 0 && info != nullptr ? info->dli_fname : nullptr;
    const char *sanitized = sanitizeDladdrPath(original);
    if (!samePath(original, sanitized) && info != nullptr) {
        info->dli_fname = sanitized;
    }
    logDladdrPath(original, sanitized, result);
    return result;
}

extern "C" pid_t fork(void) {
    ForkFn fn = resolveSymbol(&gOrigFork, "fork");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    pid_t result = fn();
    logProcessProbe("fork", 0, nullptr, static_cast<int>(result), __builtin_return_address(0));
    return result;
}

extern "C" pid_t vfork(void) {
    VforkFn fn = resolveSymbol(&gOrigVfork, "vfork");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    pid_t result = fn();
    if (result != 0) {
        logProcessProbe("vfork", 0, nullptr, static_cast<int>(result), __builtin_return_address(0));
    }
    return result;
}

extern "C" int clone(int (*fn_arg)(void *), void *child_stack, int flags, void *arg, ...) {
    void *parent_tid = nullptr;
    void *tls = nullptr;
    void *child_tid = nullptr;
    if ((flags & (CLONE_PARENT_SETTID | CLONE_SETTLS | CLONE_CHILD_SETTID | CLONE_CHILD_CLEARTID)) != 0) {
        va_list args;
        va_start(args, arg);
        parent_tid = va_arg(args, void *);
        tls = va_arg(args, void *);
        child_tid = va_arg(args, void *);
        va_end(args);
    }

    CloneFn fn = resolveSymbol(&gOrigClone, "clone");
    if (fn == nullptr) {
        errno = ENOSYS;
        return -1;
    }

    int result;
    if ((flags & (CLONE_PARENT_SETTID | CLONE_SETTLS | CLONE_CHILD_SETTID | CLONE_CHILD_CLEARTID)) != 0) {
        result = fn(fn_arg, child_stack, flags, arg, parent_tid, tls, child_tid);
    } else {
        result = fn(fn_arg, child_stack, flags, arg);
    }
    logProcessProbe("clone", flags, nullptr, result, __builtin_return_address(0));
    return result;
}

extern "C" int execve(const char *pathname, char *const argv[], char *const envp[]) {
    logProcessProbe("execve.before", 0, pathname, 0, __builtin_return_address(0));
    ExecveFn fn = resolveSymbol(&gOrigExecve, "execve");
    if (fn == nullptr) {
        errno = ENOSYS;
        logProcessProbe("execve", 0, pathname, -1, __builtin_return_address(0));
        return -1;
    }
    int result = fn(pathname, argv, envp);
    logProcessProbe("execve", 0, pathname, result, __builtin_return_address(0));
    return result;
}

extern "C" int pthread_create(pthread_t *thread, const pthread_attr_t *attr,
                              void *(*start_routine)(void *), void *arg) {
    PthreadCreateFn fn = resolveSymbol(&gOrigPthreadCreate, "pthread_create");
    if (fn == nullptr) {
        logPthreadCreateProbe("pthread_create",
                              reinterpret_cast<void *>(start_routine),
                              ENOSYS,
                              __builtin_return_address(0));
        return ENOSYS;
    }
    void *(*requested_start_routine)(void *) = start_routine;
    bool app_owned_thread = shouldMarkAppOwnedThread(
            reinterpret_cast<void *>(requested_start_routine),
            __builtin_return_address(0));
    AppOwnedPthreadStartContext *context = nullptr;
    if (app_owned_thread && requested_start_routine != nullptr) {
        context = reinterpret_cast<AppOwnedPthreadStartContext *>(
                malloc(sizeof(AppOwnedPthreadStartContext)));
        if (context != nullptr) {
            context->start_routine = requested_start_routine;
            context->arg = arg;
            start_routine = appOwnedPthreadStartTrampoline;
            arg = context;
        }
    }
    int result = fn(thread, attr, start_routine, arg);
    if (result != 0 && context != nullptr) {
        free(context);
    }
    if (result == 0 && app_owned_thread && thread != nullptr) {
        rememberAppOwnedNativeThread(*thread);
    }
    logPthreadCreateProbe("pthread_create",
                          reinterpret_cast<void *>(requested_start_routine),
                          result,
                          __builtin_return_address(0));
    return result;
}

extern "C" int kill(pid_t pid, int signal) {
    logNativeTerminationProbe("kill", pid, signal, 0, __builtin_return_address(0), currentStackPointer());
    if (shouldBlockNativeSignal(pid, signal)) {
        logNativeTerminationBlocked("kill", pid, signal, 0, __builtin_return_address(0));
        return 0;
    }
    return rawKill(pid, signal);
}

extern "C" int tkill(pid_t tid, int signal) {
    logNativeTerminationProbe("tkill", tid, signal, 0, __builtin_return_address(0), currentStackPointer());
    if (isNativeTerminationShieldEnabled() && isTerminationSignal(signal)) {
        logNativeTerminationBlocked("tkill", tid, signal, 0, __builtin_return_address(0));
        return 0;
    }
    return rawTkill(tid, signal);
}

extern "C" int tgkill(int tgid, int tid, int signal) {
    logNativeTerminationProbe("tgkill", tid, signal, 0, __builtin_return_address(0), currentStackPointer());
    if (shouldBlockNativeThreadSignal(tgid, signal)) {
        logNativeTerminationBlocked("tgkill", tid, signal, 0, __builtin_return_address(0));
        return 0;
    }
    return rawTgkill(tgid, tid, signal);
}

extern "C" int raise(int signal) {
    logNativeTerminationProbe("raise", getpid(), signal, 0, __builtin_return_address(0), currentStackPointer());
    if (isNativeTerminationShieldEnabled() && isTerminationSignal(signal)) {
        logNativeTerminationBlocked("raise", getpid(), signal, 0, __builtin_return_address(0));
        return 0;
    }
    return rawTgkill(getpid(), rawThreadId(), signal);
}

extern "C" void abort() {
    logNativeTerminationProbe("abort", getpid(), kSignalAbort, 0, __builtin_return_address(0), currentStackPointer());
    if (shouldBlockNativeExit()) {
        logNativeTerminationBlocked("abort", getpid(), kSignalAbort, 0, __builtin_return_address(0));
        return;
    }
    rawAbortProcess();
}

extern "C" void exit(int status) {
    logNativeTerminationProbe("exit", getpid(), 0, status, __builtin_return_address(0), currentStackPointer());
    if (shouldBlockNativeExit()) {
        logNativeTerminationBlocked("exit", getpid(), 0, status, __builtin_return_address(0));
        return;
    }
    rawExitProcess(status);
}

extern "C" void _exit(int status) {
    logNativeTerminationProbe("_exit", getpid(), 0, status, __builtin_return_address(0), currentStackPointer());
    if (shouldBlockNativeExit()) {
        logNativeTerminationBlocked("_exit", getpid(), 0, status, __builtin_return_address(0));
        return;
    }
    rawExitProcess(status);
}

extern "C" void _Exit(int status) {
    logNativeTerminationProbe("_Exit", getpid(), 0, status, __builtin_return_address(0), currentStackPointer());
    if (shouldBlockNativeExit()) {
        logNativeTerminationBlocked("_Exit", getpid(), 0, status, __builtin_return_address(0));
        return;
    }
    rawExitProcess(status);
}

PineNativeInlineHookFuncNoBackupFn resolvePineNativeInlineHookFuncNoBackup() {
    auto hook_func = reinterpret_cast<PineNativeInlineHookFuncNoBackupFn>(
            dlsym(RTLD_DEFAULT, "PineNativeInlineHookFuncNoBackup"));
    if (hook_func != nullptr) {
        return hook_func;
    }

    DlopenFn open_lib = resolveSymbol(&gOrigDlopen, "dlopen");
    void *pine_handle = open_lib == nullptr ? nullptr : open_lib("libpine.so", RTLD_NOW);
    if (pine_handle == nullptr) {
        ALOGD("native direct libc termination hook skipped: libpine unavailable errno=%d", errno);
        return nullptr;
    }
    return reinterpret_cast<PineNativeInlineHookFuncNoBackupFn>(
            dlsym(pine_handle, "PineNativeInlineHookFuncNoBackup"));
}

void installDirectLibcTerminationHooks() {
    if (gDirectLibcTerminationHooksInstalled || gDirectLibcTerminationHooksInstalling) {
        return;
    }
    gDirectLibcTerminationHooksInstalling = true;

    PineNativeInlineHookFuncNoBackupFn hook_func = resolvePineNativeInlineHookFuncNoBackup();
    if (hook_func == nullptr) {
        ALOGD("native direct libc termination hook skipped: Pine inline hook unavailable");
        gDirectLibcTerminationHooksInstalling = false;
        return;
    }

    DlopenFn open_lib = resolveSymbol(&gOrigDlopen, "dlopen");
    DlsymFn sym = resolveSymbol(&gOrigDlsym, "dlsym");
    void *libc_handle = open_lib == nullptr ? nullptr : open_lib("libc.so", RTLD_NOW);
    if (libc_handle == nullptr || sym == nullptr) {
        ALOGD("native direct libc termination hook skipped: libc handle=%p dlsym=%p errno=%d",
              libc_handle,
              reinterpret_cast<void *>(sym),
              errno);
        gDirectLibcTerminationHooksInstalling = false;
        return;
    }

    struct DirectHookSpec {
        const char *symbol;
        void *replacement;
    };
    DirectHookSpec specs[] = {
            {"kill", reinterpret_cast<void *>(static_cast<KillFn>(kill))},
            {"tkill", reinterpret_cast<void *>(static_cast<KillFn>(tkill))},
            {"tgkill", reinterpret_cast<void *>(static_cast<TgkillFn>(tgkill))},
            {"raise", reinterpret_cast<void *>(static_cast<RaiseFn>(raise))},
            {"abort", reinterpret_cast<void *>(static_cast<AbortFn>(abort))},
            {"exit", reinterpret_cast<void *>(static_cast<ExitFn>(exit))},
            {"_exit", reinterpret_cast<void *>(static_cast<ExitFn>(_exit))},
            {"_Exit", reinterpret_cast<void *>(static_cast<ExitFn>(_Exit))},
    };

    int patched = 0;
    for (const DirectHookSpec &spec : specs) {
        void *real_symbol = sym(libc_handle, spec.symbol);
        if (real_symbol == nullptr) {
            ALOGD("native direct libc termination hook missing symbol=%s", spec.symbol);
            continue;
        }
        if (real_symbol == spec.replacement) {
            patched++;
            continue;
        }
        hook_func(real_symbol, spec.replacement);
        patched++;
        ALOGD("native direct libc termination hook symbol=%s target=%p replacement=%p",
              spec.symbol,
              real_symbol,
              spec.replacement);
    }
    gDirectLibcTerminationHooksInstalled = patched > 0;
    gDirectLibcTerminationHooksInstalling = false;
    ALOGD("native direct libc termination hook patched=%d attempted=%zu",
          patched,
          sizeof(specs) / sizeof(specs[0]));
}

void installDirectLibcProcMapsHooks() {
    if (gDirectLibcProcMapsHooksInstalled || gDirectLibcProcMapsHooksInstalling) {
        return;
    }
    if (!isProcMapsPathSanitizationEnabled()
        && !isTransientProcMapsEnabled()
        && !isProcShimEnabled()) {
        return;
    }
    gDirectLibcProcMapsHooksInstalling = true;

    PineNativeInlineHookFuncNoBackupFn hook_func = resolvePineNativeInlineHookFuncNoBackup();
    if (hook_func == nullptr) {
        ALOGD("native direct proc maps open hook skipped: Pine inline hook unavailable");
        gDirectLibcProcMapsHooksInstalling = false;
        return;
    }

    DlopenFn open_lib = resolveSymbol(&gOrigDlopen, "dlopen");
    DlsymFn sym = resolveSymbol(&gOrigDlsym, "dlsym");
    void *libc_handle = open_lib == nullptr ? nullptr : open_lib("libc.so", RTLD_NOW);
    if (libc_handle == nullptr || sym == nullptr) {
        ALOGD("native direct proc maps open hook skipped: libc handle=%p dlsym=%p errno=%d",
              libc_handle,
              reinterpret_cast<void *>(sym),
              errno);
        gDirectLibcProcMapsHooksInstalling = false;
        return;
    }

    struct DirectOpenHookSpec {
        const char *symbol;
        void *replacement;
    };
    DirectOpenHookSpec specs[] = {
            {"open", reinterpret_cast<void *>(static_cast<OpenFn>(blackbox_direct_open))},
            {"open64", reinterpret_cast<void *>(static_cast<OpenFn>(blackbox_direct_open))},
            {"__open_2", reinterpret_cast<void *>(static_cast<Open2Fn>(blackbox_direct_open_2))},
            {"openat", reinterpret_cast<void *>(static_cast<OpenAtFn>(blackbox_direct_openat))},
            {"__openat", reinterpret_cast<void *>(static_cast<OpenAtFn>(blackbox_direct_openat))},
            {"__openat_2", reinterpret_cast<void *>(static_cast<OpenAt2Fn>(blackbox_direct_openat_2))},
    };

    int patched = 0;
    for (const DirectOpenHookSpec &spec : specs) {
        void *real_symbol = sym(libc_handle, spec.symbol);
        if (real_symbol == nullptr) {
            ALOGD("native direct proc maps open hook missing symbol=%s", spec.symbol);
            continue;
        }
        if (real_symbol == spec.replacement) {
            patched++;
            continue;
        }
        hook_func(real_symbol, spec.replacement);
        patched++;
        ALOGD("native direct proc maps open hook symbol=%s target=%p replacement=%p",
              spec.symbol,
              real_symbol,
              spec.replacement);
    }
    gDirectLibcProcMapsHooksInstalled = patched > 0;
    gDirectLibcProcMapsHooksInstalling = false;
    ALOGD("native direct proc maps open hook patched=%d attempted=%zu",
          patched,
          sizeof(specs) / sizeof(specs[0]));
}

bool patchCodeBytes(void *target_addr, const uint8_t *patch, size_t patch_size) {
    if (target_addr == nullptr || patch == nullptr || patch_size == 0) {
        return false;
    }
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) {
        return false;
    }
    uintptr_t start = reinterpret_cast<uintptr_t>(target_addr)
                      & ~(static_cast<uintptr_t>(page_size) - 1U);
    uintptr_t end = (reinterpret_cast<uintptr_t>(target_addr) + patch_size
                     + static_cast<uintptr_t>(page_size) - 1U)
                    & ~(static_cast<uintptr_t>(page_size) - 1U);
    size_t length = static_cast<size_t>(end - start);
    if (mprotect(reinterpret_cast<void *>(start), length,
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        ALOGD("native direct libc metadata hook code mprotect rwx failed target=%p errno=%d",
              target_addr, errno);
        return false;
    }
    memcpy(target_addr, patch, patch_size);
    __builtin___clear_cache(reinterpret_cast<char *>(target_addr),
                            reinterpret_cast<char *>(target_addr) + patch_size);
    if (mprotect(reinterpret_cast<void *>(start), length, PROT_READ | PROT_EXEC) != 0) {
        ALOGD("native direct libc metadata hook code mprotect rx failed target=%p errno=%d",
              target_addr, errno);
    }
    return true;
}

bool installThumbAwareDirectJump(void *target,
                                 void *replacement,
                                 const char *symbol,
                                 PineNativeInlineHookFuncNoBackupFn hook_func) {
#if !defined(__arm__)
    if (hook_func == nullptr) {
        return false;
    }
    hook_func(target, replacement);
    return true;
#else
    uintptr_t target_pc = reinterpret_cast<uintptr_t>(target);
    if ((target_pc & 1U) == 0 || (target_pc & 0x3U) == 1U) {
        if (hook_func == nullptr) {
            return false;
        }
        hook_func(target, replacement);
        return true;
    }

    uintptr_t target_addr = target_pc & ~static_cast<uintptr_t>(1U);
    if ((target_addr & 0x3U) == 2U) {
        uint8_t patch[12] = {};
        size_t patch_size = 12;
        auto *halfwords = reinterpret_cast<uint16_t *>(patch);
        halfwords[0] = 0xf8df; // ldr.w pc, [pc, #4]
        halfwords[1] = 0xf004;
        halfwords[2] = 0xbf00; // align the literal address for 2 mod 4 targets.
        uint32_t jump_target = static_cast<uint32_t>(reinterpret_cast<uintptr_t>(replacement));
        memcpy(patch + 6, &jump_target, sizeof(jump_target));
        halfwords[5] = 0xbf00;
        bool patched = patchCodeBytes(reinterpret_cast<void *>(target_addr), patch, patch_size);
        ALOGD("native direct libc metadata hook thumb-aware symbol=%s target=%p replacement=%p patch_size=%zu patched=%d",
              symbol == nullptr ? "unknown" : symbol,
              target,
              replacement,
              patch_size,
              patched ? 1 : 0);
        return patched;
    }

    if (hook_func == nullptr) {
        return false;
    }
    hook_func(target, replacement);
    return true;
#endif
}

void installDirectLibcMetadataHooks() {
    if (gDirectLibcMetadataHooksInstalled || gDirectLibcMetadataHooksInstalling) {
        return;
    }
    gDirectLibcMetadataHooksInstalling = true;

#if !defined(__arm__)
    ALOGD("native direct libc metadata hook skipped: unsupported arch");
    gDirectLibcMetadataHooksInstalling = false;
    return;
#else
    PineNativeInlineHookFuncNoBackupFn hook_func = resolvePineNativeInlineHookFuncNoBackup();
    if (hook_func == nullptr) {
        ALOGD("native direct libc metadata hook skipped: Pine inline hook unavailable");
        gDirectLibcMetadataHooksInstalling = false;
        return;
    }

    DlopenFn open_lib = resolveSymbol(&gOrigDlopen, "dlopen");
    DlsymFn sym = resolveSymbol(&gOrigDlsym, "dlsym");
    void *libc_handle = open_lib == nullptr ? nullptr : open_lib("libc.so", RTLD_NOW);
    if (libc_handle == nullptr || sym == nullptr) {
        ALOGD("native direct libc metadata hook skipped: libc handle=%p dlsym=%p errno=%d",
              libc_handle,
              reinterpret_cast<void *>(sym),
              errno);
        gDirectLibcMetadataHooksInstalling = false;
        return;
    }

    struct DirectMetadataHookSpec {
        const char *symbol;
        void *replacement;
    };
    DirectMetadataHookSpec specs[] = {
            {"access", reinterpret_cast<void *>(static_cast<AccessFn>(access))},
            {"stat", reinterpret_cast<void *>(static_cast<StatFn>(stat))},
            {"lstat", reinterpret_cast<void *>(static_cast<StatFn>(lstat))},
            {"readlink", reinterpret_cast<void *>(static_cast<ReadlinkFn>(readlink))},
            {"mkdir", reinterpret_cast<void *>(static_cast<MkdirFn>(mkdir))},
    };

    int patched = 0;
    for (const DirectMetadataHookSpec &spec : specs) {
        void *real_symbol = sym(libc_handle, spec.symbol);
        if (real_symbol == nullptr) {
            ALOGD("native direct libc metadata hook missing symbol=%s", spec.symbol);
            continue;
        }
        if (real_symbol == spec.replacement) {
            patched++;
            continue;
        }
        if (!installThumbAwareDirectJump(real_symbol, spec.replacement, spec.symbol, hook_func)) {
            continue;
        }
        patched++;
        ALOGD("native direct libc metadata hook symbol=%s target=%p replacement=%p",
              spec.symbol,
              real_symbol,
              spec.replacement);
    }
    gDirectLibcMetadataHooksInstalled = patched > 0;
    gDirectLibcMetadataHooksInstalling = false;
    ALOGD("native direct libc metadata hook patched=%d attempted=%zu",
          patched,
          sizeof(specs) / sizeof(specs[0]));
#endif
}

void *createNativeFunctionBackup(void *target, size_t replaced_size) {
    if (target == nullptr || replaced_size == 0) {
        return nullptr;
    }

#if defined(__arm__)
    auto alignThumbTrampolineSize = [](size_t size) -> size_t {
        return (size + 3U) & ~static_cast<size_t>(3U);
    };
    uintptr_t target_pc = reinterpret_cast<uintptr_t>(target);
    if ((target_pc & 1U) == 0) {
        ALOGD("native direct pthread_create hook skipped: non-thumb target=%p", target);
        return nullptr;
    }
    uintptr_t target_addr = target_pc & ~static_cast<uintptr_t>(1U);
    size_t jump_offset = alignThumbTrampolineSize(replaced_size);
    size_t trampoline_size = jump_offset + 8;
    auto trampoline = reinterpret_cast<uint8_t *>(mmap(nullptr,
                                                       trampoline_size,
                                                       PROT_READ | PROT_WRITE | PROT_EXEC,
                                                       MAP_PRIVATE | MAP_ANONYMOUS,
                                                       -1,
                                                       0));
    if (trampoline == MAP_FAILED) {
        ALOGD("native direct pthread_create hook skipped: backup mmap failed errno=%d", errno);
        return nullptr;
    }
    memcpy(trampoline, reinterpret_cast<void *>(target_addr), replaced_size);
    for (size_t off = replaced_size; off < jump_offset; off += sizeof(uint16_t)) {
        *reinterpret_cast<uint16_t *>(trampoline + off) = 0xbf00;
    }
    uint16_t *jump = reinterpret_cast<uint16_t *>(trampoline + jump_offset);
    jump[0] = 0xf8df;
    jump[1] = 0xf000;
    auto jump_target = reinterpret_cast<uint32_t *>(trampoline + jump_offset + 4);
    *jump_target = static_cast<uint32_t>((target_addr + replaced_size) | 1U);
    __builtin___clear_cache(reinterpret_cast<char *>(trampoline),
                            reinterpret_cast<char *>(trampoline + trampoline_size));
    return reinterpret_cast<void *>(reinterpret_cast<uintptr_t>(trampoline) | 1U);
#elif defined(__aarch64__)
    size_t trampoline_size = replaced_size + 16;
    auto trampoline = reinterpret_cast<uint8_t *>(mmap(nullptr,
                                                       trampoline_size,
                                                       PROT_READ | PROT_WRITE | PROT_EXEC,
                                                       MAP_PRIVATE | MAP_ANONYMOUS,
                                                       -1,
                                                       0));
    if (trampoline == MAP_FAILED) {
        ALOGD("native direct pthread_create hook skipped: backup mmap failed errno=%d", errno);
        return nullptr;
    }
    memcpy(trampoline, target, replaced_size);
    uint32_t *jump = reinterpret_cast<uint32_t *>(trampoline + replaced_size);
    jump[0] = 0x58000051U;
    jump[1] = 0xd61f0220U;
    auto jump_target = reinterpret_cast<uint64_t *>(trampoline + replaced_size + 8);
    *jump_target = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(target) + replaced_size);
    __builtin___clear_cache(reinterpret_cast<char *>(trampoline),
                            reinterpret_cast<char *>(trampoline + trampoline_size));
    return trampoline;
#else
    (void) replaced_size;
    ALOGD("native direct pthread_create hook skipped: unsupported arch target=%p", target);
    return nullptr;
#endif
}

void installDirectLibcPthreadCreateHook() {
    if (gDirectLibcPthreadCreateHookInstalled || gDirectLibcPthreadCreateHookInstalling) {
        return;
    }
    gDirectLibcPthreadCreateHookInstalling = true;

    PineNativeInlineHookFuncNoBackupFn hook_func = resolvePineNativeInlineHookFuncNoBackup();
    if (hook_func == nullptr) {
        ALOGD("native direct pthread_create hook skipped: Pine inline hook unavailable");
        gDirectLibcPthreadCreateHookInstalling = false;
        return;
    }

    DlopenFn open_lib = resolveSymbol(&gOrigDlopen, "dlopen");
    DlsymFn sym = resolveSymbol(&gOrigDlsym, "dlsym");
    void *libc_handle = open_lib == nullptr ? nullptr : open_lib("libc.so", RTLD_NOW);
    if (libc_handle == nullptr || sym == nullptr) {
        ALOGD("native direct pthread_create hook skipped: libc handle=%p dlsym=%p errno=%d",
              libc_handle,
              reinterpret_cast<void *>(sym),
              errno);
        gDirectLibcPthreadCreateHookInstalling = false;
        return;
    }

    void *real_symbol = sym(libc_handle, "pthread_create");
    void *replacement = reinterpret_cast<void *>(static_cast<PthreadCreateFn>(pthread_create));
    if (real_symbol == nullptr || real_symbol == replacement) {
        ALOGD("native direct pthread_create hook skipped: symbol=%p replacement=%p",
              real_symbol,
              replacement);
        gDirectLibcPthreadCreateHookInstalling = false;
        return;
    }

#if defined(__aarch64__)
    constexpr size_t kPthreadCreateDirectPatchSize = 16;
#else
    constexpr size_t kPthreadCreateDirectPatchSize = 8;
#endif
    void *backup = createNativeFunctionBackup(real_symbol, kPthreadCreateDirectPatchSize);
    if (backup == nullptr) {
        gDirectLibcPthreadCreateHookInstalling = false;
        return;
    }

    gOrigPthreadCreate = reinterpret_cast<PthreadCreateFn>(backup);
    hook_func(real_symbol, replacement);
    gDirectLibcPthreadCreateHookInstalled = true;
    gDirectLibcPthreadCreateHookInstalling = false;
    ALOGD("native direct pthread_create hook symbol=pthread_create target=%p backup=%p replacement=%p",
          real_symbol,
          backup,
          replacement);
}

extern "C" void installNativeFileHooks() {
    if (gNativeFileHooksInstalling) {
        return;
    }
    gNativeFileHooksInstalling = true;
    void *dlopen_hook = shouldPatchDlopen()
                        ? reinterpret_cast<void *>(static_cast<DlopenFn>(dlopen))
                        : nullptr;
    void *android_dlopen_ext_hook = shouldPatchDlopen()
                                    ? reinterpret_cast<void *>(static_cast<AndroidDlopenExtFn>(android_dlopen_ext))
                                    : nullptr;
    void *dlsym_hook = shouldPatchDlsym()
                       ? reinterpret_cast<void *>(static_cast<DlsymFn>(blackbox_dlsym))
                       : nullptr;
    void *pthread_create_hook = shouldPatchPthreadCreate()
                                ? reinterpret_cast<void *>(static_cast<PthreadCreateFn>(pthread_create))
                                : nullptr;
    NativePatchSpec specs[] = {
            {"open", reinterpret_cast<void *>(static_cast<OpenFn>(open)), reinterpret_cast<void **>(&gOrigOpen), 0},
            {"open64", reinterpret_cast<void *>(static_cast<OpenFn>(open64)), reinterpret_cast<void **>(&gOrigOpen64), 0},
            {"__open_2", reinterpret_cast<void *>(static_cast<Open2Fn>(__open_2)), reinterpret_cast<void **>(&gOrigOpen2), 0},
            {"openat", reinterpret_cast<void *>(static_cast<OpenAtFn>(openat)), reinterpret_cast<void **>(&gOrigOpenAt), 0},
            {"__openat_2", reinterpret_cast<void *>(static_cast<OpenAt2Fn>(__openat_2)), reinterpret_cast<void **>(&gOrigOpenAt2), 0},
            {"fopen", reinterpret_cast<void *>(static_cast<FopenFn>(fopen)), reinterpret_cast<void **>(&gOrigFopen), 0},
            {"fopen64", reinterpret_cast<void *>(static_cast<FopenFn>(fopen64)), reinterpret_cast<void **>(&gOrigFopen64), 0},
            {"opendir", reinterpret_cast<void *>(static_cast<OpendirFn>(opendir)), reinterpret_cast<void **>(&gOrigOpendir), 0},
            {"access", reinterpret_cast<void *>(static_cast<AccessFn>(access)), reinterpret_cast<void **>(&gOrigAccess), 0},
            {"faccessat", reinterpret_cast<void *>(static_cast<FaccessatFn>(faccessat)), reinterpret_cast<void **>(&gOrigFaccessat), 0},
            {"mkdir", reinterpret_cast<void *>(static_cast<MkdirFn>(mkdir)), reinterpret_cast<void **>(&gOrigMkdir), 0},
            {"mkdirat", reinterpret_cast<void *>(static_cast<MkdirAtFn>(mkdirat)), reinterpret_cast<void **>(&gOrigMkdirAt), 0},
            {"stat", reinterpret_cast<void *>(static_cast<StatFn>(stat)), reinterpret_cast<void **>(&gOrigStat), 0},
            {"lstat", reinterpret_cast<void *>(static_cast<StatFn>(lstat)), reinterpret_cast<void **>(&gOrigLstat), 0},
            {"fstat", reinterpret_cast<void *>(static_cast<FstatFn>(fstat)), reinterpret_cast<void **>(&gOrigFstat), 0},
            {"fstatat", reinterpret_cast<void *>(static_cast<FstatatFn>(fstatat)), reinterpret_cast<void **>(&gOrigFstatat), 0},
            {"statx", reinterpret_cast<void *>(static_cast<StatxFn>(statx)), reinterpret_cast<void **>(&gOrigStatx), 0},
            {"statfs", reinterpret_cast<void *>(static_cast<StatfsFn>(statfs)), reinterpret_cast<void **>(&gOrigStatfs), 0},
            {"statfs64", reinterpret_cast<void *>(static_cast<Statfs64Fn>(statfs64)), reinterpret_cast<void **>(&gOrigStatfs64), 0},
            {"readlink", reinterpret_cast<void *>(static_cast<ReadlinkFn>(readlink)), reinterpret_cast<void **>(&gOrigReadlink), 0},
            {"__readlink_chk", reinterpret_cast<void *>(static_cast<ReadlinkChkFn>(__readlink_chk)), reinterpret_cast<void **>(&gOrigReadlinkChk), 0},
            {"readlinkat", reinterpret_cast<void *>(static_cast<ReadlinkAtFn>(readlinkat)), reinterpret_cast<void **>(&gOrigReadlinkAt), 0},
            {"__readlinkat_chk", reinterpret_cast<void *>(static_cast<ReadlinkAtChkFn>(__readlinkat_chk)), reinterpret_cast<void **>(&gOrigReadlinkAtChk), 0},
            {"getuid", reinterpret_cast<void *>(static_cast<GetUidFn>(getuid)), reinterpret_cast<void **>(&gOrigGetUid), 0},
            {"geteuid", reinterpret_cast<void *>(static_cast<GetUidFn>(geteuid)), reinterpret_cast<void **>(&gOrigGetEuid), 0},
            {"getgid", reinterpret_cast<void *>(static_cast<GetGidFn>(getgid)), reinterpret_cast<void **>(&gOrigGetGid), 0},
            {"getegid", reinterpret_cast<void *>(static_cast<GetGidFn>(getegid)), reinterpret_cast<void **>(&gOrigGetEgid), 0},
            {"getgroups", reinterpret_cast<void *>(static_cast<GetGroupsFn>(getgroups)), reinterpret_cast<void **>(&gOrigGetGroups), 0},
            {"realpath", reinterpret_cast<void *>(static_cast<RealpathFn>(realpath)), reinterpret_cast<void **>(&gOrigRealpath), 0},
            {"syscall", reinterpret_cast<void *>(static_cast<SyscallFn>(syscall)), reinterpret_cast<void **>(&gOrigSyscall), 0},
            {"dlopen", dlopen_hook, reinterpret_cast<void **>(&gOrigDlopen), 0},
            {"android_dlopen_ext", android_dlopen_ext_hook, reinterpret_cast<void **>(&gOrigAndroidDlopenExt), 0},
            {"dlsym", dlsym_hook, reinterpret_cast<void **>(&gOrigDlsym), 0},
            {"pthread_create", pthread_create_hook, reinterpret_cast<void **>(&gOrigPthreadCreate), 0},
#if !defined(__LP64__) && (!defined(__ANDROID_API__) || __ANDROID_API__ < 21)
            {"stat64", reinterpret_cast<void *>(static_cast<Stat64Fn>(stat64)), reinterpret_cast<void **>(&gOrigStat64), 0},
            {"lstat64", reinterpret_cast<void *>(static_cast<Stat64Fn>(lstat64)), reinterpret_cast<void **>(&gOrigLstat64), 0},
            {"fstat64", reinterpret_cast<void *>(static_cast<Fstat64Fn>(fstat64)), reinterpret_cast<void **>(&gOrigFstat64), 0},
#endif
    };
    NativePatchContext context = {specs, sizeof(specs) / sizeof(specs[0])};
    dl_iterate_phdr(patchLoadedObject, &context);
    int patched = 0;
    for (size_t i = 0; i < context.spec_count; ++i) {
        patched += context.specs[i].patched;
    }
    if (patched > 0 || !gNativeFileHooksInstalled) {
        ALOGD("native file hook patched=%d installed=%d", patched, gNativeFileHooksInstalled ? 1 : 0);
    }
    gNativeFileHooksInstalled = true;
    gNativeFileHooksInstalling = false;
}
