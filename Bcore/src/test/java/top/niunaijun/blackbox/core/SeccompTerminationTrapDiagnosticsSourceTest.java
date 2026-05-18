package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;
import static top.niunaijun.blackbox.core.SourceAssertions.sliceBetween;

public class SeccompTerminationTrapDiagnosticsSourceTest {

    @Test
    public void nativeBridgeExposesExplicitTerminationTrapDiagnostic() throws Exception {
        String header = readSource(
                "src/main/cpp/SeccompShield.h",
                "Bcore/src/main/cpp/SeccompShield.h");
        String nativeCore = readSource(
                "src/main/java/top/niunaijun/blackbox/core/NativeCore.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java");
        String boxCore = readSource(
                "src/main/cpp/BoxCore.cpp",
                "Bcore/src/main/cpp/BoxCore.cpp");

        assertTrue("SeccompShield should expose a separate diagnostic trap installer",
                header.contains("void installTerminationTrapSeccompShield()"));
        assertTrue("NativeCore should expose a Java bridge for the diagnostic trap installer",
                nativeCore.contains("native void installTerminationTrapSeccompShield()"));
        assertTrue("BoxCore should register the diagnostic trap JNI bridge",
                boxCore.contains("{\"installTerminationTrapSeccompShield\"")
                        && boxCore.contains("blackbox::seccomp::installTerminationTrapSeccompShield()"));
    }

    @Test
    public void terminationTrapFilterTrapsRawTerminationSyscallsAndLogsPcLrFrames() throws Exception {
        String seccomp = readSource(
                "src/main/cpp/SeccompShield.cpp",
                "Bcore/src/main/cpp/SeccompShield.cpp");

        assertTrue("SeccompShield should implement a separate diagnostic trap installer",
                seccomp.contains("void installTerminationTrapSeccompShield()")
                        && seccomp.contains("installTerminationTrapFilter"));
        assertTrue("The diagnostic trap should have its own one-time state",
                seccomp.contains("gTerminationTrapSeccompInstalled"));

        String protectedSyscalls = sliceBetween(seccomp,
                "static bool isProtectedSyscall",
                "static void initializeVirtualSigsysAction");
        assertTrue("The SIGSYS handler should treat exit as a protected diagnostic syscall",
                protectedSyscalls.contains("kSysExit"));
        assertTrue("The SIGSYS handler should treat exit_group as a protected diagnostic syscall",
                protectedSyscalls.contains("kSysExitGroup"));

        String trapFilter = sliceBetween(seccomp,
                "static bool installTerminationTrapFilter()",
                "void installSeccompShield()");
        assertTrue("The trap filter should trap raw exit syscalls",
                trapFilter.contains("BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysExit")
                        && trapFilter.contains("kSeccompReturnTrap"));
        assertTrue("The trap filter should trap raw exit_group syscalls",
                trapFilter.contains("BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysExitGroup")
                        && trapFilter.contains("kSeccompReturnTrap"));
        assertFatalSignalTrapBlock("kill", trapFilter, "kSysKill", "args[1]");
        assertFatalSignalTrapBlock("tkill", trapFilter, "kSysTkill", "args[1]");
        assertFatalSignalTrapBlock("tgkill", trapFilter, "kSysTgkill", "args[2]");
        assertFatalSignalTrapBlock("rt_sigqueueinfo", trapFilter, "kSysRtSigqueueinfo", "args[1]");
        assertFatalSignalTrapBlock("rt_tgsigqueueinfo", trapFilter, "kSysRtTgsigqueueinfo", "args[2]");

        String logTrap = sliceBetween(seccomp,
                "static void logTrapEvent",
                "static void resetTrapEvent");
        assertTrue("Trap logging should include pc/lr/fp for IDA correlation",
                logTrap.contains("pc=%p") && logTrap.contains("lr=%p") && logTrap.contains("fp=%p"));
        assertTrue("Trap logging should include raw unwind frames",
                logTrap.contains("\"  frame[%u]=%p\""));
        assertTrue("Returning from a trapped bionic exit syscall must not fall through into the fatal trap after the syscall instruction",
                seccomp.contains("isProcessExitSyscall")
                        && seccomp.contains("emulateBlockedProcessExitReturn")
                        && seccomp.contains("context->uc_mcontext.regs[30]")
                        && seccomp.contains("context->uc_mcontext.arm_lr"));

        String installer = sliceBetween(seccomp,
                "void installTerminationTrapSeccompShield()",
                "void setVirtualUid");
        assertTrue("The diagnostic trap installer must install the SIGSYS handler for stack capture",
                installer.contains("installSignalHandler()"));
        assertTrue("The diagnostic trap installer must install the trap BPF program",
                installer.contains("installTerminationTrapFilter()"));
    }

    @Test
    public void applicationAttachProxyKeepsTerminationTrapAsExplicitGenericDiagnostic() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/ApplicationAttachSeccompProxy.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/ApplicationAttachSeccompProxy.java");

        assertTrue("Application.attach termination trap must be explicit opt-in only",
                source.contains("BLACKBOX_ATTACH_TERMINATION_TRAP")
                        && source.contains("blackbox.attach_termination_trap")
                        && source.contains("debug.blackbox.attach_termination_trap")
                        && source.contains("isAttachTerminationTrapEnabled()"));
        assertTrue("The diagnostic trap should install after Application.attach, not before bootstrap",
                source.contains("afterCall")
                        && source.indexOf("NativeCore.installTerminationTrapSeccompShield()")
                        > source.indexOf("isAttachTerminationTrapEnabled()"));
        assertTrue("The diagnostic trap should record an attach-boundary binder monitor event",
                source.contains("termination trap seccomp shield installed after attach")
                        && source.contains("BlackBoxBinderMonitor.recordProxyCall"));
        assertFalse("The diagnostic trap must not be scoped to the target malware package",
                source.contains("com.bestv")
                        || source.contains("entryRunApplication"));
    }

    private static void assertFatalSignalTrapBlock(String name, String source, String syscallName,
                                                  String signalArg) {
        int start = source.indexOf("BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, " + syscallName);
        assertTrue(name + " block should exist", start >= 0);
        int end = source.indexOf("BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSys", start + 1);
        if (end < 0) {
            end = source.length();
        }
        String block = source.substring(start, end);
        assertTrue(name + " should inspect its signal argument before trapping",
                block.contains("offsetof(struct seccomp_data, " + signalArg + ")"));
        assertTrue(name + " should trap fatal signal delivery for diagnostics",
                block.contains("SIGKILL")
                        && block.contains("SIGTERM")
                        && block.contains("SIGABRT")
                        && block.contains("kSeccompReturnTrap"));
        assertTrue(name + " should still allow non-termination signals",
                block.contains("SECCOMP_RET_ALLOW"));
    }

}
