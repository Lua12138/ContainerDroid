package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;
import static top.niunaijun.blackbox.core.SourceAssertions.sliceBetweenOrTail;

public class RawSyscallTerminationProbeSourceTest {
    @Test
    public void rawSyscallTerminationProbeIsExplicitGenericAttachDiagnostic() throws Exception {
        String nativeCore = readSource("Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java");
        String boxCore = readSource("Bcore/src/main/cpp/BoxCore.cpp");
        String attachProxy = readSource("Bcore/src/main/java/top/niunaijun/blackbox/fake/service/ApplicationAttachSeccompProxy.java");

        assertTrue(nativeCore.contains("installRawSyscallEnvironmentProbe"));
        assertTrue(nativeCore.contains("installRawSyscallTerminationProbe"));
        assertTrue(boxCore.contains("installRawSyscallEnvironmentProbe"));
        assertTrue(boxCore.contains("installRawSyscallTerminationProbe"));
        assertTrue(attachProxy.contains("BLACKBOX_ATTACH_RAW_SYSCALL_PROBE"));
        assertTrue(attachProxy.contains("blackbox.attach_raw_syscall_probe"));
        assertTrue(attachProxy.contains("debug.blackbox.attach_raw_syscall_probe"));
        assertTrue(attachProxy.contains("raw syscall termination probe installed after attach"));
        assertTrue("raw syscall probe must be explicit opt-in",
                attachProxy.contains("isAttachRawSyscallProbeEnabled()"));
    }

    @Test
    public void rawSyscallTerminationProbeCapturesDirectArmSvcTerminationWithoutSeccomp() throws Exception {
        String source = readSource("Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp");

        assertTrue(source.contains("SIGTRAP"));
        assertTrue(source.contains("bkpt"));
        assertTrue(source.contains("svc"));
        assertTrue(source.contains("arm_r7"));
        assertTrue(source.contains("__NR_exit"));
        assertTrue(source.contains("__NR_exit_group"));
        assertTrue(source.contains("__NR_kill"));
        assertTrue(source.contains("__NR_tkill"));
        assertTrue(source.contains("__NR_tgkill"));
        assertTrue(source.contains("raw syscall termination"));
        assertTrue(source.contains("pc=0x"));
        assertTrue(source.contains("lr=0x"));
        assertFalse("raw syscall probe must not rely on seccomp/no_new_privs",
                source.contains("PR_SET_NO_NEW_PRIVS")
                        || source.contains("SECCOMP_SET_MODE_FILTER")
                        || source.contains("SECCOMP_RET_TRAP"));
        assertFalse("raw syscall probe must not hardcode target samples",
                source.contains("com.bestv")
                        || source.contains("entryRunApplication")
                        || source.contains("TelnetCommand")
                        || source.contains("WONT")
                        || source.contains("jiagu"));
    }

    @Test
    public void rawSyscallProbeDoesNotRouteSignalHandlerEmulationThroughInterposedSyscall() throws Exception {
        String source = readSource("Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp");

        assertTrue("non-termination SVC emulation should use a private raw kernel syscall helper",
                source.contains("rawKernelSyscall6"));
        assertTrue("ARM raw syscall helper should issue svc directly instead of calling libc syscall",
                source.contains("__asm__ volatile")
                        && source.contains("svc #0"));
        assertFalse("SIGTRAP handler must not call the interposed syscall() wrapper while emulating non-termination SVC",
                source.contains("syscall(sysno,"));
        assertTrue("patch logs should include maps file offsets for IDA/JADX correlation",
                source.contains("map_offset")
                        && source.contains("pcFileOff=0x"));
        assertTrue("volatile executable maps such as JIT/anonymous code should be skipped by default",
                source.contains("isVolatileExecutableMap")
                        && source.contains("raw syscall probe skipped volatile executable map"));
        assertFalse("default scan should not opt into broad /memfd and [anon] executable patching",
                source.contains("|| strncmp(path, \"/memfd:\", 7) == 0")
                        || source.contains("|| strncmp(path, \"[anon:\", 6) == 0"));
    }

    @Test
    public void rawSyscallProbeCanDiagnoseAnonymousLoaderCodeWithoutEmulatingEverySyscall() throws Exception {
        String source = readSource("Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp");

        assertTrue("protected loaders can place raw syscall stubs in executable anonymous bss maps",
                source.contains("isPatchableAnonymousExecutableMap")
                        && source.contains("strncmp(path, \"[anon:.bss]\""));
        assertTrue("non-termination traps from anonymous code should keep the SVC trap active until a high-frequency passthrough site is proven hot",
                source.contains("raw syscall non-termination emulated")
                        && source.contains("emulateRawSyscall(sysno, mc)")
                        && source.contains("entry->address + entry->size"));
        assertTrue("a generic raw syscall trampoline must not be unpatched after its first benign call; only hot passthrough read/lseek sites may be restored after a threshold",
                source.contains("kHotPassthroughRestoreThreshold")
                        && source.contains("shouldRestoreHotPassthroughPatch")
                        && source.contains("count >= kHotPassthroughRestoreThreshold"));
        assertFalse("the handler must continue past the emulated SVC instead of retrying the same patched instruction",
                source.contains("mc.arm_pc = static_cast<unsigned long>(entry->address);"));
        assertTrue("anonymous diagnostics must still skip broad JIT/Pine executable maps",
                source.contains("isExcludedVolatileExecutableMap")
                        && source.contains("strncmp(path, \"/memfd:\", 7)")
                        && source.contains("strncmp(path, \"[anon:pine codes]\""));
    }

    @Test
    public void rawSyscallProbeRateLimitsBenignSyscallTelemetry() throws Exception {
        String source = readSource("Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp");

        assertTrue("benign syscall traps should be counted per patched SVC site",
                source.contains("non_termination_count"));
        assertTrue("non-termination raw syscall logs should be rate limited to avoid perturbing protected loaders",
                source.contains("shouldLogNonTerminationTrap")
                        && source.contains("count <= 3")
                        && source.contains("(count & (count - 1)) == 0"));
        assertTrue("rate-limited logs should include the count so repeated trampoline use remains visible",
                source.contains("count=%u")
                        && source.contains("entry->non_termination_count"));
    }

    @Test
    public void rawSyscallProbeRestoresOnlyHotReadAndLseekPassthroughSites() throws Exception {
        String source = readSource("Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp");
        String restorePredicate = sliceBetweenOrTail(source,
                "static bool shouldRestoreHotPassthroughPatch",
                "static bool restorePatch");
        String handler = sliceBetweenOrTail(source,
                "static void sigtrapHandler",
                "static int protFromPerms");

        assertTrue("hot passthrough restoration should be explicit, thresholded, and limited to syscall sites that remain patched",
                source.contains("kHotPassthroughRestoreThreshold")
                        && source.contains("restorePatch(PatchEntry *entry)")
                        && restorePredicate.contains("entry->patched")
                        && restorePredicate.contains("count >= kHotPassthroughRestoreThreshold"));
        assertTrue("only high-frequency non-path passthrough syscalls should be candidates for unpatching",
                restorePredicate.contains("isHighFrequencyPassthroughSyscall(sysno)")
                        && source.contains("case __NR_read:")
                        && source.contains("case __NR_lseek:")
                        && !restorePredicate.contains("__NR_open")
                        && !restorePredicate.contains("__NR_openat")
                        && !restorePredicate.contains("__NR_access")
                        && !restorePredicate.contains("__NR_readlink"));
        assertTrue("path/syscall redirect sites must remain patched even if they share the same anonymous loader map",
                source.contains("saw_redirectable_syscall")
                        && handler.contains("entry->saw_redirectable_syscall = true")
                        && restorePredicate.contains("!entry->saw_redirectable_syscall"));
        assertTrue("the current trapped syscall should still be emulated before the hot site is restored for later calls",
                handler.contains("long result = emulateRedirectableRawSyscall(sysno, mc, &telemetry)")
                        && handler.contains("const uint32_t count = incrementNonTerminationCount(entry)")
                        && handler.indexOf("long result = emulateRedirectableRawSyscall(sysno, mc, &telemetry)")
                        < handler.indexOf("restoreHotPassthroughPatch(entry, sysno, count)"));
        assertFalse("hot passthrough restoration must stay generic and sample agnostic",
                source.contains("com.bestv")
                        || source.contains("TelnetCommand")
                        || source.contains("WONT")
                        || source.contains("jiagu"));
    }

    @Test
    public void rawSyscallProbeSerializesRuntimeRefreshToAvoidDuplicateSvcPatches() throws Exception {
        String source = readSource("Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp");
        String refresh = sliceBetweenOrTail(source,
                "void refreshRawSyscallProbeMaps()",
                "} // namespace rawsyscall");
        String installEnvironment = sliceBetweenOrTail(source,
                "void installRawSyscallEnvironmentProbe()",
                "void installRawSyscallTerminationProbe()");
        String installTermination = sliceBetweenOrTail(source,
                "void installRawSyscallTerminationProbe()",
                "void refreshRawSyscallProbeMaps()");

        assertTrue("raw syscall patch registry updates must be serialized because app-owned pthreads can refresh concurrently and otherwise record duplicate SVC patch entries",
                source.contains("gPatchRegistryLock")
                        && source.contains("ScopedPatchRegistryLock"));
        assertTrue("all public scan entry points should acquire the same registry lock before scanProcessMaps mutates gPatches/gPatchCount",
                refresh.contains("ScopedPatchRegistryLock lock")
                        && installEnvironment.contains("ScopedPatchRegistryLock lock")
                        && installTermination.contains("ScopedPatchRegistryLock lock"));
        assertTrue("the duplicate check must run while the refresh/install lock is held",
                source.contains("findRecordedPatch(address)")
                        && source.contains("scanProcessMaps(false);")
                        && source.contains("scanProcessMaps(true);"));
        assertFalse("registry serialization must stay generic and sample agnostic",
                source.contains("com.bestv")
                        || source.contains("TelnetCommand")
                        || source.contains("WONT")
                        || source.contains("jiagu"));
    }

    @Test
    public void rawExitSyscallTrapDoesNotFallThroughIntoBionicFatalTrap() throws Exception {
        String source = readSource("Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp");
        String handler = sliceBetweenOrTail(source,
                "static void sigtrapHandler",
                "static int protFromPerms");

        assertTrue("raw exit/exit_group should be distinguished from kill-style syscalls when explicit termination blocking is enabled",
                source.contains("isProcessExitSyscall")
                        && handler.contains("isProcessExitSyscall(sysno)"));
        assertTrue("blocked raw exit should resume at LR instead of the instruction after svc, because bionic exit stubs place a fatal trap there",
                source.contains("resumeBlockedProcessExit")
                        && source.contains("mc.arm_pc = static_cast<unsigned long>(mc.arm_lr)"));
        assertTrue("kill/tgkill should still resume after the patched SVC instruction",
                handler.contains("entry->address + entry->size"));
        assertTrue("termination blocking must be explicitly enabled; the default environment probe should forward termination syscalls with real semantics",
                source.contains("gBlockTerminationSyscalls")
                        && source.contains("setRawSyscallTerminationBlocking(false)")
                        && source.contains("setRawSyscallTerminationBlocking(true)")
                        && handler.contains("if (!block_termination)")
                        && handler.contains("emulateRawSyscall(sysno, mc)"));
    }

    @Test
    public void rawSyscallProbeRedirectsDirectArmSvcFileSyscallsThroughIoCore() throws Exception {
        String source = readSource("Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp");

        assertTrue("direct ARM SVC open paths must pass through the same IOCore relocation rules as libc/syscall wrappers",
                source.contains("#include \"IO.h\"")
                        && source.contains("redirectRawSyscallPath")
                        && source.contains("IO::redirectPath(pathname)"));
        assertTrue("protected loaders use hand-written SVC stubs, so raw open/openat/access-style syscalls need generic path redirection before the private kernel syscall",
                source.contains("case __NR_open:")
                        && source.contains("case __NR_openat:")
                        && source.contains("case __NR_access:")
                        && source.contains("case __NR_faccessat:"));
        assertTrue("raw syscall file emulation must rewrite only the path register and still execute through the private kernel SVC helper",
                source.contains("emulateRedirectableRawSyscall(int sysno, const mcontext_t &mc,")
                        && source.contains("rawKernelSyscall6(sysno,")
                        && source.contains("redirected_path != pathname"));
        assertTrue("redirected path buffers allocated by IOCore must be released after the kernel syscall returns",
                source.contains("releaseRedirectedRawPath(pathname, redirected_path)"));
        assertTrue("raw file syscall telemetry should be separate from termination telemetry and rate limited by the existing SVC-site counter",
                source.contains("raw syscall file redirected")
                        && source.contains("const uint32_t count = incrementNonTerminationCount(entry)")
                        && source.contains("shouldLogNonTerminationTrap(count)"));
        assertFalse("raw syscall IO redirection must stay package/sample agnostic",
                source.contains("com.bestv")
                        || source.contains("TelnetCommand")
                        || source.contains("WONT")
                        || source.contains("jiagu"));
    }

    @Test
    public void rawSyscallProbeLogsPassthroughFilePathsForFailedEnvironmentPredicates() throws Exception {
        String source = readSource("Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp");
        String telemetry = sliceBetweenOrTail(source,
                "struct RawSyscallRedirectTelemetry",
                "static std::atomic<bool> gInstalled");
        String handler = sliceBetweenOrTail(source,
                "static void sigtrapHandler",
                "static int protFromPerms");

        assertTrue("raw open/access-style SVC telemetry should retain the original path even when IOCore does not rewrite it",
                telemetry.contains("bool redirectable")
                        && telemetry.contains("char original[kMaxPath]")
                        && source.contains("copyTelemetryPath(telemetry->original"));
        assertTrue("failed passthrough raw file probes must log the path, result, and IDA file offset so environment predicates can be correlated without blocking the app",
                handler.contains("raw syscall file passthrough")
                        && handler.contains("path=%s")
                        && handler.contains("result=0x%lx")
                        && handler.contains("pcFileOff=0x%")
                        && handler.contains("telemetry.original"));
        assertTrue("read/lseek syscall names should be decoded because protected loaders inspect maps/status byte-by-byte through raw SVC stubs",
                source.contains("case __NR_read:")
                        && source.contains("return \"read\"")
                        && source.contains("case __NR_lseek:")
                        && source.contains("return \"lseek\""));
        assertFalse("raw syscall passthrough diagnostics must remain sample agnostic",
                source.contains("com.bestv")
                        || source.contains("TelnetCommand")
                        || source.contains("entryRunApplication")
                        || source.contains("jiagu"));
    }

    @Test
    public void rawSyscallRuntimeRefreshAvoidsFileBackedAppTextIntegritySurface() throws Exception {
        String source = readSource("Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp");
        String shouldScanPath = sliceBetweenOrTail(source,
                "static bool shouldScanPath(",
                "static bool isPatchableAnonymousExecutableMap");
        String environmentInstall = sliceBetweenOrTail(source,
                "void installRawSyscallEnvironmentProbe()",
                "void installRawSyscallTerminationProbe()");
        String terminationInstall = sliceBetweenOrTail(source,
                "void installRawSyscallTerminationProbe()",
                "void refreshRawSyscallProbeMaps()");
        String refresh = sliceBetweenOrTail(source,
                "void refreshRawSyscallProbeMaps()",
                "} // namespace rawsyscall");

        assertTrue("package-scoped environment installs should scan file-backed app code for raw file-syscall virtualization without enabling termination blocking",
                environmentInstall.contains("setRawSyscallTerminationBlocking(false)")
                        && environmentInstall.contains("scanProcessMaps(true);"));
        assertTrue("explicit diagnostic installs may still scan file-backed app code for full raw termination forensics",
                source.contains("bool include_file_backed_app_code")
                        && shouldScanPath.contains("include_file_backed_app_code")
                        && terminationInstall.contains("setRawSyscallTerminationBlocking(true)")
                        && terminationInstall.contains("scanProcessMaps(true);"));
        assertTrue("runtime pthread refresh should patch unpacked anonymous loader code only, avoiding file-backed .so text that protected loaders may integrity-check during JNI_OnLoad",
                refresh.contains("scanProcessMaps(false);")
                        && shouldScanPath.contains("if (!include_file_backed_app_code) {\n        return false;\n    }"));
        assertTrue("anonymous loader SVC stubs remain covered even when file-backed app code is excluded",
                shouldScanPath.contains("return true;")
                        && source.contains("isPatchableAnonymousExecutableMap(path)"));
        assertFalse("runtime raw syscall scope must stay package/sample agnostic",
                source.contains("com.bestv")
                        || source.contains("TelnetCommand")
                        || source.contains("WONT")
                        || source.contains("jiagu"));
    }

}
