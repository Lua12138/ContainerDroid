package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

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
