package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class SeccompWatchdogSourceTest {

    @Test
    public void sigsysWatchdogIsOptInDiagnosticInsteadOfDefaultLoaderSurface() throws Exception {
        String source = readSource(
                "src/main/cpp/SeccompShield.cpp",
                "Bcore/src/main/cpp/SeccompShield.cpp");

        int installStart = source.indexOf("void installSeccompShield()");
        int installEnd = source.indexOf("void setVirtualUid", installStart);
        assertTrue("installSeccompShield should exist", installStart >= 0);
        assertTrue("setVirtualUid should follow installSeccompShield", installEnd > installStart);
        String installBlock = source.substring(installStart, installEnd);

        assertTrue("The noisy SIGSYS watchdog should remain available only behind an explicit diagnostic gate",
                source.contains("isSigsysWatchdogDiagnosticsEnabled()"));
        assertTrue("installSeccompShield should not start the watchdog unconditionally during protected loader startup",
                installBlock.contains("if (isSigsysWatchdogDiagnosticsEnabled())")
                        && installBlock.indexOf("ensureSigsysWatchdogStarted()")
                        > installBlock.indexOf("if (isSigsysWatchdogDiagnosticsEnabled())"));
        assertTrue("The opt-in gate should be controllable without target-package hardcoding",
                source.contains("BLACKBOX_SECCOMP_WATCHDOG")
                        || source.contains("debug.blackbox.seccomp_watchdog"));
    }
}
