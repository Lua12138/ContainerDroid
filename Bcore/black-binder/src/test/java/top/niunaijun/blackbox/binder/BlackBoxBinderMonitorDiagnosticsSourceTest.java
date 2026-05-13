package top.niunaijun.blackbox.binder;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class BlackBoxBinderMonitorDiagnosticsSourceTest {

    @Test
    public void startupLogsExposeConfigAndHookInstallState() throws Exception {
        String source = readMonitorSource();

        assertTrue(source.contains("Binder monitor init:"));
        assertTrue(source.contains("enabled="));
        assertTrue(source.contains("recordProxy="));
        assertTrue(source.contains("recordNative="));
        assertTrue(source.contains("recordIoctl="));
        assertTrue(source.contains("hooksInstalled="));
        assertTrue(source.contains("writeInterfaceToken="));
        assertTrue(source.contains("binderProxyTransact="));
    }

    private static String readMonitorSource() throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            Path moduleCandidate = dir.resolve(
                    "src/main/java/top/niunaijun/blackbox/binder/BlackBoxBinderMonitor.java");
            if (Files.isRegularFile(moduleCandidate)) {
                return new String(Files.readAllBytes(moduleCandidate), StandardCharsets.UTF_8);
            }
            Path rootCandidate = dir.resolve(
                    "Bcore/black-binder/src/main/java/top/niunaijun/blackbox/binder/BlackBoxBinderMonitor.java");
            if (Files.isRegularFile(rootCandidate)) {
                return new String(Files.readAllBytes(rootCandidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("BlackBoxBinderMonitor.java not found from " + current);
    }
}
