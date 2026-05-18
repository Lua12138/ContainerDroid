package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class InstallToDeviceScriptSourceTest {

    @Test
    public void installScriptAllowsDeviceOverrideAndSingleConnectedDeviceFallback() throws Exception {
        String source = readSource("script/install-to-device.sh");

        assertTrue("install script should keep the known wireless device only as a default",
                source.contains("DEFAULT_DEVICE="));
        assertTrue("install script should allow DEVICE env override",
                source.contains("DEVICE=${DEVICE:-}"));
        assertTrue("install script should fall back to the single connected adb device",
                source.contains("CONNECTED_DEVICE=")
                        && source.contains("adb devices")
                        && source.contains("$CONNECTED_DEVICE"));
        assertFalse("install script must not force a hard-coded DEVICE assignment",
                source.contains("\nDEVICE=adb-"));
    }

    @Test
    public void installScriptClearsStalePublicArtifactsBeforeDeviceWork() throws Exception {
        String source = readSource("script/install-to-device.sh");

        assertTrue("install script should remove stale /tmp/logcat.log before running adb",
                source.contains("rm -f /tmp/logcat.log /tmp/screencap.png"));
        assertTrue("artifact cleanup must happen before the first adb command",
                source.indexOf("rm -f /tmp/logcat.log /tmp/screencap.png")
                        < source.indexOf("adb -s \"$DEVICE\""));
    }

    @Test
    public void codexScriptAllowsDeviceOverrideAndSingleConnectedDeviceFallback() throws Exception {
        String source = readSource("script/codex.sh");

        assertTrue("codex acceptance script should keep its known wireless device only as a default",
                source.contains("DEFAULT_DEVICE="));
        assertTrue("codex acceptance script should allow DEVICE env override",
                source.contains("DEVICE=${DEVICE:-}"));
        assertTrue("codex acceptance script should fall back to the single connected adb device",
                source.contains("CONNECTED_DEVICE=")
                        && source.contains("adb devices")
                        && source.contains("$CONNECTED_DEVICE"));
        assertFalse("codex acceptance script must not force a hard-coded DEVICE default in the variable expansion",
                source.contains("DEVICE=${DEVICE:-adb-"));
    }

    @Test
    public void codexScriptAllowsTestPackageEnvironmentOverride() throws Exception {
        String source = readSource("script/codex.sh");

        assertTrue("codex script should allow TEST_PACKAGE env override for BestV/tester runs",
                source.contains("TEST_PACKAGE=${TEST_PACKAGE:-}"));
        assertTrue("codex script should read docs/test_package_name only when TEST_PACKAGE is empty",
                source.contains("if [ -z \"$TEST_PACKAGE\" ]; then")
                        && source.indexOf("TEST_PACKAGE=${TEST_PACKAGE:-}")
                        < source.indexOf("TEST_PACKAGE=$(tr -d '\\r' < \"$TEST_PACKAGE_FILE\""));
    }

    @Test
    public void codexScriptCanCollectBothRequiredPackagesWithSeparateArtifacts() throws Exception {
        String source = readSource("script/codex.sh");

        assertTrue("codex script should expose one command for both required package checks",
                source.contains("collect-required-packages"));
        assertTrue("codex script should include BestV and tester package names",
                source.contains("com.bestv.tv.video.iqy.tjdx")
                        && source.contains("com.example.tester"));
        assertTrue("dual-package collection should isolate artifact paths by package role",
                source.contains("/tmp/blackbox_bestv")
                        && source.contains("/tmp/blackbox_tester")
                        && source.contains("LOG_FILE=\"${artifact_prefix}_logcat.txt\"")
                        && source.contains("SCREENSHOT_FILE=\"${artifact_prefix}_screenshot.png\""));
    }

    @Test
    public void acceptanceScriptsShareTheSameKnownDefaultDevice() throws Exception {
        String installSource = readSource("script/install-to-device.sh");
        String codexSource = readSource("script/codex.sh");

        String defaultDevice = extractDefaultDevice(installSource);
        assertTrue("acceptance scripts should use the same known wireless default device",
                codexSource.contains(defaultDevice));
    }

    @Test
    public void codexAcceptanceCheckFailsWhenBestvDiedVetoAppearsInLogcat() throws Exception {
        Path root = findSourceRoot();
        Path temp = Files.createTempDirectory("blackbox-codex-acceptance");
        Path manifest = temp.resolve("manifest.txt");
        Path log = temp.resolve("logcat.txt");
        Path exitInfo = temp.resolve("exit-info.txt");
        Path getprop = temp.resolve("getprop.txt");
        Path realLog = temp.resolve("real-logcat.txt");
        Path realExitInfo = temp.resolve("real-exit-info.txt");
        Path realGetprop = temp.resolve("real-getprop.txt");
        Path screenshot = temp.resolve("screenshot.png");
        Path realScreenshot = temp.resolve("real-screenshot.png");

        Files.write(manifest, "status=success\nfailed_stage=none\n".getBytes(StandardCharsets.UTF_8));
        Files.write(log, (
                "05-17 04:00:00.000  1111  2222 I BProcessManager: App Died: "
                        + "com.bestv.tv.video.iqy.tjdx\n").getBytes(StandardCharsets.UTF_8));
        Files.write(exitInfo, "exit info\n".getBytes(StandardCharsets.UTF_8));
        Files.write(getprop, "props\n".getBytes(StandardCharsets.UTF_8));
        Files.write(realLog, "real log\n".getBytes(StandardCharsets.UTF_8));
        Files.write(realExitInfo, "real exit info\n".getBytes(StandardCharsets.UTF_8));
        Files.write(realGetprop, "real props\n".getBytes(StandardCharsets.UTF_8));
        Files.write(screenshot, "same screenshot\n".getBytes(StandardCharsets.UTF_8));
        Files.write(realScreenshot, "same screenshot\n".getBytes(StandardCharsets.UTF_8));

        ProcessBuilder builder = new ProcessBuilder("bash", "script/codex.sh", "acceptance-check");
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("ARTIFACT_MANIFEST_FILE", manifest.toString());
        env.put("LOG_FILE", log.toString());
        env.put("EXIT_INFO_FILE", exitInfo.toString());
        env.put("GETPROP_FILE", getprop.toString());
        env.put("REAL_LOG_FILE", realLog.toString());
        env.put("REAL_EXIT_INFO_FILE", realExitInfo.toString());
        env.put("REAL_GETPROP_FILE", realGetprop.toString());
        env.put("SCREENSHOT_FILE", screenshot.toString());
        env.put("REAL_SCREENSHOT_FILE", realScreenshot.toString());
        env.put("ARTIFACT_MAX_AGE_MINUTES", "1000000");

        Process process = builder.start();
        assertTrue("acceptance-check should finish", process.waitFor(15, TimeUnit.SECONDS));
        String output = readFully(process.getInputStream());

        assertNotEquals("BestV App Died logcat veto must fail acceptance", 0, process.exitValue());
        assertTrue(output, output.contains("veto_status=failed"));
        assertTrue(output, output.contains("acceptance_status=failed_veto"));
    }

    @Test
    public void codexAcceptanceCheckFailsWhenSandboxScreenshotDiffersFromRealDeviceScreenshot() throws Exception {
        Path root = findSourceRoot();
        Path temp = Files.createTempDirectory("blackbox-codex-screenshot");
        Path manifest = temp.resolve("manifest.txt");
        Path log = temp.resolve("logcat.txt");
        Path exitInfo = temp.resolve("exit-info.txt");
        Path getprop = temp.resolve("getprop.txt");
        Path realLog = temp.resolve("real-logcat.txt");
        Path realExitInfo = temp.resolve("real-exit-info.txt");
        Path realGetprop = temp.resolve("real-getprop.txt");
        Path screenshot = temp.resolve("screenshot.png");
        Path realScreenshot = temp.resolve("real-screenshot.png");

        Files.write(manifest, "status=success\nfailed_stage=none\n".getBytes(StandardCharsets.UTF_8));
        Files.write(log, "container log\n".getBytes(StandardCharsets.UTF_8));
        Files.write(exitInfo, "exit info\n".getBytes(StandardCharsets.UTF_8));
        Files.write(getprop, "props\n".getBytes(StandardCharsets.UTF_8));
        Files.write(realLog, "real log\n".getBytes(StandardCharsets.UTF_8));
        Files.write(realExitInfo, "real exit info\n".getBytes(StandardCharsets.UTF_8));
        Files.write(realGetprop, "real props\n".getBytes(StandardCharsets.UTF_8));
        Files.write(screenshot, "sandbox screenshot\n".getBytes(StandardCharsets.UTF_8));
        Files.write(realScreenshot, "real screenshot\n".getBytes(StandardCharsets.UTF_8));

        ProcessBuilder builder = new ProcessBuilder("bash", "script/codex.sh", "acceptance-check");
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("ARTIFACT_MANIFEST_FILE", manifest.toString());
        env.put("LOG_FILE", log.toString());
        env.put("EXIT_INFO_FILE", exitInfo.toString());
        env.put("GETPROP_FILE", getprop.toString());
        env.put("REAL_LOG_FILE", realLog.toString());
        env.put("REAL_EXIT_INFO_FILE", realExitInfo.toString());
        env.put("REAL_GETPROP_FILE", realGetprop.toString());
        env.put("SCREENSHOT_FILE", screenshot.toString());
        env.put("REAL_SCREENSHOT_FILE", realScreenshot.toString());
        env.put("ARTIFACT_MAX_AGE_MINUTES", "1000000");

        Process process = builder.start();
        assertTrue("acceptance-check should finish", process.waitFor(15, TimeUnit.SECONDS));
        String output = readFully(process.getInputStream());

        assertNotEquals("screenshot mismatch must fail acceptance", 0, process.exitValue());
        assertTrue(output, output.contains("screenshot_status=failed"));
        assertTrue(output, output.contains("acceptance_status=failed_screenshot"));
    }

    private static Path findSourceRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("script/codex.sh");
            if (Files.isRegularFile(candidate)) {
                return dir;
            }
        }
        throw new AssertionError("source root not found from " + current);
    }

    private static String readFully(InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String extractDefaultDevice(String source) {
        for (String line : source.split("\\R")) {
            if (line.startsWith("DEFAULT_DEVICE=")) {
                String value = line.substring("DEFAULT_DEVICE=".length());
                if (value.startsWith("${DEFAULT_DEVICE:-") && value.endsWith("}")) {
                    return value.substring("${DEFAULT_DEVICE:-".length(), value.length() - 1);
                }
                return value;
            }
        }
        throw new AssertionError("DEFAULT_DEVICE assignment not found");
    }
}
