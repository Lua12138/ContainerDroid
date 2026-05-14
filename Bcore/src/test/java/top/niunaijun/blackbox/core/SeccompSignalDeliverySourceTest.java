package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SeccompSignalDeliverySourceTest {

    @Test
    public void seccompFilterVirtualizesSigsysHandlersWithoutSuppressingSigsysDelivery() throws Exception {
        String source = readSource(
                "src/main/cpp/SeccompShield.cpp",
                "Bcore/src/main/cpp/SeccompShield.cpp");

        assertTrue("SeccompShield should define a no-signal success action for syscalls that only need to be swallowed",
                source.contains("kSeccompReturnSuccess")
                        && source.contains("SECCOMP_RET_ERRNO"));
        assertTrue("SeccompShield should define a trap action for SIGSYS handler virtualization",
                source.contains("kSeccompReturnTrap")
                        && source.contains("SECCOMP_RET_TRAP"));

        String rtSigactionBlock = sliceBetween(source,
                "BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysRtSigaction",
                "BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_prctl");
        assertTrue("rt_sigaction(SIGSYS) should trap so the handler can store the virtual app handler",
                rtSigactionBlock.contains("kSeccompReturnTrap")
                        && rtSigactionBlock.contains("SIGSYS"));
        assertFalse("rt_sigaction(SIGSYS) must not be silently swallowed, or non-seccomp SIGSYS cannot be forwarded",
                rtSigactionBlock.contains("kSeccompReturnSuccess"));
    }

    @Test
    public void seccompFilterOnlySuppressesTerminationSignalDelivery() throws Exception {
        String source = readSource(
                "src/main/cpp/SeccompShield.cpp",
                "Bcore/src/main/cpp/SeccompShield.cpp");

        String killBlock = sliceBetween(source,
                "BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysKill",
                "BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysTkill");
        String tkillBlock = sliceBetween(source,
                "BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysTkill",
                "BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysTgkill");
        String tgkillBlock = sliceBetween(source,
                "BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysTgkill",
                "BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysRtSigqueueinfo");
        String rtSigqueueBlock = sliceBetween(source,
                "BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysRtSigqueueinfo",
                "BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysRtTgsigqueueinfo");
        String rtTgSigqueueBlock = sliceBetween(source,
                "BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysRtTgsigqueueinfo",
                "BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),\n    };");
        String prctlBlock = sliceBetween(source,
                "BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_prctl",
                "BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_seccomp");
        String seccompBlock = sliceBetween(source,
                "BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_seccomp",
                "BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysKill");

        assertTerminationOnlyBlock("kill", killBlock, "args[1]");
        assertTerminationOnlyBlock("tkill", tkillBlock, "args[1]");
        assertTerminationOnlyBlock("tgkill", tgkillBlock, "args[2]");
        assertTerminationOnlyBlock("rt_sigqueueinfo", rtSigqueueBlock, "args[1]");
        assertTerminationOnlyBlock("rt_tgsigqueueinfo", rtTgSigqueueBlock, "args[2]");
        assertTrue("prctl(PR_SET_SECCOMP) should report success without a SIGSYS handler roundtrip", prctlBlock.contains("kSeccompReturnSuccess"));
        assertTrue("seccomp(SECCOMP_SET_MODE_FILTER) should report success without a SIGSYS handler roundtrip", seccompBlock.contains("kSeccompReturnSuccess"));
        assertFalse("Signal-delivery blocks must not suppress SIGSYS; apps and packers commonly use it for user handlers",
                killBlock.contains("SIGSYS")
                        || tkillBlock.contains("SIGSYS")
                        || tgkillBlock.contains("SIGSYS")
                        || rtSigqueueBlock.contains("SIGSYS")
                        || rtTgSigqueueBlock.contains("SIGSYS"));
    }

    @Test
    public void nonSeccompSigsysForwardingIsLoggedBeforeVirtualHandlerRuns() throws Exception {
        String source = readSource(
                "src/main/cpp/SeccompShield.cpp",
                "Bcore/src/main/cpp/SeccompShield.cpp");

        int forward = source.indexOf("static void forwardVirtualSigsys");
        int log = source.indexOf("non-seccomp SIGSYS forwarding", forward);
        int siginfoHandler = source.indexOf("action.sa_sigaction(signo, info, context_raw)", forward);
        int handler = source.indexOf("action.sa_handler(signo)", forward);

        assertTrue("forwardVirtualSigsys should exist", forward >= 0);
        assertTrue("Non-seccomp SIGSYS forwarding should be logged with virtual handler metadata",
                log > forward);
        assertTrue("Forwarding log must happen before invoking a virtual SA_SIGINFO handler",
                siginfoHandler > log);
        assertTrue("Forwarding log must happen before invoking a virtual one-argument handler",
                handler > log);
    }

    private static String sliceBetween(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start + startNeedle.length());
        assertTrue(startNeedle + " should exist", start >= 0);
        assertTrue(endNeedle + " should exist after " + startNeedle, end > start);
        return source.substring(start, end);
    }

    private static void assertTerminationOnlyBlock(String name, String block, String signalArg) {
        assertTrue(name + " should inspect its signal argument before swallowing",
                block.contains("offsetof(struct seccomp_data, " + signalArg + ")"));
        assertTrue(name + " should swallow SIGKILL without delivering it",
                block.contains("SIGKILL") && block.contains("kSeccompReturnSuccess"));
        assertTrue(name + " should swallow SIGTERM without delivering it",
                block.contains("SIGTERM") && block.contains("kSeccompReturnSuccess"));
        assertTrue(name + " should swallow SIGABRT without delivering it",
                block.contains("SIGABRT") && block.contains("kSeccompReturnSuccess"));
        assertTrue(name + " should allow non-termination signals to reach the app handler",
                block.contains("SECCOMP_RET_ALLOW"));
    }

    private static String readSource(String moduleRelativePath, String rootRelativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            Path moduleCandidate = dir.resolve(moduleRelativePath);
            if (Files.isRegularFile(moduleCandidate)) {
                return new String(Files.readAllBytes(moduleCandidate), StandardCharsets.UTF_8);
            }
            Path rootCandidate = dir.resolve(rootRelativePath);
            if (Files.isRegularFile(rootCandidate)) {
                return new String(Files.readAllBytes(rootCandidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(rootRelativePath + " not found from " + current);
    }
}
