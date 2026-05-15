package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RuntimeExecProxySourceTest {

    @Test
    public void procMountsDynamicReplayIsDiagnosticOptInByDefault() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/RuntimeExecProxy.java");
        String runtimeBeforeCall = sliceBetween(source,
                "public void beforeCall(Pine.CallFrame callFrame) {",
                "public void afterCall(Pine.CallFrame callFrame) {");
        String processBuilderBeforeCall = sliceBetween(source,
                "private void hookProcessBuilderStart",
                "public void afterCall(Pine.CallFrame callFrame) {");
        String mountsBuilder = sliceBetween(source,
                "private static String buildSanitizedProcMounts() {",
                "private static String buildDefaultProcMounts() {");

        assertTrue("Runtime.exec(cat /proc/mounts) should use the centralized proc-mount response builder",
                runtimeBeforeCall.contains("new StaticProcess(buildSanitizedProcMounts())"));
        assertTrue("ProcessBuilder.start(cat /proc/mounts) should use the same centralized proc-mount response builder",
                processBuilderBeforeCall.contains("new StaticProcess(buildSanitizedProcMounts())"));
        assertTrue("Full dynamic /proc/mounts replay should not be the default path after BestV early-kill regression",
                source.contains("buildDefaultProcMounts")
                        && mountsBuilder.contains("if (!isDynamicProcMountsEnabled())")
                        && mountsBuilder.contains("return buildDefaultProcMounts();"));
        assertTrue("Dynamic /proc/mounts replay should stay available only behind explicit diagnostic switches",
                source.contains("DYNAMIC_PROC_MOUNTS_ENV")
                        && source.contains("BLACKBOX_DYNAMIC_PROC_MOUNTS")
                        && source.contains("DYNAMIC_PROC_MOUNTS_PROPERTY")
                        && source.contains("debug.blackbox.dynamic_mounts")
                        && source.contains("SystemPropertiesCompat.get(DYNAMIC_PROC_MOUNTS_PROPERTY)"));
        assertTrue("The diagnostic dynamic path should still read the real proc mount table from the current Android mount namespace",
                source.contains("readProcMounts")
                        && source.contains("new FileInputStream(\"/proc/mounts\")"));
        assertTrue("The diagnostic dynamic path should sanitize host sandbox package paths from the returned mount table",
                source.contains("sanitizeProcMounts")
                        && source.contains("shouldDropProcMountLine")
                        && source.contains("BlackBoxCore.getContext().getPackageName()"));
        assertFalse("proc-mount simulation must not be target-package gated",
                source.contains("com.bestv") || source.contains("BestV"));
        assertFalse("the previous static sanitized mount table name should not remain as the active strategy",
                source.contains("SANITIZED_PROC_MOUNTS"));
    }

    @Test
    public void procMountsDefaultTemplateIsCompactRichAndSandboxNeutral() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/RuntimeExecProxy.java");
        String linesBlock = sliceBetween(source,
                "private static final String[] DEFAULT_PROC_MOUNTS_LINES = new String[] {",
                "};");
        List<String> lines = extractJavaStringLiterals(linesBlock);
        StringBuilder mounts = new StringBuilder();
        for (String line : lines) {
            mounts.append(line).append('\n');
        }
        String defaultMounts = mounts.toString();
        String lower = defaultMounts.toLowerCase();

        assertTrue("default Runtime.exec cat /proc/mounts output should stay above simple inventory-size detectors without replaying a high-cardinality table",
                defaultMounts.getBytes(StandardCharsets.UTF_8).length >= 4096
                        && defaultMounts.getBytes(StandardCharsets.UTF_8).length <= 6144);
        assertTrue("default proc-mount template should contain common Android root/proc/dev/sys entries",
                defaultMounts.contains("/dev/block/dm-3 / ext4")
                        && defaultMounts.contains("proc /proc proc")
                        && defaultMounts.contains("tmpfs /dev tmpfs")
                        && defaultMounts.contains("sysfs /sys sysfs"));
        assertTrue("default proc-mount template should contain common Android data/storage entries without app-specific bind mounts",
                defaultMounts.contains(" /data ")
                        && defaultMounts.contains("/storage/emulated")
                        && defaultMounts.contains("/mnt/runtime/default/emulated"));
        assertTrue("default proc-mount template should preserve direct-like rich mount options on a compact line set",
                defaultMounts.contains("background_gc=on")
                        && defaultMounts.contains("inlinecrypt")
                        && defaultMounts.contains("derive_gid")
                        && defaultMounts.contains("unshared_obb"));
        assertTrue("default proc-mount template should stay low-cardinality after BestV high-cardinality regression",
                lines.size() >= 8 && lines.size() <= 12);
        assertFalse("broad system inventory should remain diagnostic-only, not the default Runtime.exec mount surface",
                defaultMounts.contains("/apex/")
                        || defaultMounts.contains(" /dev/binderfs ")
                        || defaultMounts.contains(" /sys/fs/bpf ")
                        || defaultMounts.contains(" cgroup "));
        assertFalse("default proc-mount template must not expose sandbox, target, root, or debug transport artifacts",
                lower.contains("blackbox")
                        || lower.contains("top.niunaijun")
                        || lower.contains("com.bestv")
                        || lower.contains("magisk")
                        || lower.contains("/debug_ramdisk")
                        || lower.contains(" /dev/usb-ffs/adb "));
        assertFalse("proc-mount simulation must not be target-package gated",
                source.contains("com.bestv") || source.contains("BestV"));
    }

    @Test
    public void getpropNoArgCommandUsesInProcessSanitizedStaticProcess() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/RuntimeExecProxy.java");
        String runtimeBeforeCall = sliceBetween(source,
                "public void beforeCall(Pine.CallFrame callFrame) {",
                "public void afterCall(Pine.CallFrame callFrame) {");
        String processBuilderBeforeCall = sliceBetween(source,
                "private void hookProcessBuilderStart",
                "public void afterCall(Pine.CallFrame callFrame) {");

        assertTrue("Runtime.exec(getprop) should avoid spawning a visible child process in sandbox apps",
                runtimeBeforeCall.contains("shouldReturnSanitizedGetprop(callFrame.args)")
                        && runtimeBeforeCall.contains("new StaticProcess(buildSanitizedGetprop())"));
        assertTrue("ProcessBuilder.start(getprop) should use the same in-process sanitized response",
                processBuilderBeforeCall.contains("shouldReturnSanitizedGetprop(commandList)")
                        && processBuilderBeforeCall.contains("new StaticProcess(buildSanitizedGetprop())"));
        assertTrue("getprop command matching should cover no-arg getprop forms only",
                source.contains("isGetpropCommand")
                        && source.contains("\"getprop\"")
                        && source.contains("\"/system/bin/getprop\""));
        assertTrue("sanitized getprop output should be built in-process from known SystemProperties keys",
                source.contains("buildSanitizedGetprop")
                        && source.contains("DEFAULT_GETPROP_KEYS")
                        && source.contains("SystemPropertiesCompat.get(key)"));
        assertFalse("sanitized getprop must not spawn a real /system/bin/getprop child inside the sandbox app",
                source.contains("readFullGetprop")
                        || source.contains("new ProcessBuilder(\"/system/bin/getprop\")")
                        || source.contains("sInternalExec"));
        assertTrue("sanitized getprop should omit empty/missing keys to match no-arg getprop output",
                source.contains("if (value == null || value.length() == 0)")
                        && source.contains("continue;"));
        assertTrue("known-key getprop output must drop sandbox instrumentation values and host package traces",
                source.contains("sanitizeGetpropValue")
                        && source.contains("getHostPackageName()"));
        assertFalse("getprop simulation must not be target-package gated",
                source.contains("com.bestv") || source.contains("BestV"));
        assertFalse("getprop simulation must not call Runtime.exec recursively",
                source.contains("exec(\"getprop\")"));
    }

    @Test
    public void runtimeExecIdentityCommandsUseVirtualInProcessStaticProcess() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/RuntimeExecProxy.java");
        String runtimeBeforeCall = sliceBetween(source,
                "public void beforeCall(Pine.CallFrame callFrame) {",
                "public void afterCall(Pine.CallFrame callFrame) {");
        String processBuilderBeforeCall = sliceBetween(source,
                "private void hookProcessBuilderStart",
                "public void afterCall(Pine.CallFrame callFrame) {");

        assertTrue("Runtime.exec(id) should avoid spawning a host-uid child visible to sandbox apps",
                runtimeBeforeCall.contains("shouldReturnSanitizedId(callFrame.args)")
                        && runtimeBeforeCall.contains("new StaticProcess(buildSanitizedId())"));
        assertTrue("ProcessBuilder.start(id) should use the same virtual identity response",
                processBuilderBeforeCall.contains("shouldReturnSanitizedId(commandList)")
                        && processBuilderBeforeCall.contains("new StaticProcess(buildSanitizedId())"));
        assertTrue("Runtime.exec(cat /proc/self/status) should avoid exposing host uid/gid from a real cat child",
                runtimeBeforeCall.contains("shouldReturnSanitizedProcSelfStatus(callFrame.args)")
                        && runtimeBeforeCall.contains("new StaticProcess(buildSanitizedProcSelfStatus())"));
        assertTrue("ProcessBuilder.start(cat /proc/self/status) should use the same virtual proc status response",
                processBuilderBeforeCall.contains("shouldReturnSanitizedProcSelfStatus(commandList)")
                        && processBuilderBeforeCall.contains("new StaticProcess(buildSanitizedProcSelfStatus())"));
        assertTrue("Sanitized identity must derive from BActivityThread virtual uid, not target package names",
                source.contains("BActivityThread.getBUid()")
                        && source.contains("BUserHandle.getAppId"));
        assertTrue("The identity output should synthesize Android app-style uid, gids, and SELinux categories",
                source.contains("buildAndroidAppName")
                        && source.contains("buildVirtualGroups")
                        && source.contains("buildVirtualSelinuxContext"));
        assertFalse("identity command simulation must not be BestV-targeted",
                source.contains("com.bestv") || source.contains("BestV"));
    }

    @Test
    public void sanitizedGetpropCoversCommonEnvironmentIntegrityKeys() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/RuntimeExecProxy.java");
        String keys = sliceBetween(source,
                "private static final String[] DEFAULT_GETPROP_KEYS = new String[] {",
                "};");
        List<String> keyList = extractJavaStringLiterals(keys);

        assertTrue("getprop output should include the same build fingerprint visible through SystemProperties",
                keys.contains("\"ro.build.fingerprint\""));
        assertTrue("getprop output should include common bootloader integrity state keys",
                keys.contains("\"ro.boot.verifiedbootstate\"")
                        && keys.contains("\"ro.boot.flash.locked\""));
        assertTrue("getprop output should include common secure/debuggable flags",
                keys.contains("\"ro.secure\"")
                        && keys.contains("\"ro.debuggable\""));
        assertTrue("getprop output should include platform and qemu markers so absence matches the host system",
                keys.contains("\"ro.board.platform\"")
                        && keys.contains("\"ro.kernel.qemu\""));
        assertTrue("in-process getprop inventory should stay rich enough for no-arg getprop environment checks",
                keyList.size() >= 120);
        assertFalse("in-process getprop must not query SELinux-protected serial number properties from the app process",
                keyList.contains("ro.serialno")
                        || keyList.contains("ro.boot.serialno"));
        assertFalse("in-process getprop must not query vendor-private Wi-Fi MAC properties that emit app-context AVC noise",
                keyList.contains("persist.vendor.wifi.mac"));
    }

    @Test
    public void staticProcessLifecycleTracingIsExplicitDiagnosticOnly() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/RuntimeExecProxy.java");
        String staticProcess = sliceBetween(source,
                "private static final class StaticProcess extends Process {",
                "\n    }\n}");

        assertTrue("StaticProcess stream lifecycle tracing should be available only behind explicit diagnostic switches",
                source.contains("STATIC_PROCESS_TRACE_ENV")
                        && source.contains("BLACKBOX_STATIC_PROCESS_TRACE")
                        && source.contains("STATIC_PROCESS_TRACE_JAVA_PROPERTY")
                        && source.contains("blackbox.static_process_trace")
                        && source.contains("STATIC_PROCESS_TRACE_PROPERTY")
                        && source.contains("debug.blackbox.static_process_trace"));
        assertTrue("StaticProcess diagnostic must be default-off and property gated",
                source.contains("shouldTraceStaticProcess()")
                        && source.contains("SystemPropertiesCompat.get(STATIC_PROCESS_TRACE_PROPERTY)"));
        assertTrue("StaticProcess should preserve the original ByteArrayInputStream surface when tracing is disabled",
                staticProcess.contains("return new ByteArrayInputStream(stdout);")
                        && staticProcess.contains("return new ByteArrayInputStream(stderr);"));
        assertTrue("StaticProcess should expose stdout EOF/close and process lifecycle observations",
                staticProcess.contains("TracingInputStream")
                        && staticProcess.contains("static process getInputStream")
                        && staticProcess.contains("static process stdout EOF")
                        && staticProcess.contains("static process stdout close")
                        && staticProcess.contains("static process waitFor")
                        && staticProcess.contains("static process destroy"));
        assertFalse("StaticProcess diagnostics must not be target-package gated",
                source.contains("com.bestv") || source.contains("BestV"));
    }

    @Test
    public void broadRuntimeExecTracingIsExplicitButSanitizersRemainDefault() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/RuntimeExecProxy.java");
        String runtimeBeforeCall = sliceBetween(source,
                "public void beforeCall(Pine.CallFrame callFrame) {",
                "public void afterCall(Pine.CallFrame callFrame) {");
        String processBuilderBeforeCall = sliceBetween(source,
                "private void hookProcessBuilderStart",
                "public void afterCall(Pine.CallFrame callFrame) {");

        assertTrue("broad before/after exec telemetry should be gated by explicit env/java/debug properties",
                source.contains("EXEC_TRACE_ENV")
                        && source.contains("BLACKBOX_EXEC_TRACE")
                        && source.contains("EXEC_TRACE_JAVA_PROPERTY")
                        && source.contains("blackbox.exec_trace")
                        && source.contains("EXEC_TRACE_PROPERTY")
                        && source.contains("debug.blackbox.exec_trace")
                        && source.contains("shouldTraceSandboxExec()"));
        assertFalse("sanitized Runtime.exec handling must not return before checking id/getprop/proc probes when broad tracing is disabled",
                runtimeBeforeCall.contains("if (!shouldTraceSandboxExec()) {\n                        return;\n                    }"));
        assertFalse("sanitized ProcessBuilder handling must not return before checking id/getprop/proc probes when broad tracing is disabled",
                processBuilderBeforeCall.contains("if (!shouldTraceSandboxExec()) {\n                        return;\n                    }"));
        assertTrue("unhandled commands should only be logged when broad exec tracing is enabled",
                runtimeBeforeCall.contains("if (shouldTraceSandboxExec())")
                        && processBuilderBeforeCall.contains("if (shouldTraceSandboxExec())"));
    }

    private static String sliceBetween(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start + startNeedle.length());
        assertTrue(startNeedle + " should exist", start >= 0);
        assertTrue(endNeedle + " should exist after " + startNeedle, end > start);
        return source.substring(start, end);
    }

    private static String readSource(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(relativePath + " not found from " + current);
    }

    private static List<String> extractJavaStringLiterals(String source) {
        List<String> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(source);
        while (matcher.find()) {
            values.add(unescapeJavaString(matcher.group(1)));
        }
        return values;
    }

    private static String unescapeJavaString(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!escaped) {
                if (ch == '\\') {
                    escaped = true;
                } else {
                    out.append(ch);
                }
                continue;
            }
            switch (ch) {
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case '\\':
                    out.append('\\');
                    break;
                case '"':
                    out.append('"');
                    break;
                default:
                    out.append(ch);
                    break;
            }
            escaped = false;
        }
        if (escaped) {
            out.append('\\');
        }
        return out.toString();
    }
}
