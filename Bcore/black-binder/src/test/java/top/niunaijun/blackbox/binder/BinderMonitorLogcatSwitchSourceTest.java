package top.niunaijun.blackbox.binder;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class BinderMonitorLogcatSwitchSourceTest {

    @Test
    public void binderMonitorLogcatIsCompileTimeAndRuntimeGated() throws Exception {
        String buildGradle = readSource("Bcore/black-binder/build.gradle");
        String config = readSource(
                "Bcore/black-binder/src/main/java/top/niunaijun/blackbox/binder/BinderMonitorConfig.java");
        String monitor = readSource(
                "Bcore/black-binder/src/main/java/top/niunaijun/blackbox/binder/BlackBoxBinderMonitor.java");
        String sink = readSource(
                "Bcore/black-binder/src/main/java/top/niunaijun/blackbox/binder/AsyncJsonlEventSink.java");
        String bActivityThread = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java");

        assertTrue("black-binder build should expose the shared diagnostic logcat compile switch",
                buildGradle.contains("BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED"));
        assertTrue("BinderMonitorConfig should hard-gate logcat with the compile-time switch",
                config.contains("BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED")
                        && config.contains("private static boolean sanitizeLogcat"));
        assertTrue("BinderMonitorConfig should expose a runtime copy with a new logcat value",
                config.contains("public BinderMonitorConfig withLogcat(boolean logcat)"));
        assertTrue("BActivityThread should apply the sandbox diagnostic logcat option to BinderMonitorConfig",
                bActivityThread.contains("binderMonitorConfig.withLogcat(BlackBoxCore.get().isDiagnosticLogcatEnabled())"));
        assertTrue("BlackBoxBinderMonitor should route its own tag logs through log helpers",
                monitor.contains("private static void logInfo")
                        && monitor.contains("private static void logWarn")
                        && monitor.contains("private static boolean shouldLogcat()")
                        && !monitor.contains("Log.i(TAG, \"Binder monitor init:\""));
        assertTrue("The JSONL event sink should not emit BlackBoxBinderMonitor logcat records when logcat is disabled",
                sink.contains("private boolean shouldLogcat()")
                        && sink.contains("BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && shouldLogcat()"));
    }

    private static String readSource(String rootRelativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(rootRelativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(rootRelativePath + " not found from " + current);
    }
}
