package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RawSyscallTerminationProbeSourceTest {
    private static final Path ROOT = Paths.get(System.getProperty("user.dir")).getParent();

    @Test
    public void rawSyscallTerminationProbeIsExplicitGenericAttachDiagnostic() throws Exception {
        String nativeCore = read("Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java");
        String boxCore = read("Bcore/src/main/cpp/BoxCore.cpp");
        String attachProxy = read("Bcore/src/main/java/top/niunaijun/blackbox/fake/service/ApplicationAttachSeccompProxy.java");

        assertTrue(nativeCore.contains("installRawSyscallTerminationProbe"));
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
        String source = read("Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp");

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
        String source = read("Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp");

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
        String source = read("Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp");

        assertTrue("protected loaders can place raw syscall stubs in executable anonymous bss maps",
                source.contains("isPatchableAnonymousExecutableMap")
                        && source.contains("strncmp(path, \"[anon:.bss]\""));
        assertTrue("non-termination traps from anonymous code should keep the SVC trap active",
                source.contains("raw syscall non-termination emulated")
                        && source.contains("emulateRawSyscall(sysno, mc)")
                        && source.contains("entry->address + entry->size"));
        assertFalse("a generic raw syscall trampoline must not be unpatched after its first benign call",
                source.contains("raw syscall non-termination restored")
                        || source.contains("mc.arm_pc = static_cast<unsigned long>(entry->address);"));
        assertTrue("anonymous diagnostics must still skip broad JIT/Pine executable maps",
                source.contains("isExcludedVolatileExecutableMap")
                        && source.contains("strncmp(path, \"/memfd:\", 7)")
                        && source.contains("strncmp(path, \"[anon:pine codes]\""));
    }

    @Test
    public void rawSyscallProbeRateLimitsBenignSyscallTelemetry() throws Exception {
        String source = read("Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp");

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
    public void rawSyscallProbeRedirectsDirectArmSvcFileSyscallsThroughIoCore() throws Exception {
        String source = read("Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp");

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
                source.contains("emulateRedirectableRawSyscall(sysno, mc)")
                        && source.contains("rawKernelSyscall6(sysno,")
                        && source.contains("redirected_path != pathname"));
        assertTrue("redirected path buffers allocated by IOCore must be released after the kernel syscall returns",
                source.contains("releaseRedirectedRawPath(pathname, redirected_path)"));
        assertTrue("raw file syscall telemetry should be separate from termination telemetry and rate limited by the existing SVC-site counter",
                source.contains("raw syscall file redirected")
                        && source.contains("shouldLogNonTerminationTrap(entry)"));
        assertFalse("raw syscall IO redirection must stay package/sample agnostic",
                source.contains("com.bestv")
                        || source.contains("TelnetCommand")
                        || source.contains("WONT")
                        || source.contains("jiagu"));
    }

    @Test
    public void rawSyscallRuntimeRefreshAvoidsFileBackedAppTextIntegritySurface() throws Exception {
        String source = read("Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp");
        String shouldScanPath = sliceBetween(source,
                "static bool shouldScanPath(",
                "static bool isPatchableAnonymousExecutableMap");
        String install = sliceBetween(source,
                "void installRawSyscallTerminationProbe()",
                "void refreshRawSyscallProbeMaps()");
        String refresh = sliceBetween(source,
                "void refreshRawSyscallProbeMaps()",
                "} // namespace rawsyscall");

        assertTrue("explicit diagnostic installs may still scan file-backed app code for full raw termination forensics",
                source.contains("bool include_file_backed_app_code")
                        && shouldScanPath.contains("include_file_backed_app_code")
                        && install.contains("scanProcessMaps(true);"));
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

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(ROOT.resolve(relative)), StandardCharsets.UTF_8);
    }

    private static String sliceBetween(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        if (start < 0) {
            return "";
        }
        int end = source.indexOf(endMarker, start + startMarker.length());
        if (end < 0) {
            return source.substring(start);
        }
        return source.substring(start, end);
    }
}
