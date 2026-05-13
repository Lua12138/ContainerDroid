package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class BActivityThreadBinderMonitorSourceTest {

    @Test
    public void nativeBinderMonitorHonorsEnabledConfigGate() throws Exception {
        String source = readBActivityThreadSource();

        assertTrue(source.contains("binderMonitorConfig.isEnabled() && binderMonitorConfig.isRecordNative()"));
        assertTrue(source.contains("binderMonitorConfig.isEnabled() && binderMonitorConfig.isRecordIoctl()"));
    }

    private static String readBActivityThreadSource() throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            Path moduleCandidate = dir.resolve(
                    "src/main/java/top/niunaijun/blackbox/app/BActivityThread.java");
            if (Files.isRegularFile(moduleCandidate)) {
                return new String(Files.readAllBytes(moduleCandidate), StandardCharsets.UTF_8);
            }
            Path rootCandidate = dir.resolve(
                    "Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java");
            if (Files.isRegularFile(rootCandidate)) {
                return new String(Files.readAllBytes(rootCandidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("BActivityThread.java not found from " + current);
    }
}
