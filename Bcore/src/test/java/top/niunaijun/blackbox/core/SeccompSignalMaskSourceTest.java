package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class SeccompSignalMaskSourceTest {

    @Test
    public void seccompFilterDoesNotTrapRawRtSigprocmaskFromArtSignalHandlers() throws Exception {
        String source = readSource(
                "src/main/cpp/SeccompShield.cpp",
                "Bcore/src/main/cpp/SeccompShield.cpp");

        assertFalse("Raw rt_sigprocmask must not be seccomp-trapped: ART calls it from SIGSEGV handlers and a nested SIGSYS kills the process",
                source.contains("BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysRtSigprocmask"));
        assertFalse("The SIGSYS handler should not contain a raw rt_sigprocmask emulation branch",
                source.contains("isSignalMaskSyscall(sysno)"));
        assertTrue("PLT-level signal-mask wrappers should remain for ordinary libc callers",
                source.contains("int sigprocmask(int how, const sigset_t *set, sigset_t *oldset)")
                        && source.contains("int pthread_sigmask(int how, const sigset_t *set, sigset_t *oldset)")
                        && source.contains("int rt_sigprocmask(int how, const sigset_t *set, sigset_t *oldset, size_t sigsetsize)")
                        && source.contains("sanitizeSignalMaskForHook"));
    }

    @Test
    public void signalMaskWrappersStillRemoveSigsysAndSigillBeforeForwarding() throws Exception {
        String source = readSource(
                "src/main/cpp/SeccompShield.cpp",
                "Bcore/src/main/cpp/SeccompShield.cpp");

        assertTrue("Signal-mask wrapper path should remove SIGSYS/SIGILL from incoming masks",
                source.contains("sanitizeSignalMaskCopy(set, sanitized)"));
        assertTrue("The wrapper path should forward sanitized masks through the real libc entrypoints",
                source.contains("const sigset_t *forward = blackbox::seccomp::sanitizeSignalMaskForHook(\"sigprocmask\"")
                        && source.contains("const sigset_t *forward = blackbox::seccomp::sanitizeSignalMaskForHook(\"pthread_sigmask\"")
                        && source.contains("const sigset_t *forward = blackbox::seccomp::sanitizeSignalMaskForHook(\"rt_sigprocmask\""));
    }
}
