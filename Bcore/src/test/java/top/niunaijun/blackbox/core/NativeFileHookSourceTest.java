package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;
import static top.niunaijun.blackbox.core.SourceAssertions.sliceBetween;

public class NativeFileHookSourceTest {

    @Test
    public void nativeFileHooksRedirectProcPathsBeforeLibcOpen() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Native hook should export open for later-loaded hardened libraries",
                source.contains("extern \"C\" int open("));
        assertTrue("Native hook should export openat for bionic/libc file probes",
                source.contains("extern \"C\" int openat("));
        assertTrue("Native hook should export fopen for stdio file probes",
                source.contains("extern \"C\" FILE *fopen("));
        assertTrue("Native hook should export syscall for packed protectors using direct libc syscall",
                source.contains("extern \"C\" long syscall("));
        assertTrue("Native syscall hook should handle openat probes",
                source.contains("case __NR_openat:"));
        assertTrue("Native file probes should pass through IOCore redirect rules",
                source.contains("IO::redirectPath(pathname)"));
    }

    @Test
    public void nativeFileHooksPatchLoadedAndFutureNativeLibraries() throws Exception {
        String nativeFileHook = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String boxCore = readSource(
                "src/main/cpp/BoxCore.cpp",
                "Bcore/src/main/cpp/BoxCore.cpp");
        String runtimeHook = readSource(
                "src/main/cpp/Hook/RuntimeHook.cpp",
                "Bcore/src/main/cpp/Hook/RuntimeHook.cpp");

        assertTrue("Native IO replacement exports are not sufficient; loaded ELF PLT slots must be patched",
                nativeFileHook.contains("installNativeFileHooks")
                        && nativeFileHook.contains("dl_iterate_phdr")
                        && nativeFileHook.contains("patchLoadedObject"));
        assertTrue("IO hooks must be installed during NativeCore.enableIO for already-loaded libcore/NIO code",
                boxCore.contains("installNativeFileHooks();"));
        assertTrue("Runtime.nativeLoad should re-run native IO PLT patching after app libraries are loaded",
                runtimeHook.contains("installNativeFileHooks();"));
    }

    @Test
    public void nativeTerminationShieldCoversDirectLibcSymbolsWithoutDlsymReplacement() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String directSpecs = sliceBetween(source,
                "DirectHookSpec specs[] = {",
                "int patched = 0;");

        assertTrue("Direct libc termination calls resolved by dlsym/libffi should be hooked at the libc symbol entry, not by returning BlackBox pointers from dlsym",
                source.contains("installDirectLibcTerminationHooks")
                        && source.contains("resolvePineNativeInlineHookFuncNoBackup")
                        && source.contains("PineNativeInlineHookFuncNoBackup")
                        && source.contains("open_lib(\"libpine.so\", RTLD_NOW)")
                        && source.contains("open_lib(\"libc.so\", RTLD_NOW)")
                        && source.contains("sym(libc_handle, spec.symbol)")
                        && source.contains("hook_func(real_symbol, spec.replacement)")
                        && source.contains("\"kill\"")
                        && source.contains("\"tgkill\"")
                        && source.contains("\"_exit\""));
        assertFalse("Bionic's libc syscall entry can be a special stub that Pine no-backup inline hooking corrupts; keep syscall coverage in the wrapper/PLT path, not the direct libc entry patch",
                directSpecs.contains("{\"syscall\""));
        assertTrue("The direct libc hook must be installed only after a virtual package termination shield is configured",
                source.contains("setNativeTerminationShieldPackage")
                        && source.contains("installDirectLibcTerminationHooks();"));
        assertTrue("The syscall wrapper must use a private raw kernel syscall path so inline-hooking libc syscall cannot recurse",
                source.contains("rawKernelSyscall6")
                        && source.contains("callKernelSyscall(number, args)")
                        && source.contains("(void) fn;"));
        assertFalse("Direct libc termination coverage must not be implemented by target package hardcoding",
                source.contains("com.bestv.tv.video.iqy.tjdx"));
    }

    @Test
    public void nativeFileHooksCanPatchDynamicLibrariesBeforeJniOnLoadWhenExplicitlyEnabled() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Native hook should include Android's android_dlopen_ext prototype",
                source.contains("#include <android/dlext.h>"));
        assertTrue("Native hook should export dlopen so later native loads re-run PLT patching",
                source.contains("extern \"C\" void *dlopen("));
        assertTrue("Native hook should export android_dlopen_ext to patch Java-loaded libraries before JNI_OnLoad",
                source.contains("extern \"C\" void *android_dlopen_ext("));
        assertTrue("Re-patching immediately after dynamic native loads is observable, so it must be explicit diagnostic only",
                source.contains("debug.blackbox.early_dlopen_repatch")
                        && source.contains("isEarlyDlopenRepatchEnabled"));
        assertTrue("Patching the dynamic-loader entries changes the native loader caller surface, so loader hooks must also be explicit diagnostic only",
                source.contains("debug.blackbox.dlopen_probe")
                        && source.contains("shouldPatchDlopen()"));
        assertTrue("Successful dynamic native loads may re-run PLT patching only when explicitly enabled",
                source.contains("patchAfterDynamicLoad(\"dlopen\"")
                        && source.contains("patchAfterDynamicLoad(\"android_dlopen_ext\"")
                        && source.contains("if (result != nullptr && isEarlyDlopenRepatchEnabled())")
                        && source.contains("installNativeFileHooks();"));
        assertTrue("Dynamic loader entries themselves must be PLT-patched only when dynamic-loader diagnostics or early re-patching are enabled",
                source.contains("void *dlopen_hook = shouldPatchDlopen()")
                        && source.contains("void *android_dlopen_ext_hook = shouldPatchDlopen()")
                        && source.contains("{\"dlopen\", dlopen_hook")
                        && source.contains("{\"android_dlopen_ext\", android_dlopen_ext_hook"));
    }

    @Test
    public void nativeFileHooksCoverFortifiedLibcAndRawAtSyscalls() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Android fortified open wrappers should be redirected too",
                source.contains("extern \"C\" int __open_2(")
                        && source.contains("extern \"C\" int __openat_2("));
        assertTrue("Android fortified readlink wrappers should preserve redirection",
                source.contains("extern \"C\" ssize_t __readlink_chk(")
                        && source.contains("extern \"C\" ssize_t __readlinkat_chk(")
                        && source.contains("\"__readlinkat_chk\""));
        assertTrue("realpath should reverse virtual storage back to public app-data paths",
                source.contains("extern \"C\" char *realpath(")
                        && source.contains("IO::reverseRedirectPath(result)"));
        assertTrue("Raw syscall bridge should cover faccessat/readlinkat, not only openat",
                source.contains("case __NR_faccessat:")
                        && source.contains("case __NR_readlinkat:"));
    }

    @Test
    public void nativeSyscallWrapperReadsOnlyKnownArgumentCountsBeforeDispatch() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String syscallWrapper = sliceBetween(source,
                "extern \"C\" long syscall(long number, ...)",
                "extern \"C\" void *dlopen(");

        assertTrue("variadic syscall wrapper should compute the ABI argument count from the syscall number before va_arg reads",
                source.contains("int syscallArgumentCount(long number)")
                        && source.contains("takeSyscallArgsForNumber(number, va_args, args)"));
        assertFalse("variadic syscall wrapper must not blindly read six arguments for every syscall",
                syscallWrapper.contains("takeSyscallArgs(va_args, args)"));
        assertTrue("zero-argument identity syscalls should not consume missing variadic arguments",
                source.contains("case __NR_getuid:")
                        && source.contains("return 0;"));
        assertTrue("open/openat should avoid reading an absent mode argument unless creation flags require it",
                source.contains("takeOpenSyscallArgs")
                        && source.contains("takeOpenAtSyscallArgs")
                        && source.contains("openFlagsRequireMode"));
        assertTrue("handled fixed-width file and process syscalls should retain their required argument counts",
                source.contains("case __NR_readlinkat:")
                        && source.contains("return 4;")
                        && source.contains("case __NR_statx:")
                        && source.contains("return 5;"));
    }

    @Test
    public void fortifiedOpenWrappersDoNotCallTheirOwnFortifyEntryPoints() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String open2 = sliceBetween(source,
                "extern \"C\" int __open_2(",
                "extern \"C\" int openat(");
        String openat2 = sliceBetween(source,
                "extern \"C\" int __openat_2(",
                "extern \"C\" FILE *fopen(");

        assertTrue("__open_2 must call the resolved libc function pointer; calling open(path, flags) is fortified back into __open_2 and recurses",
                open2.contains("resolveSymbol(&gOrigOpen2, \"__open_2\")")
                        && !open2.contains("return open(pathname, flags);"));
        assertTrue("__openat_2 must call the resolved libc function pointer; calling openat(dirfd, path, flags) is fortified back into __openat_2 and recurses",
                openat2.contains("resolveSymbol(&gOrigOpenAt2, \"__openat_2\")")
                        && !openat2.contains("return openat(dirfd, pathname, flags);"));
    }

    @Test
    public void cmakeCompilesNativeFileHookIntoBlackboxLibrary() throws Exception {
        String cmake = readSource(
                "src/main/cpp/CMakeLists.txt",
                "Bcore/src/main/cpp/CMakeLists.txt");

        assertTrue("blackbox shared library should compile NativeFileHook.cpp",
                cmake.contains("Hook/NativeFileHook.cpp")
                        || cmake.contains("aux_source_directory(Hook SRC3)"));
    }

    @Test
    public void nativeFileHooksLogFocusedSandboxProbePaths() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String focusedOpenFilter = sliceBetween(source,
                "bool shouldLogOpenPath(",
                "bool isRelativeFileProbePath(");

        assertTrue("Verbose native file diagnostics must be opt-in so they do not slow launch or expose a sandbox signal",
                source.contains("kFileProbeProperty")
                        && source.contains("bool isFileProbeEnabled()")
                        && source.contains("if (!isFileProbeEnabled())"));
        assertTrue("Native file diagnostics should remain focused to protector probe paths",
                source.contains("shouldLogOpenPath"));
        assertTrue("Native file diagnostics should include proc probes",
                source.contains("\"/proc/\""));
        assertTrue("Native file diagnostics should include APK probes in the global focused filter",
                focusedOpenFilter.contains("isApkProbePath(pathname)")
                        && focusedOpenFilter.contains("isApkProbePath(redirected)"));
        assertTrue("Native file diagnostics should include app-data probes only through the app-owned fallback",
                source.contains("bool shouldLogAppOwnedNativeFilePath(const char *pathname, const char *redirected)")
                        && source.contains("\"/data/data/\"")
                        && source.contains("\"/data/user/\""));
        assertTrue("Native file diagnostics should log redirect decisions",
                source.contains("ALOGD(\"native file probe"));
    }

    @Test
    public void nativeFileHooksLogCallerOffsetsForEnvironmentProbes() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Native file diagnostics should resolve caller map/file offsets for IDA correlation",
                source.contains("struct CallerLocation")
                        && source.contains("resolveCallerLocation")
                        && source.contains("callerOff=0x%lx")
                        && source.contains("callerMap=%s"));
        assertTrue("Caller resolution should handle anonymous executable maps as well as dladdr-backed libraries",
                source.contains("openRealProcMapsFile()")
                        && source.contains("caller >= start && caller < end")
                        && source.contains("map_offset + (caller - start)"));
        assertTrue("File/proc probe wrappers should pass the protected caller address, not only the hook wrapper address",
                source.contains("logOpenPath(\"fopen\", pathname, redirected, 0, result == nullptr ? -1 : 0, __builtin_return_address(0))")
                        && source.contains("logOpenPath(\"open\", pathname, redirected, flags, result, __builtin_return_address(0))")
                        && source.contains("logOpenPath(\"syscall.openat\", resolved_log.path, redirected_log, static_cast<int>(args[2]), result, __builtin_return_address(0))"));
    }

    @Test
    public void nativeFileHooksCoverProtectorAccessStatAndReadlinkProbes() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Native hook should export access for hardened existence probes",
                source.contains("extern \"C\" int access("));
        assertTrue("Native hook should export faccessat for dirfd-based existence probes",
                source.contains("extern \"C\" int faccessat("));
        assertTrue("Native hook should export stat for metadata checks",
                source.contains("extern \"C\" int stat("));
        assertTrue("Native hook should export lstat for symlink metadata checks",
                source.contains("extern \"C\" int lstat("));
        assertTrue("Native hook should export fstat for fd metadata checks",
                source.contains("extern \"C\" int fstat("));
        assertTrue("Native hook should export readlink for /dev/fd and /proc/self/fd checks",
                source.contains("extern \"C\" ssize_t readlink("));
        assertTrue("Native hook should export readlinkat for dirfd-based symlink checks",
                source.contains("extern \"C\" ssize_t readlinkat("));
        assertTrue("Metadata probes should pass through IOCore redirect rules",
                source.contains("redirectMetadataPath")
                        && source.contains("IO::redirectPath(pathname)"));
        assertTrue("Stat/readlink diagnostics should remain focused to sandbox probe paths",
                source.contains("native stat probe")
                        && source.contains("native readlink probe"));
    }

    @Test
    public void readlinkResultsHideSandboxStoragePathsForFdAndMapsFollowers() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String readlink = sliceBetween(source,
                "extern \"C\" ssize_t readlink(",
                "extern \"C\" ssize_t __readlink_chk(");
        String readlinkat = sliceBetween(source,
                "extern \"C\" ssize_t readlinkat(",
                "extern \"C\" ssize_t __readlinkat_chk(");
        String syscallWrapper = sliceBetween(source,
                "extern \"C\" long syscall(long number, ...)",
                "extern \"C\" void *dlopen(");
        String syscallReadlinkat = sliceBetween(syscallWrapper,
                "case __NR_readlinkat:",
                "default:");

        assertTrue("readlink(/proc/self/fd/*) can reveal the host blackbox storage target for a sanitized maps path; successful results must be rewritten back to the public app-data alias",
                source.contains("reverseRedirectedReadlinkResult")
                        && source.contains("IO::reverseRedirectPath"));
        assertTrue("readlink wrapper must rewrite the returned target bytes, not only redirect the input path",
                readlink.contains("reverseRedirectedReadlinkResult(buf, bufsiz, result)"));
        assertTrue("readlinkat wrapper must rewrite returned target bytes for dirfd-based fd probes",
                readlinkat.contains("reverseRedirectedReadlinkResult(buf, bufsiz, result)"));
        assertTrue("raw syscall readlinkat path must get the same returned-target sanitization as libc wrappers",
                syscallReadlinkat.contains("reverseRedirectedReadlinkResult(reinterpret_cast<char *>(args[2]),")
                        && syscallReadlinkat.contains("static_cast<size_t>(args[3])"));
        assertFalse("Readlink target sanitization must stay package-agnostic",
                source.contains("com.bestv.tv.video.iqy.tjdx"));
    }

    @Test
    public void nativeFileHooksRedirectFilesystemStatProbes() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Native hook should export statfs because protected code can query filesystem metadata without opening a file",
                source.contains("extern \"C\" int statfs("));
        assertTrue("Native hook should export statfs64 for Android 32-bit filesystem metadata callers",
                source.contains("extern \"C\" int statfs64("));
        assertTrue("statfs paths must pass through IOCore redirect rules before reaching bionic",
                source.contains("redirectFilesystemPath")
                        && source.contains("IO::redirectPath(pathname)"));
        assertTrue("Raw syscall hook should cover direct statfs/statfs64 calls from native protectors",
                source.contains("case __NR_statfs:")
                        && source.contains("case __NR_statfs64:"));
        assertTrue("Android 32-bit SYS_statfs64 has path, size, and result-buffer arguments; dropping the third argument returns EFAULT",
                source.contains("case __NR_statfs64:\n            return 3;"));
        assertTrue("Native filesystem metadata diagnostics should be visible for app-data paths",
                source.contains("logStatPath(\"statfs\"")
                        && source.contains("logStatPath(\"syscall.statfs64\""));
    }

    @Test
    public void nativeFileHooksRedirectNativeDirectoryCreationForAppData() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Native hook should export mkdir because packed protectors create app-private directories from native code",
                source.contains("extern \"C\" int mkdir("));
        assertTrue("Native hook should export mkdirat for dirfd-based native directory creation",
                source.contains("extern \"C\" int mkdirat("));
        assertTrue("Native mkdir paths should pass through IOCore redirect rules before reaching bionic",
                source.contains("redirectDirectoryPath")
                        && source.contains("IO::redirectPath(pathname)"));
        assertTrue("Raw syscall hook should cover direct mkdirat calls from native protectors",
                source.contains("case __NR_mkdirat:"));
        assertTrue("Native directory creation diagnostics should be visible for app-data paths",
                source.contains("native mkdir probe")
                        && source.contains("logMkdirPath"));
    }

    @Test
    public void nativeFileHooksHideProcShimBackingFilesFromFdInspection() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Native hooks should recognize stable proc shim fds prepared by RuntimeHook",
                source.contains("isProcShimFd")
                        && source.contains("isActiveProcShimFd")
                        && source.contains("kProcShimFdStart")
                        && source.contains("kProcShimFdEnd"));
        assertTrue("readlink(/dev/fd/N) should report the public proc target, not the backing file",
                source.contains("procShimReadlinkTarget")
                        && source.contains("\"/proc/self/maps\"")
                        && source.contains("\"/proc/version\""));
        assertTrue("fstat on proc shim fds should look like procfs metadata instead of app-private files",
                source.contains("sanitizeProcShimStat")
                        && source.contains("st_size = 0")
                        && source.contains("S_IFREG"));
    }

    @Test
    public void nativeFileHooksDoNotTreatArbitraryFdNumbersAsProcShims() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String activeShim = sliceBetween(source,
                "bool isActiveProcShimFd(",
                "void refreshProcMapsShimForRedirect()");
        String redirectProc = sliceBetween(source,
                "const char *redirectProcProbeToShim(",
                "int procShimFdForReadPath(");
        String shimReadPath = sliceBetween(source,
                "int procShimFdForReadPath(",
                "int openProcShimFdForRead(");

        assertTrue("Only fd93 should be considered active for the early maps shim; fd90/fd91/fd92/fd94 require protected proc shims",
                activeShim.contains("fd == kProcMapsFd")
                        && activeShim.contains("isProcMapsShimReadyForRedirect()")
                        && activeShim.contains("isProtectedProcMapsShimReady()"));
        assertTrue("Direct /proc/self/cmdline and /proc/self/comm redirects must require active protected shim fds, not just any open fd number",
                redirectProc.contains("isActiveProcShimFd(kProcCmdlineFd)")
                        && redirectProc.contains("isActiveProcShimFd(kProcCommFd)"));
        assertTrue("/dev/fd/91 should not be duplicated or sanitized unless fd91 is an active protected shim",
                shimReadPath.contains("isProcShimFdPath(pathname, &fd) && isActiveProcShimFd(fd)"));
    }

    @Test
    public void nativeFileHooksPreserveProcCmdlineMetadataWhenBackedByRedirectedFile() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Redirected proc cmdline backing files should still look like procfs metadata",
                source.contains("isProcCmdlineProbePath")
                        && source.contains("isProcCmdlineFd")
                        && source.contains("sanitizeProcCmdlineStat"));
        assertTrue("stat/lstat/fstat must sanitize proc cmdline size/mode after IO redirection",
                source.contains("maybeSanitizeProcCmdlineStat(result, pathname, redirected, buf)")
                        && source.contains("maybeSanitizeProcCmdlineFdStat(result, fd, buf)"));
        assertTrue("native access/faccessat should deny write/execute checks on proc cmdline just like real procfs",
                source.contains("denyProcCmdlineAccessIfNeeded(pathname, redirected, mode)")
                        && source.contains("denyProcCmdlineAccessIfNeeded(resolved_log.path, redirected, static_cast<int>(args[2]))"));
    }

    @Test
    public void nativeFileHooksVirtualizeNativeUidAndProcStatusTogether() throws Exception {
        String nativeFileHook = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String boxCore = readSource(
                "src/main/cpp/BoxCore.cpp",
                "Bcore/src/main/cpp/BoxCore.cpp");

        assertTrue("Native file hook should receive the same virtual uid configured for Java/libcore identity",
                nativeFileHook.contains("setNativeFileVirtualUid")
                        && boxCore.contains("setNativeFileVirtualUid(virtualUid)"));
        assertTrue("Native getuid/euid/gid/egid should not expose the host BlackBox Linux uid",
                nativeFileHook.contains("extern \"C\" uid_t getuid(")
                        && nativeFileHook.contains("extern \"C\" uid_t geteuid(")
                        && nativeFileHook.contains("extern \"C\" gid_t getgid(")
                        && nativeFileHook.contains("extern \"C\" gid_t getegid(")
                        && nativeFileHook.contains("extern \"C\" int getgroups("));
        assertTrue("PLT patching should cover native identity functions in later-loaded protector libraries",
                nativeFileHook.contains("\"getuid\"")
                        && nativeFileHook.contains("\"geteuid\"")
                        && nativeFileHook.contains("\"getgid\"")
                        && nativeFileHook.contains("\"getegid\"")
                        && nativeFileHook.contains("\"getgroups\""));
        assertTrue("libc syscall bridge should cover raw getuid/euid/gid/egid identity probes",
                nativeFileHook.contains("case __NR_getuid:")
                        && nativeFileHook.contains("case __NR_geteuid:")
                        && nativeFileHook.contains("case __NR_getgid:")
                        && nativeFileHook.contains("case __NR_getegid:"));
        assertTrue("/proc/self/status reads should be backed by a synthesized procfs-looking fd with virtual uid/gid/groups",
                nativeFileHook.contains("isProcStatusProbePath")
                        && nativeFileHook.contains("openVirtualProcStatusFdForRead")
                        && nativeFileHook.contains("rewriteProcStatusIdentityLine")
                        && nativeFileHook.contains("maybeSanitizeProcStatusStat"));
    }

    @Test
    public void nativeFileHooksVirtualizeHostOwnedMetadataForRedirectedAppData() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Native metadata sanitization should remember the real host uid/gid before getuid/getgid are virtualized",
                source.contains("gNativeHostUid")
                        && source.contains("gNativeHostGid")
                        && source.contains("rawHostUid()")
                        && source.contains("rawHostGid()"));
        assertTrue("stat-family results owned by the host BlackBox uid/gid should be exposed as the virtual app uid/gid",
                source.contains("sanitizeVirtualOwnerStat")
                        && source.contains("buf->st_uid == static_cast<uid_t>(gNativeHostUid)")
                        && source.contains("buf->st_gid == static_cast<gid_t>(gNativeHostGid)")
                        && source.contains("buf->st_uid = virtualUid()")
                        && source.contains("buf->st_gid = virtualGid()"));
        assertTrue("path and fd metadata probes should both pass through the virtual-owner sanitizer",
                source.contains("maybeSanitizeVirtualOwnerStat(result, buf)")
                        && source.contains("maybeSanitizeVirtualOwnerFdStat(result, fd, buf)"));
    }

    @Test
    public void nativeFileHooksVirtualizeAtAndStatxMetadataOwners() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Native hook should export fstatat because dirfd-relative metadata probes bypass stat/lstat",
                source.contains("typedef int (*FstatatFn)(int dirfd, const char *pathname, struct stat *buf, int flags)")
                        && source.contains("extern \"C\" int fstatat(")
                        && source.contains("\"fstatat\""));
        assertTrue("Native hook should export statx for API 30+ extended metadata probes",
                source.contains("typedef int (*StatxFn)(int dirfd, const char *pathname, int flags, unsigned int mask, struct statx *buf)")
                        && source.contains("extern \"C\" int statx(")
                        && source.contains("\"statx\""));
        assertTrue("fstatat results must reuse existing proc and virtual owner metadata sanitizers",
                source.contains("maybeSanitizeProcCmdlineStat(result, resolved_log.path, redirected, buf)")
                        && source.contains("maybeSanitizeProcStatusStat(result, resolved_log.path, redirected, buf)")
                        && source.contains("maybeSanitizeVirtualOwnerStat(result, buf)"));
        assertTrue("statx results owned by the host uid/gid should be exposed as the virtual uid/gid",
                source.contains("sanitizeVirtualOwnerStatx")
                        && source.contains("buf->stx_uid == static_cast<__u32>(gNativeHostUid)")
                        && source.contains("buf->stx_gid == static_cast<__u32>(gNativeHostGid)")
                        && source.contains("buf->stx_uid = static_cast<__u32>(virtualUid())")
                        && source.contains("buf->stx_gid = static_cast<__u32>(virtualGid())"));
        assertTrue("Raw syscall bridge should sanitize fstatat64/newfstatat and statx metadata probes",
                source.contains("case __NR_fstatat64:")
                        && source.contains("case __NR_newfstatat:")
                        && source.contains("case __NR_statx:")
                        && source.contains("maybeSanitizeVirtualOwnerStatx(result, reinterpret_cast<struct statx *>(args[4]))"));
    }

    @Test
    public void nativeFileHooksVirtualizeProcCgroupAndSelinuxContextContent() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("/proc/self/cgroup content should not expose the host BlackBox Linux uid",
                source.contains("writeVirtualProcCgroupFile")
                        && source.contains("\"3:cpuacct:/uid_%d/pid_%d\\n\"")
                        && source.contains("virtualUid()")
                        && source.contains("getpid()"));
        assertTrue("/proc/self/attr/current content should derive SELinux app category from the virtual uid",
                source.contains("writeVirtualProcAttrCurrentFile")
                        && source.contains("\"u:r:untrusted_app:s0:c%d,c256,c512,c768\"")
                        && source.contains("selinuxCategoryForUid(virtualUid())"));
        assertTrue("/proc/self/attr/current virtualization should preserve procfs' raw NUL terminator when the real file provides it",
                source.contains("std::string raw(buffer, read_bytes)")
                        && source.contains("result.push_back('\\0')"));
        assertTrue("Read-only open/openat/fopen paths should route proc identity files through the synthesized fd helper",
                source.contains("openVirtualProcIdentityFdForRead")
                        && source.contains("isProcCgroupProbePath")
                        && source.contains("isProcAttrCurrentProbePath"));
    }

    @Test
    public void nativeFileHookInternalMapsReadsAreReentrancyGuarded() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String openMaps = sliceBetween(source,
                "FILE *openRealProcMapsFile()",
                "FILE *openRealProcStatusFile()");
        String logOpen = sliceBetween(source,
                "void logOpenPath(",
                "void logNativeTerminationProbe(");

        assertTrue("Internal /proc reads used for caller resolution must be marked so they do not recursively log themselves",
                source.contains("class ScopedInternalFileProbe")
                        && source.contains("isInternalFileProbe()"));
        assertTrue("openRealProcMapsFile should run under the internal-probe guard",
                openMaps.contains("ScopedInternalFileProbe internal_probe"));
        assertTrue("native file logging should suppress internal file-probe opens before resolving caller maps",
                logOpen.contains("isInternalFileProbe()")
                        && logOpen.indexOf("isInternalFileProbe()") < logOpen.indexOf("resolveCallerLocation"));
    }

    @Test
    public void nativeFileHooksRedirectDirectProcSelfProbesToPreparedShimFds() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        int redirectShim = source.indexOf("redirectProcProbeToShim");
        int redirectAbsolute = source.indexOf("const char *redirectAbsolutePath");
        int ioRedirect = source.indexOf("IO::redirectPath(pathname)", redirectAbsolute);

        assertTrue("Native file hook should have a direct proc probe shim redirect helper",
                redirectShim >= 0);
        assertTrue("Direct /proc/self/maps probes should be redirected to the maps shim fd when prepared",
                source.contains("\"/proc/self/maps\"")
                        && source.contains("kProcMapsFdPath")
                        && source.contains("isProcShimFdAvailable(kProcMapsFd)"));
        assertTrue("Direct /proc/<pid>/cmdline/comm and global proc probes should reuse the prepared shim fd set",
                source.contains("kProcCmdlineFdPath")
                        && source.contains("kProcCommFdPath")
                        && source.contains("kProcMeminfoFdPath")
                        && source.contains("kProcVersionFdPath"));
        assertTrue("The proc shim redirect must run before ordinary IO redirection for open/fopen/openat",
                redirectAbsolute > redirectShim && ioRedirect > redirectAbsolute
                        && source.indexOf("redirectProcProbeToShim(pathname)", redirectAbsolute) < ioRedirect);
    }

    @Test
    public void nativeFileHooksSeedEarlyProcMapsShimBeforeProtectedNativeLoad() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        int configurePackage = source.indexOf("setNativeTerminationShieldPackage");
        int prepareEarly = source.indexOf("prepareEarlyProcMapsShim(package_name)", configurePackage);
        int redirectMaps = source.indexOf("isCurrentProcessProcPath(pathname, \"maps\")");

        assertTrue("Package-scoped native setup should seed a proc maps shim before protected Runtime.nativeLoad runs",
                configurePackage >= 0 && prepareEarly > configurePackage);
        assertTrue("Early maps shim should keep package context without relying on protected-library path discovery",
                source.contains("gEarlyProcMapsPackage")
                        && source.contains("writeEarlyProcMapsFile"));
        assertTrue("Early maps writer should read the real proc maps through RTLD_NEXT to avoid file-hook recursion",
                source.contains("dlsym(RTLD_NEXT, \"fopen\")")
                        && source.contains("openRealProcMapsFile"));
        assertTrue("Early maps sanitizer should hide sandbox runtime and hook mappings before fd93 is prepared by RuntimeHook",
                source.contains("shouldHideEarlyMapsLine")
                        && source.contains("libblackbox.so")
                        && source.contains("libpine.so")
                        && source.contains("[anon:pine codes]"));
        assertTrue("Direct /proc/self/maps redirects should use the early snapshot without rewriting it on every read, then refresh only after the protected shim takes over",
                redirectMaps >= 0
                        && source.contains("refreshProcMapsShimForRedirect")
                        && source.contains("if (isProtectedProcMapsShimReady())")
                        && source.contains("refreshProtectedProcMapsShim();"));
    }

    @Test
    public void procMapsShimIsDiagnosticOptInByDefault() throws Exception {
        String nativeFileHook = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String runtimeHook = readSource(
                "src/main/cpp/Hook/RuntimeHook.cpp",
                "Bcore/src/main/cpp/Hook/RuntimeHook.cpp");
        String packageSetup = sliceBetween(nativeFileHook,
                "extern \"C\" void setNativeTerminationShieldPackage",
                "extern \"C\" void disableEarlyProcMapsShim");
        String protectedSetup = sliceBetween(runtimeHook,
                "void prepareProtectedProcShims",
                "extern \"C\" void refreshProtectedProcMapsShim");

        assertTrue("Proc maps/cmdline/version shims should be controlled by an explicit Android debug property",
                nativeFileHook.contains("debug.blackbox.proc_shim")
                        && runtimeHook.contains("debug.blackbox.proc_shim")
                        && nativeFileHook.contains("native_property::getBool(kProcShimProperty)")
                        && runtimeHook.contains("native_property::getBool(kProcShimProperty)"));
        assertTrue("Early proc maps shim should be opt-in so normal runs do not expose fd93 maps contents to protected code",
                packageSetup.contains("if (isProcShimEnabled())")
                        && packageSetup.contains("prepareEarlyProcMapsShim(package_name)")
                        && packageSetup.contains("resetEarlyProcMapsShim()"));
        assertTrue("Runtime.nativeLoad protected proc string patching should also be opt-in",
                protectedSetup.contains("if (!isProcShimEnabled())")
                        && protectedSetup.contains("return;"));
    }

    @Test
    public void transientProcMapsVirtualizationIsDiagnosticOptIn() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        int transientHelper = source.indexOf("openTransientProcMapsFdForRead");
        int transientToggle = source.indexOf("isTransientProcMapsEnabled");
        int transientDecision = source.indexOf("bool shouldUseTransientProcMaps");
        int fopenWrapper = source.indexOf("extern \"C\" FILE *fopen(");
        int fopenTransient = source.indexOf("openTransientProcMapsFdForRead(pathname, __builtin_return_address(0))", fopenWrapper);
        int fopenShim = source.indexOf("openProcShimFdForRead(pathname)", fopenWrapper);
        int syscallOpenAt = source.indexOf("case __NR_openat:");
        int syscallTransient = source.indexOf("openTransientProcMapsFdForRead(resolved_log.path, __builtin_return_address(0))", syscallOpenAt);
        int syscallShim = source.indexOf("openProcShimFdForRead(resolved_log.path)", syscallOpenAt);

        assertTrue("Transient proc maps virtualization should be controlled by an explicit Android debug property",
                source.contains("debug.blackbox.transient_maps")
                        && transientToggle >= 0
                        && source.contains("native_property::getBool(kTransientProcMapsProperty)"));
        assertTrue("Transient proc maps should stay diagnostic opt-in rather than replacing real /proc/self/maps by default",
                transientHelper >= 0
                        && source.contains("shouldUseTransientProcMaps")
                        && transientDecision > transientToggle
                        && source.indexOf("isTransientProcMapsEnabled()", transientDecision) > transientDecision
                        && source.contains("!isProcShimEnabled()")
                        && source.contains("isNativeTerminationShieldEnabled()"));
        assertTrue("When explicitly enabled, fopen(/proc/self/maps) should prefer the transient sanitized maps fd before falling back to fd93 or real procfs",
                fopenWrapper >= 0 && fopenTransient > fopenWrapper && fopenShim > fopenTransient);
        assertTrue("When explicitly enabled, raw syscall openat maps probes should get the same transient sanitized fd before the fd93 path",
                syscallOpenAt >= 0 && syscallTransient > syscallOpenAt && syscallShim > syscallTransient);
    }

    @Test
    public void procMapsSandboxRuntimeHidingIsCallerScopedToAppOwnedNativeCode() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Default proc maps virtualization should receive the native caller so sandbox hiding is not applied to framework/runtime readers",
                source.contains("openTransientProcMapsFdForRead(const char *pathname, void *caller)")
                        && source.contains("openTransientProcMapsFdForRead(pathname, __builtin_return_address(0))")
                        && source.contains("openTransientProcMapsFdForRead(resolved_log.path, __builtin_return_address(0))"));
        assertTrue("Sandbox-runtime hiding should be limited to app-owned native callers rather than every /proc/self/maps reader",
                source.contains("shouldUseAppVisibleProcMapsForCaller")
                        && source.contains("isAppOwnedNativeCallerPath")
                        && source.contains("resolveCallerLocation(caller, &caller_location)"));
        assertTrue("Caller classification should include protected app native paths but exclude Android framework/runtime and BlackBox hook libraries",
                source.contains("\"/blackbox/data/user/\"")
                        && source.contains("\"/data/app/\"")
                        && source.contains("\"/data/user/\"")
                        && source.contains("\"/apex/\"")
                        && source.contains("\"/system/\"")
                        && source.contains("\"libblackbox.so\"")
                        && source.contains("\"libpine.so\"")
                        && source.contains("gNativeTerminationShieldPackage"));
    }

    @Test
    public void appVisibleProcMapsPreserveRealRangesWhileHidingSandboxRuntimeMappings() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String writer = sliceBetween(source,
                "bool writeAppVisibleProcMapsFile",
                "int createAnonymousProcFd");

        assertTrue("App-visible maps writer should still read real /proc/self/maps and never synthesize address ranges",
                writer.contains("FILE *maps = openRealProcMapsFile()")
                        && writer.contains("fgets(line, sizeof(line), maps)")
                        && !writer.contains("writeEarlyProcMapsFallback")
                        && !writer.contains("12c00000-12c01000"));
        assertTrue("App-visible maps should hide sandbox runtime/hook mappings but keep protected app mappings after path sanitization",
                writer.contains("shouldHideAppVisibleMapsLine(line)")
                        && writer.contains("sanitizeEarlyMapsLine(line)")
                        && writer.contains("writeExact(fd, sanitized.data(), sanitized.size())"));
        assertTrue("Default open path should select the stronger app-visible maps only for app-owned native callers; otherwise keep the public path-only maps view",
                source.contains("const bool app_visible = shouldUseAppVisibleProcMapsForCaller(caller)")
                        && source.contains("app_visible ? \"bb_proc_maps_app\" : \"bb_proc_maps_public\"")
                        && source.contains("app_visible ? writeAppVisibleProcMapsFile(fd) : writeProcMapsPathOnlyFile(fd)"));
    }

    @Test
    public void procMapsPathSanitizationKeepsRealRangesAndOnlyRewritesSandboxAppDataRootsByDefault() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String pathOnlySanitizer = sliceBetween(source,
                "std::string sanitizeProcMapsPathOnlyLine",
                "std::string sanitizeEarlyMapsLine");
        String pathOnlyWriter = sliceBetween(source,
                "bool writeProcMapsPathOnlyFile",
                "bool writeEarlyProcMapsFile");

        assertTrue("Default maps view should have an explicit opt-out property instead of requiring a diagnostic opt-in",
                source.contains("debug.blackbox.maps_path_sanitize")
                        && source.contains("isProcMapsPathSanitizationEnabled"));
        assertTrue("Path-only maps sanitizer should preserve real address ranges and only reverse-map sandbox app-data roots",
                pathOnlySanitizer.contains("replaceBlackBoxDataUserRoots(&sanitized)")
                        && pathOnlySanitizer.contains("\"/blackbox/data/user/\"")
                        && !pathOnlySanitizer.contains("kBlackBoxHostPackagePrefix")
                        && !pathOnlySanitizer.contains("shouldHideEarlyMapsLine"));
        assertTrue("The default maps writer should emit every real maps line after path-only sanitization",
                pathOnlyWriter.contains("FILE *maps = openRealProcMapsFile()")
                        && pathOnlyWriter.contains("std::string sanitized = sanitizeProcMapsPathOnlyLine(line)")
                        && pathOnlyWriter.contains("writeExact(fd, sanitized.data(), sanitized.size())"));
        assertFalse("Path-only maps writer must not drop hook/runtime mappings; that stronger hiding remains diagnostic-only",
                pathOnlyWriter.contains("shouldHideEarlyRawMapsLine")
                        || pathOnlyWriter.contains("shouldHideEarlyMapsLine"));
    }

    @Test
    public void virtualProcMapsMemfdsLookLikeProcMapsToFdInspection() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Default path-only maps fds should use a recognizable memfd name for generic fd metadata sanitization",
                source.contains("bb_proc_maps_public"));
        assertTrue("readlink(/proc/self/fd/N) for a virtual maps memfd should report /proc/self/maps, not a memfd backing name",
                source.contains("isVirtualProcMapsFdTarget")
                        && source.contains("virtualProcMapsReadlinkTarget")
                        && source.contains("return \"/proc/self/maps\""));
        assertTrue("fstat on a virtual maps memfd should look like procfs metadata instead of a sized memfd",
                source.contains("isVirtualProcMapsFd")
                        && source.contains("maybeSanitizeProcMapsFdStat")
                        && source.contains("sanitizeProcMapsStat")
                        && source.contains("st_size = 0"));
    }

    @Test
    public void nativeFileHooksKeepVirtualAppDataMappingsDuringMapsSanitization() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        int sanitizer = source.indexOf("std::string sanitizeEarlyMapsLine");
        int virtualRootRewrite = source.indexOf("replaceBlackBoxDataUserRoots(&sanitized)", sanitizer);
        int hostRewrite = source.indexOf("replaceAll(&sanitized, kBlackBoxHostPackagePrefix", sanitizer);
        int writer = source.indexOf("bool writeEarlyProcMapsFile");
        int rawFilter = source.indexOf("shouldHideEarlyRawMapsLine(line)", writer);

        assertTrue("Maps sanitizer should rewrite BlackBox virtual data roots to public app data roots before package-name replacement",
                source.contains("replaceBlackBoxDataUserRoots")
                        && virtualRootRewrite > sanitizer
                        && hostRewrite > virtualRootRewrite);
        assertTrue("Raw maps filtering must keep /blackbox/data/user mappings so protected libraries remain visible after sanitization",
                source.contains("shouldHideEarlyRawMapsLine")
                        && source.contains("\"/blackbox/data/user/\"")
                        && rawFilter > writer);
    }

    @Test
    public void nativeProcMapsSanitizerNeverSynthesizesUnbackedAddressRanges() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertFalse("Early/transient maps virtualization must not invent placeholder address ranges",
                source.contains("writeEarlyProcMapsFallback")
                        || source.contains("12c00000-12c01000")
                        || source.contains("libprotected.so"));
        assertTrue("Early/transient maps virtualization should only emit real maps lines after path sanitization",
                source.contains("FILE *maps = openRealProcMapsFile()")
                        && source.contains("std::string sanitized = sanitizeEarlyMapsLine(line)")
                        && source.contains("writeExact(fd, sanitized.data(), sanitized.size())"));
        assertFalse("If real /proc/self/maps cannot be read, the proc maps shim should fail closed instead of fabricating entries",
                source.contains("if (maps == nullptr) {\n        return writeEarlyProcMapsFallback(fd);\n    }"));
    }

    @Test
    public void nativeFileHooksReturnProcShimDescriptorsWithoutReopeningDevFd() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        int openHelper = source.indexOf("openProcShimFdForRead");
        int fopenWrapper = source.indexOf("extern \"C\" FILE *fopen(");
        int openWrapper = source.indexOf("extern \"C\" int open(");
        int syscallOpenAt = source.indexOf("case __NR_openat:");

        assertTrue("Proc shim reads should duplicate the prepared fd directly instead of reopening /dev/fd/N",
                openHelper >= 0
                        && source.contains("dup(shim_fd)")
                        && source.contains("lseek(shim_fd, 0, SEEK_SET)"));
        assertTrue("fopen(/proc/self/maps) should return fdopen(dup(fd93)) so Android /dev/fd restrictions cannot break maps reads",
                fopenWrapper >= 0
                        && source.indexOf("fdopen", fopenWrapper) > fopenWrapper
                        && source.indexOf("openProcShimFdForRead(pathname)", fopenWrapper) > fopenWrapper);
        assertTrue("open(/proc/self/maps) should return the duplicated proc-shim descriptor before calling libc open",
                openWrapper >= 0
                        && source.indexOf("openProcShimFdForRead(pathname)", openWrapper) > openWrapper
                        && source.indexOf("callOpen(resolveSymbol(&gOrigOpen", openWrapper)
                        > source.indexOf("openProcShimFdForRead(pathname)", openWrapper));
        assertTrue("raw syscall openat probes should also return duplicated proc-shim descriptors before delegating to libc syscall",
                syscallOpenAt >= 0
                        && source.indexOf("openProcShimFdForRead(resolved_log.path)", syscallOpenAt) > syscallOpenAt
                        && source.indexOf("callSyscall(fn, number, args)", syscallOpenAt)
                        > source.indexOf("openProcShimFdForRead(resolved_log.path)", syscallOpenAt));
    }

    @Test
    public void earlyProcMapsShimIsBoundedToApplicationConstruction() throws Exception {
        String nativeFileHook = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String runtimeHook = readSource(
                "src/main/cpp/Hook/RuntimeHook.cpp",
                "Bcore/src/main/cpp/Hook/RuntimeHook.cpp");
        String nativeCore = readSource(
                "src/main/java/top/niunaijun/blackbox/core/NativeCore.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java");
        String boxCore = readSource(
                "src/main/cpp/BoxCore.cpp",
                "Bcore/src/main/cpp/BoxCore.cpp");
        String activityThread = readSource(
                "src/main/java/top/niunaijun/blackbox/app/BActivityThread.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java");

        assertTrue("RuntimeHook should expose whether the library-specific protected proc shim has taken over fd93",
                runtimeHook.contains("extern \"C\" bool isProtectedProcMapsShimReady()")
                        && runtimeHook.contains("return gProcShimContextReady"));
        assertTrue("NativeFileHook should redirect maps only while early shim is active or the protected shim is ready",
                nativeFileHook.contains("isProcMapsShimReadyForRedirect")
                        && nativeFileHook.contains("gEarlyProcMapsReady || isProtectedProcMapsShimReady()"));
        assertTrue("NativeFileHook should expose a lifecycle boundary to disable the early maps shim",
                nativeFileHook.contains("extern \"C\" void disableEarlyProcMapsShim()")
                        && nativeFileHook.contains("if (!isProtectedProcMapsShimReady())")
                        && nativeFileHook.contains("close(kProcMapsFd)"));
        assertTrue("NativeCore should expose and register the early maps disable native bridge",
                nativeCore.contains("native void disableEarlyProcMapsShim()")
                        && boxCore.contains("{\"disableEarlyProcMapsShim\",")
                        && boxCore.contains("disableEarlyProcMapsShim()"));

        int makeApplication = activityThread.indexOf("BRLoadedApk.getWithException(loadedApk).makeApplication(false, null)");
        int disableEarlyMaps = activityThread.indexOf("NativeCore.disableEarlyProcMapsShim()", makeApplication);
        int appOnCreate = activityThread.indexOf("AppInstrumentation.get().callApplicationOnCreate(application)", makeApplication);
        assertTrue("BActivityThread should keep early maps active through makeApplication but disable it before Application.onCreate",
                makeApplication >= 0 && disableEarlyMaps > makeApplication && appOnCreate > disableEarlyMaps);
    }

    @Test
    public void nativeFileHooksUseProcShimForAbsoluteOpenAtPaths() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        int redirectOpenAt = source.indexOf("const char *redirectOpenAtPath");
        int absoluteBranch = source.indexOf("if (pathname[0] == '/')", redirectOpenAt);
        int relativeResolution = source.indexOf("char dir_path[PATH_MAX]", redirectOpenAt);
        int absoluteRedirect = source.indexOf("return redirectAbsolutePath(pathname);", absoluteBranch);

        assertTrue("redirectOpenAtPath should exist",
                redirectOpenAt >= 0);
        assertTrue("openat absolute-path branch should be detected before relative dirfd handling",
                absoluteBranch > redirectOpenAt && relativeResolution > absoluteBranch);
        assertTrue("openat(AT_FDCWD, \"/proc/self/maps\", ...) must pass through redirectAbsolutePath so proc shim fds are used",
                absoluteRedirect > absoluteBranch && absoluteRedirect < relativeResolution);
    }

    @Test
    public void nativeFileHooksExposeProcDirectoryEnumerationDiagnostics() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Native hook should export opendir because packed protectors enumerate /proc directly",
                source.contains("extern \"C\" DIR *opendir("));
        assertTrue("openat diagnostics should preserve the resolved /proc path even for relative dirfd probes",
                source.contains("ResolvedPath")
                        && source.contains("resolveOpenAtPathForLog")
                        && source.contains("native file probe"));
        assertTrue("syscall(openat) diagnostics should use the same resolved /proc path evidence",
                source.contains("logOpenPath(\"syscall.openat\"")
                        && source.contains("resolved_log.path"));
        assertTrue("opendir diagnostics should stay focused on /proc, APK, and app-data probes",
                source.contains("native dir probe")
                        && source.contains("logDirPath")
                        && source.contains("\"/proc/\""));
    }

    @Test
    public void unixFileSystemHookRedirectsBooleanAttributesUsedByMkdirs() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/UnixFileSystemHook.cpp",
                "Bcore/src/main/cpp/Hook/UnixFileSystemHook.cpp");

        assertTrue("Android 11 File.exists()/isDirectory() use getBooleanAttributes0(String), so it must redirect public app data paths",
                source.contains("HOOK_JNI(jint, getBooleanAttributes0")
                        && source.contains("IO::redirectPath(env, abspath)"));
        assertTrue("UnixFileSystemHook should install the getBooleanAttributes0(String) hook through the method lookup wrapper",
                source.contains("Hook(env, clazz, \"getBooleanAttributes0\", \"(Ljava/lang/String;)I\""));
        assertTrue("UnixFileSystemHook should preserve the original getBooleanAttributes0 entrypoint",
                source.contains("(void *) new_getBooleanAttributes0")
                        && source.contains("(void **) (&orig_getBooleanAttributes0)"));
    }

    @Test
    public void unixFileSystemHookRestoresCanonicalizeReturnToVirtualPath() throws Exception {
        String unixHook = readSource(
                "src/main/cpp/Hook/UnixFileSystemHook.cpp",
                "Bcore/src/main/cpp/Hook/UnixFileSystemHook.cpp");
        String ioHeader = readSource(
                "src/main/cpp/IO.h",
                "Bcore/src/main/cpp/IO.h");
        String ioSource = readSource(
                "src/main/cpp/IO.cpp",
                "Bcore/src/main/cpp/IO.cpp");

        assertTrue("canonicalize0 should reverse-map the real redirected result before returning to app code",
                unixHook.contains("orig_canonicalize0(env, obj, redirect)")
                        && unixHook.contains("IO::reverseRedirectPath(env, canonical)"));
        assertTrue("Android 15 canonicalize0 variant should use the same reverse mapping",
                unixHook.contains("orig_canonicalize0_v35(env, obj, redirect, isAtLeastTargetSdk35)")
                        && unixHook.contains("IO::reverseRedirectPath(env, canonical)"));
        assertTrue("IO should expose a reverse redirect helper for canonical path sanitization",
                ioHeader.contains("reverseRedirectPath(JNIEnv *env, jstring path)")
                        && ioHeader.contains("reverseRedirectPath(const char *__path)"));
        assertTrue("reverse redirect should replace relocatePath back to targetPath",
                ioSource.contains("reverseRedirectPathWithAlias(__path, info.relocatePath, info.targetPath)")
                        && ioSource.contains("replace(__path, relocatePath, targetPath)"));
        assertTrue("reverse redirect should tolerate Android canonical /data/user/0 -> /data/data aliasing",
                ioSource.contains("\"/data/user/0/\"")
                        && ioSource.contains("\"/data/data/\"")
                        && ioSource.contains("reverseRedirectPathWithAlias"));
        assertTrue("IO::replace must initialize the allocated output by allocation size, not strlen(uninitialized malloc memory)",
                ioSource.contains("memset(result, 0, result_len)"));
        assertFalse("IO::replace must not call strlen(result) before result is initialized",
                ioSource.contains("memset(result, 0, strlen(result))"));
    }

    @Test
    public void nativeFileHooksSanitizeDladdrLibraryPathsForPackedProtectors() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Native hook should export dladdr for later-loaded hardened libraries",
                source.contains("extern \"C\" int dladdr("));
        assertTrue("dladdr hook should recognize BlackBox virtual data paths",
                source.contains("\"/blackbox/data/user/\""));
        assertTrue("dladdr hook should rewrite library paths back to app-owned data paths",
                source.contains("\"/data/data/\""));
        assertTrue("dladdr hook should keep a stable per-thread sanitized path buffer",
                source.contains("gSanitizedDladdrPath"));
        assertTrue("dladdr hook should log focused path-sanitization evidence",
                source.contains("native dladdr probe"));
    }

    @Test
    public void nativeFileHooksBlockSandboxNativeTerminationPaths() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Native hook should receive the current virtual package from Java",
                source.contains("setNativeTerminationShieldPackage"));
        assertTrue("Native hook should enable termination shielding for the current sandbox package",
                source.contains("isNativeTerminationShieldEnabled")
                        && source.contains("gNativeTerminationShieldPackage[0] != '\\0'"));
        assertTrue("Native hook should export bionic signal prototypes checked against AOSP",
                source.contains("extern \"C\" int kill(")
                        && source.contains("extern \"C\" int tgkill(")
                        && source.contains("extern \"C\" int raise("));
        assertTrue("Native hook should export bionic exit prototypes checked against AOSP",
                source.contains("extern \"C\" void abort(")
                        && source.contains("extern \"C\" void exit(")
                        && source.contains("extern \"C\" void _exit(")
                        && source.contains("extern \"C\" void _Exit("));
        assertTrue("Native hook should suppress self-targeted native termination signals",
                source.contains("shouldBlockNativeSignal")
                        && source.contains("getpid()")
                        && source.contains("isTerminationSignal"));
        assertTrue("Raw syscall hook should cover direct kill and process-exit syscalls",
                source.contains("case __NR_kill:")
                        && source.contains("case __NR_tgkill:")
                        && source.contains("case __NR_exit:")
                        && source.contains("case __NR_exit_group:"));
        assertTrue("Native termination suppression should be visible in logcat",
                source.contains("native termination shield blocked"));
        assertTrue("Native termination suppression should log the original caller address for IDA correlation",
                source.contains("caller=%p")
                        && source.contains("__builtin_return_address(0)")
                        && source.contains("logNativeTerminationBlocked(\"kill\", pid, signal, 0, __builtin_return_address(0))")
                        && source.contains("logNativeTerminationBlocked(\"syscall.tgkill\", args[1], static_cast<int>(args[2]), 0, __builtin_return_address(0))"));
    }

    @Test
    public void nativeFileHooksDiagnoseTerminationCallsWithoutBlocking() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Native termination diagnostics should be explicit opt-in and separate from blocking shields",
                source.contains("debug.blackbox.termination_probe")
                        && source.contains("isTerminationProbeEnabled"));
        assertTrue("Termination probe should log the caller map/offset and a short native stack for IDA correlation",
                source.contains("logNativeTerminationProbe")
                        && source.contains("native termination probe")
                        && source.contains("native termination frame")
                        && source.contains("resolveCallerLocation")
                        && source.contains("_Unwind_Backtrace"));
        assertTrue("libc kill/tgkill/exit wrappers should emit non-blocking probe telemetry before delegating",
                source.contains("logNativeTerminationProbe(\"kill\", pid, signal, 0, __builtin_return_address(0), currentStackPointer());")
                        && source.contains("logNativeTerminationProbe(\"tgkill\", tid, signal, 0, __builtin_return_address(0), currentStackPointer());")
                        && source.contains("logNativeTerminationProbe(\"exit\", getpid(), 0, status, __builtin_return_address(0), currentStackPointer());")
                        && source.contains("logNativeTerminationProbe(\"_exit\", getpid(), 0, status, __builtin_return_address(0), currentStackPointer());"));
        assertTrue("raw libc syscall termination paths should also emit non-blocking probe telemetry",
                source.contains("logNativeTerminationProbe(\"syscall.kill\", args[0], static_cast<int>(args[1]), 0, __builtin_return_address(0), currentStackPointer());")
                        && source.contains("logNativeTerminationProbe(\"syscall.tgkill\", args[1], static_cast<int>(args[2]), 0, __builtin_return_address(0), currentStackPointer());")
                        && source.contains("logNativeTerminationProbe(\"syscall.exit_group\", getpid(), 0, static_cast<int>(args[0]), __builtin_return_address(0), currentStackPointer());"));
    }

    @Test
    public void nativeTerminationBlockingDumpsRecentFileProbeRingForRootCauseCorrelation() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Blocked native self-termination should emit a bounded recent native file-operation ring when file probing is enabled, so the failing IO predicate can be correlated with the raise/exit caller",
                source.contains("struct RecentNativeFileProbe")
                        && source.contains("kRecentNativeFileProbeCount")
                        && source.contains("rememberRecentNativeFileProbe")
                        && source.contains("dumpRecentNativeFileProbesForTermination")
                        && source.contains("native termination recent file")
                        && source.contains("eventTid=%d")
                        && source.contains("callerOff=0x%lx")
                        && source.contains("callerMap=%s"));
        assertTrue("The recent-file ring must be fed by the normal caller-aware file probe logger, not by target-package special cases",
                source.contains("rememberRecentNativeFileProbe(api, pathname, redirected, flags, result, result_errno, caller, &caller_location)")
                        && source.contains("dumpRecentNativeFileProbesForTermination(api, rawThreadId())")
                        && !source.contains("com.bestv.tv.video.iqy.tjdx"));
    }

    @Test
    public void nativeTerminationDiagnosticsCanDumpRuntimeCallerMappingForIda() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Runtime code dumps must be an explicit diagnostic because they perform file IO from the termination path",
                source.contains("debug.blackbox.termination_memdump")
                        && source.contains("isTerminationMemoryDumpEnabled"));
        assertTrue("The dumper should locate the real maps entry containing the caller instead of assuming file offsets from dladdr",
                source.contains("resolveMemoryMapEntry(caller, &entry)")
                        && source.contains("entry.start")
                        && source.contains("entry.end")
                        && source.contains("entry.offset")
                        && source.contains("entry.perms"));
        assertTrue("The dump should preserve metadata needed to rebase the blob in IDA",
                source.contains("native termination memdump meta")
                        && source.contains("callerOff=0x%lx")
                        && source.contains("mapStart=0x%lx")
                        && source.contains("mapEnd=0x%lx")
                        && source.contains("mapOffset=0x%lx"));
        assertTrue("The dump should write bytes from the caller mapping into the virtual package files directory",
                source.contains("dumpTerminationCallerMemory")
                        && source.contains("\"/data/user/0/%s/files/native_probe\"")
                        && source.contains("writeExact(fd, reinterpret_cast<const void *>(entry.start), dump_size)"));
        assertTrue("Termination probe logging should invoke the dumper only after caller location is resolved",
                source.contains("dumpTerminationCallerMemory(api, caller, &caller_location);"));
    }

    @Test
    public void nativeTerminationMemoryDumpIoDoesNotPolluteRecentFileProbeRing() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String callerDump = sliceBetween(source,
                "void dumpTerminationCallerMemory(",
                "void dumpTerminationStackMemory(");
        String stackDump = sliceBetween(source,
                "void dumpTerminationStackMemory(",
                "void logOpenPath(");

        assertTrue("Caller-memory dump file IO should be marked internal so the recent-file ring still points at the app IO predicate that happened before termination",
                callerDump.contains("ScopedInternalFileProbe internal_probe;")
                        && callerDump.indexOf("ScopedInternalFileProbe internal_probe;") < callerDump.indexOf("mkdir(dir_path, 0700);"));
        assertTrue("Stack dump file IO should also be internal and must not overwrite recent app-owned file events before logNativeTerminationBlocked dumps the ring",
                stackDump.contains("ScopedInternalFileProbe internal_probe;")
                        && stackDump.indexOf("ScopedInternalFileProbe internal_probe;") < stackDump.indexOf("mkdir(dir_path, 0700);"));
    }

    @Test
    public void nativeTerminationDiagnosticsCanDumpCallerStackWindowForIda() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Termination memdump should also capture a bounded stack window so libffi/pthread anti-debug branches can be correlated with stack strings",
                source.contains("dumpTerminationStackMemory")
                        && source.contains("kTerminationStackDumpMaxBytes")
                        && source.contains("term_%d_%s_stack_0x%lx-0x%lx.bin"));
        assertTrue("Stack dumps must resolve and clamp the current stack mapping instead of reading unbounded memory",
                source.contains("resolveMemoryMapEntry(reinterpret_cast<void *>(stack_pointer), &entry)")
                        && source.contains("entry.start")
                        && source.contains("entry.end")
                        && source.contains("dump_start")
                        && source.contains("dump_end"));
        assertTrue("Signal/exit entrypoints should pass their entry stack pointer into the termination probe before hook frames hide caller-local evidence",
                source.contains("currentStackPointer()")
                        && source.contains("logNativeTerminationProbe(\"raise\", getpid(), signal, 0, __builtin_return_address(0), currentStackPointer());")
                        && source.contains("dumpTerminationStackMemory(api, stack_pointer);"));
    }

    @Test
    public void nativeTerminationDiagnosticsDumpAdjacentRuntimeDataMapsForIda() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Runtime-unpacked protector code can keep the decisive strings/globals in adjacent readable maps, so termination memdump should capture a bounded set of nearby readable maps for IDA correlation",
                source.contains("kTerminationAdjacentDumpMaxMaps")
                        && source.contains("kTerminationAdjacentDumpMaxBytes")
                        && source.contains("kTerminationAdjacentDumpMaxDistance")
                        && source.contains("dumpTerminationAdjacentReadableMaps")
                        && source.contains("native termination adjacent memdump meta"));
        assertTrue("Adjacent-map dumping must be driven by the real maps entry containing the termination caller and must not synthesize address ranges",
                source.contains("isAdjacentReadableMapCandidate")
                        && source.contains("openRealProcMapsFile()")
                        && source.contains("parseMemoryMapLine")
                        && source.contains("candidate.start")
                        && source.contains("candidate.end"));
        assertTrue("Adjacent-map dump IO must be marked internal so diagnostic files do not overwrite the recent app-owned file probe ring",
                source.contains("ScopedInternalFileProbe internal_probe;")
                        && source.contains("dumpTerminationAdjacentReadableMaps(api, caller, &caller_location);"));
        assertFalse("Adjacent runtime-data dumping must remain package-agnostic and not key on the protected sample name",
                source.contains("com.bestv.tv.video.iqy.tjdx")
                        || source.contains("libjiagu"));
    }

    @Test
    public void directLibcOpenEntrypointsVirtualizeProcMapsForLibffiCallers() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String directOpenSpecs = sliceBetween(source,
                "DirectOpenHookSpec specs[] = {",
                "int patched = 0;");

        assertTrue("Protected loaders can call bionic open/openat symbols directly through libffi, so maps virtualization must not rely only on PLT patching",
                source.contains("installDirectLibcProcMapsHooks")
                        && source.contains("blackbox_direct_openat")
                        && source.contains("blackbox_direct_open"));
        assertTrue("The direct hook should cover bionic's private __openat entrypoint because protected code resolves that symbol explicitly",
                source.contains("\"__openat\"")
                        && source.contains("\"openat\"")
                        && source.contains("\"open\""));
        assertTrue("Direct openat handling must route /proc/self/maps through the same caller-aware app-visible maps writer",
                source.contains("openTransientProcMapsFdForRead(resolved_log.path, __builtin_return_address(0))")
                        && source.contains("native direct proc maps open"));
        assertTrue("Non-proc direct open/openat calls should delegate with a private raw kernel syscall to avoid recursion through patched libc",
                source.contains("rawDirectOpenAt")
                        && source.contains("callKernelSyscall(__NR_openat")
                        && source.contains("use_absolute ? AT_FDCWD : dirfd"));
        assertTrue("Do not direct-hook bionic syscall; previous diagnostics showed that corrupts the special syscall stub",
                !directOpenSpecs.contains("{\"syscall\""));
    }

    @Test
    public void directLibcOpenEntrypointsDoNotReenterDuringInternalMapsReads() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String directOpen = sliceBetween(source,
                "extern \"C\" int blackbox_direct_open(",
                "extern \"C\" int blackbox_direct_open_2(");
        String directOpen2 = sliceBetween(source,
                "extern \"C\" int blackbox_direct_open_2(",
                "extern \"C\" int blackbox_direct_openat(");
        String directOpenAt = sliceBetween(source,
                "extern \"C\" int blackbox_direct_openat(",
                "extern \"C\" int blackbox_direct_openat_2(");
        String directOpenAt2 = sliceBetween(source,
                "extern \"C\" int blackbox_direct_openat_2(",
                "extern \"C\" int open(");

        assertTrue("Direct libc open replacement is also reached by libc fopen while BlackBox reads real /proc/self/maps; it must bypass redirection/logging in that internal path",
                directOpen.contains("if (isInternalFileProbe())")
                        && directOpen.indexOf("if (isInternalFileProbe())") < directOpen.indexOf("redirectAbsolutePath(pathname)")
                        && directOpen.contains("return rawDirectOpenAt(AT_FDCWD, pathname, flags, mode);"));
        assertTrue("Direct libc __open_2 replacement needs the same internal guard without reading varargs",
                directOpen2.contains("if (isInternalFileProbe())")
                        && directOpen2.indexOf("if (isInternalFileProbe())") < directOpen2.indexOf("redirectAbsolutePath(pathname)")
                        && directOpen2.contains("return rawDirectOpenAt(AT_FDCWD, pathname, flags, 0);"));
        assertTrue("Direct libc openat replacement must not recurse when internal diagnostics resolve caller maps",
                directOpenAt.contains("if (isInternalFileProbe())")
                        && directOpenAt.indexOf("if (isInternalFileProbe())") < directOpenAt.indexOf("resolveOpenAtPathForLog(dirfd, pathname)")
                        && directOpenAt.contains("return rawDirectOpenAt(dirfd, pathname, flags, mode);"));
        assertTrue("Direct libc __openat_2 replacement needs the same internal guard",
                directOpenAt2.contains("if (isInternalFileProbe())")
                        && directOpenAt2.indexOf("if (isInternalFileProbe())") < directOpenAt2.indexOf("resolveOpenAtPathForLog(dirfd, pathname)")
                        && directOpenAt2.contains("return rawDirectOpenAt(dirfd, pathname, flags, 0);"));
    }

    @Test
    public void directLibcProcMapsVirtualizationRecognizesLibffiBackedAppNativeFrames() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String policy = sliceBetween(source,
                "bool shouldVirtualizeDirectProcMapsOpen(",
                "extern \"C\" int blackbox_direct_open(");
        String appVisiblePolicy = sliceBetween(source,
                "bool shouldUseAppVisibleProcMapsForCaller(",
                "void replaceBlackBoxDataUserRoots(");
        String directOpen = sliceBetween(source,
                "extern \"C\" int blackbox_direct_open(",
                "extern \"C\" int blackbox_direct_open_2(");
        String directOpenAt = sliceBetween(source,
                "extern \"C\" int blackbox_direct_openat(",
                "extern \"C\" int blackbox_direct_openat_2(");

        assertTrue("Direct libc maps hooks should still prefer the immediate caller for normal app-owned native scans, but must also recognize app-owned frames hidden behind libffi/bionic trampoline callers",
                policy.contains("isProcessProbeEnabled()")
                        && policy.contains("resolveCallerLocation(caller, &caller_location)")
                        && policy.contains("isAppOwnedNativeCallerPath(caller_location.path)")
                        && policy.contains("resolveMemoryMapEntry(caller, &entry)")
                        && policy.contains("isAppOwnedNativeCallerPath(entry.path)")
                        && policy.contains("hasAppOwnedNativeFrame()"));
        assertTrue("A resolved libc/libffi trampoline caller must not short-circuit the stack-aware fallback; only an app-owned immediate caller may return early",
                policy.contains("if (caller_location.resolved && isAppOwnedNativeCallerPath(caller_location.path)) {\n        return true;\n    }"));
        assertFalse("Returning false for every resolved non-app caller loses libffi-dispatched app frames and re-exposes raw /proc/self/maps",
                policy.contains("if (caller_location.resolved) {\n        return isAppOwnedNativeCallerPath(caller_location.path);\n    }"));
        assertTrue("App-visible proc maps must use the same stack-aware policy so libffi-dispatched /proc/self/maps reads receive current real ranges with sandbox paths hidden instead of path-only maps",
                appVisiblePolicy.contains("resolveCallerLocation(caller, &caller_location)")
                        && appVisiblePolicy.contains("isAppOwnedNativeCallerPath(caller_location.path)")
                        && appVisiblePolicy.contains("hasAppOwnedNativeFrame()"));
        assertTrue("The stack-aware helper should use the existing bounded native backtrace capture instead of virtualizing every framework maps scan",
                source.contains("bool hasAppOwnedNativeFrame()")
                        && source.contains("void *frames[kProcessProbeMaxFrames]")
                        && source.contains("captureNativeBacktrace(frames, kProcessProbeMaxFrames)")
                        && source.contains("isAppOwnedNativeCallerPath(frame_location.path)"));
        assertTrue("direct.open should fall back to the raw kernel open for /proc/self/maps when the caller is not app-owned native code",
                directOpen.contains("bool virtualize_proc_maps = shouldVirtualizeDirectProcMapsOpen(__builtin_return_address(0));")
                        && directOpen.contains("if (virtualize_proc_maps) {\n            int transient_maps = openTransientProcMapsFdForRead(pathname, __builtin_return_address(0));"));
        assertTrue("direct.openat should use the same app-native caller policy before creating sanitized maps fds",
                directOpenAt.contains("bool virtualize_proc_maps = shouldVirtualizeDirectProcMapsOpen(__builtin_return_address(0));")
                        && directOpenAt.contains("if (virtualize_proc_maps) {\n            int transient_maps = openTransientProcMapsFdForRead(resolved_log.path, __builtin_return_address(0));"));
    }

    @Test
    public void directLibcProcMapsVirtualizationCoversBionicOnlyReadsDuringAppNativeLoadWindow() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String policy = sliceBetween(source,
                "bool shouldVirtualizeDirectProcMapsOpen(",
                "extern \"C\" int blackbox_direct_open(");
        String appVisiblePolicy = sliceBetween(source,
                "bool shouldUseAppVisibleProcMapsForCaller(",
                "void replaceBlackBoxDataUserRoots(");
        String directOpen = sliceBetween(source,
                "extern \"C\" int blackbox_direct_open(",
                "extern \"C\" int blackbox_direct_open_2(");
        String directOpenAt = sliceBetween(source,
                "extern \"C\" int blackbox_direct_openat(",
                "extern \"C\" int blackbox_direct_openat_2(");

        assertTrue("App-owned native library loads should open a bounded maps trust window without naming any target package or library",
                source.contains("gAppNativeLoaderMapsTrustUntilNs")
                        && source.contains("kAppNativeLoaderMapsTrustWindowNs")
                        && source.contains("markAppNativeLoaderMapsWindow")
                        && source.contains("maybeMarkAppNativeLoaderMapsWindow")
                        && source.contains("isAppOwnedNativeLibraryPath"));
        assertTrue("The bounded window should be based on monotonic time and expire automatically to avoid virtualizing framework/WebView maps scans indefinitely",
                source.contains("CLOCK_MONOTONIC")
                        && source.contains("monotonicTimeNs()")
                        && source.contains("isWithinAppNativeLoaderMapsWindow()")
                        && source.contains("monotonicTimeNs() <= gAppNativeLoaderMapsTrustUntilNs"));
        assertTrue("When libffi dispatches open() from bionic with no app frame, direct maps virtualization should trust only that bounded app-native-load window",
                policy.contains("isWithinAppNativeLoaderMapsWindow()")
                        && policy.contains("isBionicLibcCallerPath(caller_location.path)")
                        && policy.contains("return true;"));
        assertFalse("The loader-window fallback must not reuse process_probe; process_probe is diagnostic and broadens maps virtualization for all callers",
                policy.contains("isProcessProbeEnabled() && isBionicLibcCallerPath"));
        assertTrue("The loader-window bionic fallback should receive app-visible real ranges; libffi/pthread anti-debug scans often surface as libc callers with no recoverable app frame",
                appVisiblePolicy.contains("if (isWithinAppNativeLoaderMapsWindow()\n"
                        + "        && caller_location.resolved\n"
                        + "        && isBionicLibcCallerPath(caller_location.path)) {\n"
                        + "        return true;\n"
                        + "    }"));
        assertFalse("The app-visible policy must not downgrade bionic/libffi loader-window maps reads back to public/path-only maps",
                appVisiblePolicy.contains("if (isWithinAppNativeLoaderMapsWindow()\n"
                        + "        && caller_location.resolved\n"
                        + "        && isBionicLibcCallerPath(caller_location.path)) {\n"
                        + "        return false;\n"
                        + "    }"));
        assertTrue("Successful app-owned .so opens should mark the window for both direct open and direct openat paths before later /proc/self/maps reads",
                directOpen.contains("maybeMarkAppNativeLoaderMapsWindow(pathname, redirected, result);")
                        && directOpenAt.contains("maybeMarkAppNativeLoaderMapsWindow(resolved_log.path, redirected_log, result);"));
    }

    @Test
    public void directProcMapsVirtualizationTracksAppOwnedPthreadStartRoutine() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String policy = sliceBetween(source,
                "bool shouldVirtualizeDirectProcMapsOpen(",
                "extern \"C\" int blackbox_direct_open(");
        String appVisiblePolicy = sliceBetween(source,
                "bool shouldUseAppVisibleProcMapsForCaller(",
                "void replaceBlackBoxDataUserRoots(");
        String pthreadCreate = sliceBetween(source,
                "extern \"C\" int pthread_create(",
                "extern \"C\" int kill(");

        assertTrue("libffi-dispatched protectors often scan /proc/self/maps from pthreads whose open stack has no app frame; pthread ids must be marked when their start routine or creation stack is app-owned",
                source.contains("gAppOwnedNativeThreads")
                        && source.contains("rememberAppOwnedNativeThread")
                        && source.contains("isCurrentThreadMarkedAppOwnedNative")
                        && source.contains("shouldMarkAppOwnedThread"));
        assertTrue("Direct libc /proc/self/maps virtualization should trust the bounded app-owned pthread marker before falling back to immediate caller or stack unwinding",
                policy.contains("if (isCurrentThreadMarkedAppOwnedNative()) {\n        return true;\n    }"));
        assertTrue("App-visible maps selection must use the same pthread marker so marked native app threads receive real current ranges with sanitized paths",
                appVisiblePolicy.contains("if (isCurrentThreadMarkedAppOwnedNative()) {\n        return true;\n    }"));
        assertTrue("pthread_create must classify/log the requested start routine, wrap app-owned threads to close the start-before-parent-return race, and still record successfully created pthread ids",
                pthreadCreate.contains("bool app_owned_thread = shouldMarkAppOwnedThread(")
                        && pthreadCreate.contains("requested_start_routine")
                        && pthreadCreate.contains("start_routine = appOwnedPthreadStartTrampoline")
                        && pthreadCreate.contains("int result = fn(thread, attr, start_routine, arg);")
                        && pthreadCreate.contains("if (result == 0 && app_owned_thread && thread != nullptr)")
                        && pthreadCreate.contains("rememberAppOwnedNativeThread(*thread);")
                        && pthreadCreate.contains("reinterpret_cast<void *>(requested_start_routine)"));
    }

    @Test
    public void directLibcPthreadCreateHookMarksLibffiCreatedAppThreadsWithoutDlsymPointerReplacement() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String setup = sliceBetween(source,
                "extern \"C\" void setNativeTerminationShieldPackage(",
                "extern \"C\" void disableEarlyProcMapsShim()");
        String installer = sliceBetween(source,
                "void installDirectLibcPthreadCreateHook()",
                "extern \"C\" void installNativeFileHooks()");
        String dlsymReplacement = sliceBetween(source,
                "void *resolveDlsymReplacement(",
                "extern \"C\" void *blackbox_dlsym(");

        assertTrue("libffi-dispatched pthread_create calls should be covered by patching libc's real pthread_create entry, not only by PLT patching already-loaded app objects",
                source.contains("installDirectLibcPthreadCreateHook")
                        && setup.contains("installDirectLibcPthreadCreateHook();")
                        && installer.contains("\"pthread_create\"")
                        && installer.contains("PineNativeInlineHookFuncNoBackup")
                        && installer.contains("hook_func(real_symbol, replacement)"));
        assertTrue("The direct pthread_create hook must reuse the AOSP-compatible pthread_create wrapper so start_routine is preserved and app-owned thread ids are remembered",
                installer.contains("reinterpret_cast<void *>(static_cast<PthreadCreateFn>(pthread_create))")
                        && installer.contains("createNativeFunctionBackup(real_symbol")
                        && installer.contains("gOrigPthreadCreate = reinterpret_cast<PthreadCreateFn>(backup)")
                        && source.contains("bool app_owned_thread = shouldMarkAppOwnedThread(")
                        && source.contains("rememberAppOwnedNativeThread(*thread);"));
        assertTrue("PLT pthread_create patching should remain diagnostic-only; default coverage should come from the lower-surface libc entry hook",
                source.contains("bool shouldPatchPthreadCreate()")
                        && source.contains("return isProcessProbeEnabled();"));
        assertFalse("Do not fix libffi pthread_create by returning a libblackbox pthread_create address from dlsym; that exposes the replacement pointer surface",
                dlsymReplacement.contains("strcmp(symbol, \"pthread_create\") == 0")
                        && !dlsymReplacement.contains("isDlsymReplacementEnabled()"));
    }

    @Test
    public void appOwnedPthreadTrampolineRefreshesRawSvcCoverageBeforeAntiDebugStartRoutineRuns() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String trampoline = sliceBetween(source,
                "void *appOwnedPthreadStartTrampoline(void *opaque)",
                "bool shouldUseAppVisibleProcMapsForCaller(");
        String pthreadWrapper = sliceBetween(source,
                "extern \"C\" int pthread_create(",
                "extern \"C\" int kill(");

        assertTrue("libffi-created anti-debug threads can run hand-written SVC stubs from unpacked app-owned code; the pthread layer should refresh the generic raw syscall probe instead of relying on dlsym/PLT replacement",
                source.contains("#include \"../RawSyscallTerminationProbe.h\"")
                        && source.contains("blackbox::rawsyscall::refreshRawSyscallProbeMaps();"));
        assertFalse("the parent pthread_create path must not patch executable app library text before JNI_OnLoad/integrity checks complete",
                pthreadWrapper.contains("blackbox::rawsyscall::refreshRawSyscallProbeMaps();"));
        assertTrue("the wrapper should redirect app-owned native threads through the trampoline before calling the real pthread_create",
                pthreadWrapper.contains("start_routine = appOwnedPthreadStartTrampoline;")
                        && pthreadWrapper.indexOf("start_routine = appOwnedPthreadStartTrampoline;")
                        < pthreadWrapper.indexOf("int result = fn(thread, attr, start_routine, arg);"));
        assertTrue("the trampoline should refresh after marking the child thread and before the protected start routine executes",
                trampoline.contains("rememberAppOwnedNativeThread(pthread_self());")
                        && trampoline.contains("blackbox::rawsyscall::refreshRawSyscallProbeMaps();")
                        && trampoline.indexOf("rememberAppOwnedNativeThread(pthread_self());")
                        < trampoline.indexOf("blackbox::rawsyscall::refreshRawSyscallProbeMaps();")
                        && trampoline.indexOf("blackbox::rawsyscall::refreshRawSyscallProbeMaps();")
                        < trampoline.indexOf("return start_routine(arg);"));
        assertFalse("raw SVC patch refresh must stay package/sample agnostic",
                source.contains("com.bestv.tv.video.iqy.tjdx")
                        || source.contains("TelnetCommand")
                        || source.contains("WONT"));
    }

    @Test
    public void directLibcMetadataHooksRedirectLibffiMapsFollowupPathChecks() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String setup = sliceBetween(source,
                "extern \"C\" void setNativeTerminationShieldPackage(",
                "extern \"C\" void disableEarlyProcMapsShim()");
        String installer = sliceBetween(source,
                "void installDirectLibcMetadataHooks()",
                "void *createNativeFunctionBackup(");
        String metadataWrappers = sliceBetween(source,
                "extern \"C\" int access(",
                "extern \"C\" uid_t getuid()");

        assertTrue("Protectors can dispatch metadata probes through libffi/bionic after reading sanitized /proc/self/maps; direct libc access/stat/readlink/mkdir entries must route through the same generic IO redirection wrappers",
                source.contains("installDirectLibcMetadataHooks")
                        && setup.contains("installDirectLibcMetadataHooks();")
                        && installer.contains("\"access\"")
                        && installer.contains("\"stat\"")
                        && installer.contains("\"lstat\"")
                        && installer.contains("\"readlink\"")
                        && installer.contains("\"mkdir\""));
        assertTrue("Direct metadata hooks must patch the real libc entry without exposing dlsym pointer replacements",
                installer.contains("PineNativeInlineHookFuncNoBackup")
                        && installer.contains("installThumbAwareDirectJump(real_symbol, spec.replacement, spec.symbol, hook_func)")
                        && installer.contains("native direct libc metadata hook symbol=%s"));
        assertTrue("Pine's Thumb literal-load jump corrupts 2 mod 4 function entries; metadata hooks must use an alignment-aware direct jump that pads the literal to a 4-byte address",
                source.contains("bool installThumbAwareDirectJump(")
                        && source.contains("(target_addr & 0x3U) == 2U")
                        && source.contains("0xf004")
                        && source.contains("0xbf00")
                        && source.contains("patch_size = 12"));
        assertFalse("Direct metadata wrappers must not forward through copied libc entry backups: Tester/WebView proved that broad backup trampolines can jump into non-executable stack/mark memory. Forward through unpatched lower-level *at libc APIs instead.",
                installer.contains("createNativeFunctionBackup(real_symbol")
                        || installer.contains("*spec.original = backup;"));
        assertTrue("The direct access/stat/lstat/readlink/mkdir replacements must avoid recursion into their patched libc entries by forwarding through unpatched lower-level *at APIs",
                metadataWrappers.contains("resolveSymbol(&gOrigFaccessat, \"faccessat\")")
                        && metadataWrappers.contains("resolveSymbol(&gOrigFstatat, \"fstatat\")")
                        && metadataWrappers.contains("resolveSymbol(&gOrigReadlinkAt, \"readlinkat\")")
                        && metadataWrappers.contains("resolveSymbol(&gOrigMkdirAt, \"mkdirat\")"));
        assertFalse("The directly patched metadata entry wrappers should not resolve their own patched libc symbols as originals",
                metadataWrappers.contains("resolveSymbol(&gOrigAccess, \"access\")")
                        || metadataWrappers.contains("resolveSymbol(&gOrigStat, \"stat\")")
                        || metadataWrappers.contains("resolveSymbol(&gOrigLstat, \"lstat\")")
                        || metadataWrappers.contains("resolveSymbol(&gOrigReadlink, \"readlink\")")
                        || metadataWrappers.contains("resolveSymbol(&gOrigMkdir, \"mkdir\")"));
        assertFalse("Direct libc metadata coverage must remain package-agnostic and not hardcode the protected sample",
                installer.contains("com.bestv.tv.video.iqy.tjdx")
                        || installer.contains("libjiagu")
                        || installer.contains("entryRunApplication"));
    }

    @Test
    public void nativeTerminationShieldBlocksForkedWatchdogKillingOriginalSandboxProcess() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("Shield setup should remember the original sandbox process pid before protected code can fork",
                source.contains("gNativeTerminationShieldRootPid")
                        && source.contains("gNativeTerminationShieldRootPid = getpid()"));
        assertTrue("Shield setup should remember the original sandbox process group for kill(0) and kill(-pgid) watchdogs",
                source.contains("gNativeTerminationShieldRootPgid")
                        && source.contains("gNativeTerminationShieldRootPgid = getpgrp()"));
        assertTrue("kill() shielding should block forked watchdogs that target the original sandbox pid, not only the current child pid",
                source.contains("pid == gNativeTerminationShieldRootPid")
                        && source.contains("pid == getpid()"));
        assertTrue("kill() shielding should cover process-group termination forms inherited by forked watchdogs",
                source.contains("pid == 0")
                        && source.contains("pid == -gNativeTerminationShieldRootPgid")
                        && source.contains("pid == -1"));
        assertTrue("tgkill() shielding should block signals whose thread-group target is the original sandbox pid",
                source.contains("tgid == gNativeTerminationShieldRootPid")
                        && source.contains("tgid == getpid()"));
    }

    @Test
    public void nativeTerminationBlockingEmitsBacktraceWithoutRequiringProbeProperty() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String blocked = sliceBetween(source,
                "void logNativeTerminationBlocked(",
                "void logProcessProbe(");

        assertTrue("blocked self-termination should emit a bounded native backtrace even when non-blocking probe diagnostics are disabled",
                source.contains("void dumpBlockedNativeTerminationFrames")
                        && blocked.contains("dumpBlockedNativeTerminationFrames(api);"));
        assertTrue("blocked backtrace logs should include IDA-friendly map offsets",
                source.contains("native termination blocked frame")
                        && source.contains("pcOff=0x%lx")
                        && source.contains("pcMap=%s"));
    }

    @Test
    public void nativeFileHooksDiagnoseProcessCreationForExternalWatchdogs() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("process creation diagnostics should be explicit opt-in to avoid default loader perturbation",
                source.contains("debug.blackbox.process_probe")
                        && source.contains("isProcessProbeEnabled"));
        assertTrue("Native hook should export bionic process creation prototypes checked against AOSP",
                source.contains("extern \"C\" pid_t fork(")
                        && source.contains("extern \"C\" pid_t vfork(")
                        && source.contains("extern \"C\" int clone(")
                        && source.contains("extern \"C\" int execve(")
                        && source.contains("extern \"C\" int pthread_create("));
        assertTrue("Native syscall hook should diagnose raw clone/execve watchdog creation paths",
                source.contains("case __NR_clone:")
                        && source.contains("case __NR_execve:"));
        assertTrue("process creation diagnostics should include caller address, parent pid, result pid, flags, and path",
                source.contains("native process probe")
                        && source.contains("caller=%p")
                        && source.contains("parent=%d")
                        && source.contains("result=%d")
                        && source.contains("flags=0x%lx")
                        && source.contains("path=%s"));
        assertTrue("pthread_create diagnostics should include both the libffi/PLT caller and requested thread start routine",
                source.contains("logPthreadCreateProbe")
                        && source.contains("startRoutine=%p")
                        && source.contains("startOff=0x%lx")
                        && source.contains("startMap=%s"));
        assertTrue("pthread_create diagnostics should emit native frames for IDA correlation when process probing is enabled",
                source.contains("native process frame")
                        && source.contains("captureNativeBacktrace(frames, kProcessProbeMaxFrames)"));
        assertTrue("pthread_create PLT replacement is observable to anti-debug loaders, so it must be an explicit process-probe diagnostic rather than a default native hook",
                source.contains("void *pthread_create_hook = shouldPatchPthreadCreate()")
                        && source.contains("{\"pthread_create\", pthread_create_hook")
                        && source.contains("bool shouldPatchPthreadCreate()"));
        assertFalse("Default native hook installation must not always expose a libblackbox pthread_create pointer through app PLT/GOT slots",
                source.contains("{\"pthread_create\", reinterpret_cast<void *>(static_cast<PthreadCreateFn>(pthread_create))"));
        assertTrue("dlsym-resolved pthread_create replacement should remain an explicit diagnostic, not a default environment mutation",
                source.contains("typedef void *(*DlsymFn)(void *handle, const char *symbol)")
                        && source.contains("extern \"C\" void *blackbox_dlsym(void *handle, const char *symbol)")
                        && source.contains("resolveDlsymReplacement")
                        && source.contains("isDlsymReplacementEnabled()")
                        && source.contains("\"pthread_create\"")
                        && source.contains("native dlsym probe")
                        && source.contains("{\"dlsym\"")
                        && source.contains("reinterpret_cast<void *>(static_cast<DlsymFn>(blackbox_dlsym))"));
    }

    @Test
    public void fileProbeLogsAppOwnedNativeRelativeOpensForAntiDebugTriage() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String logOpen = sliceBetween(source,
                "void logOpenPath(",
                "void logNativeTerminationProbe(");

        assertTrue("File-probe diagnostics should not miss libffi/pthread anti-debug checks that open relative or synthetic paths from app-owned native code",
                source.contains("bool shouldLogAppOwnedNativeFileProbe(const char *pathname, const char *redirected, void *caller)")
                        && source.contains("bool shouldLogAppOwnedNativeFilePath(const char *pathname, const char *redirected)")
                        && source.contains("isCurrentThreadMarkedAppOwnedNative()")
                        && source.contains("isAppOwnedNativeAddress(caller)")
                        && source.contains("hasAppOwnedNativeFrame()"));
        assertTrue("Open diagnostics should keep the narrow path filter for normal framework calls but expand when the caller stack/thread is app-owned native and the path is relevant",
                logOpen.contains("bool should_log = shouldLogOpenPath(pathname, redirected)")
                        && logOpen.contains("shouldLogAppOwnedNativeFilePath(pathname, redirected)")
                        && logOpen.contains("shouldLogAppOwnedNativeFileProbe(pathname, redirected, caller)")
                        && logOpen.contains("if (!should_log)"));
        assertTrue("The app-owned fallback must not turn opt-in diagnostics into a generic /sys tracer, because that regresses Tester and masks IO deadlocks",
                source.contains("bool isSysfsProbePath(const char *pathname)")
                        && source.contains("!isSysfsProbePath(pathname)")
                        && source.contains("!isSysfsProbePath(redirected)"));
        assertTrue("Open diagnostics should preserve errno before resolving caller maps so failed native path checks can be correlated with IDA branches",
                logOpen.contains("int result_errno = result < 0 ? errno : 0")
                        && logOpen.contains("errno=%d")
                        && logOpen.contains("result_errno"));
        assertFalse("The app-owned relative-open diagnostic must stay package-agnostic",
                source.contains("com.bestv.tv.video.iqy.tjdx"));
    }

    @Test
    public void fileProbeDoesNotGloballyTraceFrameworkAppDataChurn() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String focusedOpenFilter = sliceBetween(source,
                "bool shouldLogOpenPath(",
                "bool isRelativeFileProbePath(");
        String appOwnedProbe = sliceBetween(source,
                "bool shouldLogAppOwnedNativeFileProbe(const char *pathname, const char *redirected, void *caller) {",
                "void replaceBlackBoxDataUserRoots(");

        assertFalse("The global file-probe filter must not trace every framework/WebView private-data redirect; that diagnostic noise regresses Tester and can hide the real anti-debug path",
                focusedOpenFilter.contains("\"/data/data/\"")
                        || focusedOpenFilter.contains("\"/data/user/\""));
        assertTrue("Private app-data probes are still useful, but only when the immediate caller/thread is app-owned native; do not run a full stack scan for every framework cache open",
                source.contains("bool isAppPrivateDataProbePath(const char *pathname)")
                        && appOwnedProbe.contains("isAppPrivateDataProbePath(pathname)")
                        && appOwnedProbe.contains("isAppPrivateDataProbePath(redirected)")
                        && appOwnedProbe.contains("return isCurrentThreadMarkedAppOwnedNative() || isAppOwnedNativeAddress(caller);"));
        assertTrue("Full stack scanning should be reserved for relative/proc/apk-style app-owned probes, after the private-data fast path",
                appOwnedProbe.indexOf("return isCurrentThreadMarkedAppOwnedNative() || isAppOwnedNativeAddress(caller);")
                        < appOwnedProbe.indexOf("return hasAppOwnedNativeFrame();"));
    }

    @Test
    public void pthreadCreateMarksAppOwnedThreadAtEntrypointBeforeLibffiMapsScanCanRaceParent() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String pthreadWrapper = sliceBetween(source,
                "extern \"C\" int pthread_create(",
                "extern \"C\" int kill(");

        assertTrue("libffi-created anti-debug threads can read /proc/self/maps before the parent returns from pthread_create; app-owned threads must be marked inside a generic start trampoline",
                source.contains("struct AppOwnedPthreadStartContext")
                        && source.contains("void *appOwnedPthreadStartTrampoline(void *opaque)")
                        && source.contains("rememberAppOwnedNativeThread(pthread_self())")
                        && source.contains("void *(*start_routine)(void *) = context->start_routine")
                        && source.contains("return start_routine(arg)"));
        assertTrue("pthread_create should wrap only app-owned native start routines, preserve the original start routine/arg, and free the trampoline context on create failure",
                pthreadWrapper.contains("if (app_owned_thread && requested_start_routine != nullptr)")
                        && pthreadWrapper.contains("malloc(sizeof(AppOwnedPthreadStartContext))")
                        && pthreadWrapper.contains("start_routine = appOwnedPthreadStartTrampoline")
                        && pthreadWrapper.contains("arg = context")
                        && pthreadWrapper.contains("if (result != 0 && context != nullptr)")
                        && pthreadWrapper.contains("free(context)"));
        assertTrue("The parent-side remember step should remain as a fallback after pthread_create returns, but it is no longer the only race-sensitive marker",
                pthreadWrapper.contains("rememberAppOwnedNativeThread(*thread);"));
        assertFalse("The pthread entrypoint trampoline must stay package-agnostic",
                source.contains("com.bestv.tv.video.iqy.tjdx")
                        || source.contains("libjiagu"));
    }

    @Test
    public void dlsymResolvedProcessEntrypointsAreNotReplacedByDefault() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/NativeFileHook.cpp",
                "Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String resolve = sliceBetween(source,
                "void *resolveDlsymReplacement(",
                "void logDlsymProbe(");

        assertTrue("Returning libblackbox function pointers from dlsym is observable, so replacement must be a separate explicit diagnostic",
                source.contains("debug.blackbox.dlsym_replace")
                        && source.contains("isDlsymReplacementEnabled"));
        assertTrue("Even dlsym logging changes the linker caller surface, so the dlsym hook itself must be explicit diagnostic only",
                source.contains("debug.blackbox.dlsym_probe")
                        && source.contains("shouldPatchDlsym()"));
        assertTrue("dlsym replacement must default to nullptr unless explicitly enabled",
                resolve.contains("if (!isDlsymReplacementEnabled())")
                        && resolve.contains("return nullptr;"));
        assertTrue("The dlsym PLT slot should be patched only when dlsym diagnostics/replacement are enabled",
                source.contains("void *dlsym_hook = shouldPatchDlsym()")
                        && source.contains("{\"dlsym\", dlsym_hook"));
        assertTrue("blackbox_dlsym should still log the real resolved pointer and whether replacement was used",
                source.contains("void *real = fn == nullptr ? nullptr : fn(handle, symbol);")
                        && source.contains("void *result = replacement != nullptr ? replacement : real;")
                        && source.contains("logDlsymProbe(symbol, result, replacement != nullptr, caller);"));
    }

    @Test
    public void nativeCoreConfiguresNativeTerminationShieldBeforeApplicationStartup() throws Exception {
        String nativeCore = readSource(
                "src/main/java/top/niunaijun/blackbox/core/NativeCore.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java");
        String boxCore = readSource(
                "src/main/cpp/BoxCore.cpp",
                "Bcore/src/main/cpp/BoxCore.cpp");
        String activityThread = readSource(
                "src/main/java/top/niunaijun/blackbox/app/BActivityThread.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java");

        assertTrue("NativeCore should expose the package-scoped native termination shield",
                nativeCore.contains("native void setNativeTerminationShieldPackage(String packageName)"));
        assertTrue("BoxCore should register the native termination shield JNI bridge",
                boxCore.contains("{\"setNativeTerminationShieldPackage\",")
                        && boxCore.contains("setNativeTerminationShieldPackage(package_name)"));

        int enableRedirect = activityThread.indexOf("IOCore.get().enableRedirect(packageContext)");
        int setShield = activityThread.indexOf("NativeCore.setNativeTerminationShieldPackage(packageName)");
        int makeApplication = activityThread.indexOf("BRLoadedApk.getWithException(loadedApk).makeApplication(false, null)");
        assertTrue("BActivityThread should configure native termination shielding after IO rules but before makeApplication",
                enableRedirect >= 0 && setShield > enableRedirect && setShield < makeApplication);
    }

}
