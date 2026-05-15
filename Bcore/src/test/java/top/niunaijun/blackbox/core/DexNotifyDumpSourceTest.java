package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DexNotifyDumpSourceTest {

    @Test
    public void packageManagerNotifyDexLoadDumpsReportedDexPathsGenerically() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IPackageManagerProxy.java");

        assertTrue("notifyDexLoad and vendor notifyDexLoadWithStatus should be handled generically",
                source.contains("@ProxyMethods({\"notifyDexLoad\", \"notifyDexLoadWithStatus\"})"));
        assertTrue("notifyDexLoad hook should dump paths from classLoaderContextMap/List/String args",
                source.contains("NativeCore.dumpDexPath")
                        && source.contains("collectDexLoadPaths")
                        && source.contains("value instanceof Map")
                        && source.contains("value instanceof Iterable"));
        assertTrue("notifyDexLoad hook should keep forwarding the original package-manager call",
                source.contains("return method.invoke(who, args);"));
        assertFalse("notifyDexLoad dump must not be target-package gated",
                source.contains("com.bestv") || source.contains("BestV"));
    }

    @Test
    public void dexDumpProxyHooksOnlyPublicDexFileApisAfterVirtualAppBind() throws Exception {
        String proxy = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/DexDumpProxy.java");
        String hookManager = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java");

        assertTrue("DexDumpProxy should hook deprecated public DexFile APIs for no-classloader dex loads",
                proxy.contains("hookDexFilePublicApis")
                        && proxy.contains("dalvik.system.DexFile")
                        && proxy.contains("loadDex"));
        assertTrue("DexDumpProxy should dump DexFile cookies instead of only classloader cookies",
                proxy.contains("NativeCore.dumpDexFile"));
        assertFalse("DexDumpProxy must not hook ART native dex open methods after the failed broad-hook attempt",
                proxy.contains("openDexFileNative")
                        || proxy.contains("openInMemoryDexFilesNative"));
        assertFalse("DexDumpProxy must not be globally registered from HookManager",
                hookManager.contains("new DexDumpProxy()"));
    }

    @Test
    public void dexFileLoadDexSeccompShieldIsDiagnosticOptInToAvoidDefaultLoaderSurface() throws Exception {
        String proxy = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/DexDumpProxy.java");
        String loadDexHook = sliceBetween(proxy,
                "if (!\"loadDex\".equals(method.getName()))",
                "private static void maybeInstallSeccompForStandaloneDexLoad");
        String seccompTrigger = sliceBetween(proxy,
                "private static void maybeInstallSeccompForStandaloneDexLoad",
                "private static void recordDexLoadSeccompInstall");
        String beforeCall = sliceBetween(loadDexHook,
                "public void beforeCall(Pine.CallFrame callFrame) {",
                "public void afterCall(Pine.CallFrame callFrame) {");
        String afterCall = sliceBetween(loadDexHook,
                "public void afterCall(Pine.CallFrame callFrame) {",
                "});");

        assertTrue("DexFile.loadDex may still request the pre-load seccomp shield as an explicit diagnostic payload-dump mode",
                beforeCall.contains("maybeInstallSeccompForStandaloneDexLoad(\"dalvik.system.DexFile.loadDex\")"));
        assertTrue("DexFile.loadDex should move path-argument dumping off the protected loader call stack",
                beforeCall.indexOf("scheduleStringPathArgs") >= 0
                        && beforeCall.indexOf("maybeInstallSeccompForStandaloneDexLoad") > beforeCall.indexOf("scheduleStringPathArgs"));
        assertFalse("DexFile.loadDex should not synchronously copy/load path dumps in beforeCall",
                beforeCall.contains("dumpStringPathArgs"));
        assertFalse("DexFile.loadDex afterCall should only dump returned DexFile cookies; after-return seccomp was too late for BestV",
                afterCall.contains("maybeInstallSeccompForStandaloneDexLoad"));
        assertTrue("Deprecated standalone DexFile.loadDex should remain the generic opt-in seccomp trigger",
                proxy.contains("maybeInstallSeccompForStandaloneDexLoad(\"dalvik.system.DexFile.loadDex\")"));
        assertTrue("DexFile.loadDex seccomp must be disabled by default to avoid exposing loader-visible seccomp state",
                proxy.contains("isDexLoadSeccompDiagnosticsEnabled")
                        && seccompTrigger.contains("if (!isDexLoadSeccompDiagnosticsEnabled())")
                        && seccompTrigger.contains("return"));
        assertFalse("DexFile.loadDex default path must not install termination-only seccomp after BestV SIGILL regression",
                seccompTrigger.contains("NativeCore.installTerminationOnlySeccompShield()")
                        || seccompTrigger.contains("termination-only seccomp shield requested"));
        assertTrue("Opt-in diagnostic should be controllable from host/device test tooling",
                proxy.contains("BLACKBOX_DEXLOAD_SECCOMP")
                        && proxy.contains("debug.blackbox.dexload_seccomp"));
        assertTrue("DexFile.loadDex trigger should install the existing syscall shield",
                proxy.contains("NativeCore.installSeccompShield()"));
        assertTrue("DexFile.loadDex should still dump returned DexFile cookies, but only from the async dump worker",
                afterCall.contains("scheduleDexFileDump((DexFile) result"));
        assertFalse("DexFile.loadDex afterCall must not synchronously read DexFile cookies or /proc maps on Jiagu's loader stack",
                afterCall.contains("dumpDexFile((DexFile) result"));
        assertFalse("DexFile.loadDex trigger must not depend on the diagnostic global seccomp marker",
                proxy.contains("NativeCore.installSeccompShieldIfNeeded()"));
        assertTrue("DexFile.loadDex seccomp trigger should be observable in binder-monitor JSONL",
                proxy.contains("recordDexLoadSeccompInstall")
                        && proxy.contains("BlackBoxBinderMonitor.recordProxyCall"));
        assertTrue("DexFile.loadDex diagnostics should say the opt-in shield was requested before the standalone load",
                proxy.contains("seccomp shield requested before"));
        assertFalse("DexFile.loadDex diagnostics must not say after-return seccomp is the active strategy",
                proxy.contains("seccomp shield requested after"));
        assertFalse("DexFile.loadDex seccomp trigger must not be target-package gated",
                proxy.contains("com.bestv") || proxy.contains("BestV"));
    }

    @Test
    public void dexFileLoadDexAsyncDumpDoesNotWaitPastFastRawKillWindow() throws Exception {
        String proxy = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/DexDumpProxy.java");

        assertTrue("DexFile.loadDex dumps must stay off the protected loader call stack",
                proxy.contains("sDexLoadDumpExecutor.schedule(task, DEX_LOAD_DUMP_DELAY_MS, TimeUnit.MILLISECONDS)"));
        assertTrue("DexFile.loadDex async dump worker should run immediately; delayed workers miss payloads when the app raw-kills itself within a few hundred ms",
                proxy.contains("DEX_LOAD_DUMP_DELAY_MS = 0"));
    }

    @Test
    public void applicationAttachSchedulesClassLoaderDumpOffLifecycleStack() throws Exception {
        String attachProxy = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/ApplicationAttachSeccompProxy.java");
        String dexDumpProxy = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/DexDumpProxy.java");
        String afterCall = sliceBetween(attachProxy,
                "public void afterCall(Pine.CallFrame callFrame) {",
                "});");
        String scheduler = sliceBetween(dexDumpProxy,
                "public static void scheduleClassLoaderDump",
                "private static void dumpClassLoader");

        assertTrue("Application.attach should schedule a generic ClassLoader dex dump at the real Application attach boundary",
                afterCall.contains("dumpApplicationClassLoaderAfterAttach(callFrame.thisObject)"));
        assertTrue("Application.attach dump should only use runtime package identity and the receiver class loader",
                attachProxy.contains("BActivityThread.getAppPackageName()")
                        && attachProxy.contains("receiver instanceof Application")
                        && attachProxy.contains("((Application) receiver).getClassLoader()")
                        && attachProxy.contains("DexDumpProxy.scheduleClassLoaderDump"));
        assertFalse("Application.attach hook must not synchronously dump dex on the protected lifecycle stack",
                attachProxy.contains("NativeCore.dumpDex"));
        assertTrue("ClassLoader dump scheduling should reuse the async dex-load worker",
                scheduler.contains("scheduleDexLoadDump(sourceTag")
                        && scheduler.contains("NativeCore.dumpDex(classLoader, packageName)"));
        assertFalse("Application.attach ClassLoader dump must not synthesize or special-case protected-loader placeholders",
                attachProxy.contains("entryRunApplication")
                        || attachProxy.contains("QHClassLoader")
                        || attachProxy.contains("com.bestv")
                        || dexDumpProxy.contains("entryRunApplication")
                        || dexDumpProxy.contains("QHClassLoader")
                        || dexDumpProxy.contains("com.bestv"));
    }

    @Test
    public void terminationOnlySeccompBridgeIsNotUsedByDefaultDexFileLoadDex() throws Exception {
        String proxy = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/DexDumpProxy.java");
        String nativeCore = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java");
        String boxCore = readSource(
                "Bcore/src/main/cpp/BoxCore.cpp");
        String seccomp = readSource(
                "Bcore/src/main/cpp/SeccompShield.cpp");

        assertFalse("Default DexFile.loadDex must not request termination-only seccomp; BestV treats even that as a loader-visible signal",
                proxy.contains("NativeCore.installTerminationOnlySeccompShield()")
                        || proxy.contains("termination-only seccomp shield requested"));
        assertTrue("Full seccomp should remain explicit diagnostic opt-in only",
                proxy.contains("if (!isDexLoadSeccompDiagnosticsEnabled())")
                        && proxy.contains("NativeCore.installSeccompShield()"));
        assertTrue("NativeCore should expose the termination-only seccomp bridge",
                nativeCore.contains("native void installTerminationOnlySeccompShield()"));
        assertTrue("BoxCore should register the termination-only seccomp bridge",
                boxCore.contains("{\"installTerminationOnlySeccompShield\"")
                        && boxCore.contains("blackbox::seccomp::installTerminationOnlySeccompShield()"));
        assertTrue("SeccompShield should implement a separate termination-only installer",
                seccomp.contains("void installTerminationOnlySeccompShield()")
                        && seccomp.contains("installTerminationOnlyFilter"));
        assertFalse("The termination-only installer must not install a SIGSYS handler or watchdog by default",
                sliceBetween(seccomp,
                        "void installTerminationOnlySeccompShield()",
                        "void installTerminationTrapSeccompShield()").contains("installSignalHandler()"));
    }

    private static String sliceBetween(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start + startNeedle.length());
        assertTrue(startNeedle + " should exist", start >= 0);
        assertTrue(endNeedle + " should exist after " + startNeedle, end > start);
        return source.substring(start, end);
    }

    @Test
    public void nativeCoreDumpDexPathRedirectsVirtualDataPathsAndDumpsDexFileCookies() throws Exception {
        String source = readSource("Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java");

        assertTrue("dumpDexPath should try redirected virtual data paths",
                source.contains("dumpDexPathCandidates")
                        && source.contains("IOCore.get().redirectPath"));
        assertTrue("dumpDexPath should skip unrelated framework/WebView containers so diagnostics do not block ordinary app launch",
                source.contains("shouldDumpDexPath")
                        && source.contains("isFrameworkDexPath")
                        && source.contains("packageName"));
        assertTrue("NativeCore should expose a DexFile-specific dump entrypoint",
                source.contains("public static void dumpDexFile(DexFile dexFile, String packageName, String sourceTag)")
                        && source.contains("DexFileCompat.getCookies(dexFile)")
                        && source.contains("dumpDexCookie(cookie, outputDir)"));
        String cookieDump = sliceBetween(source,
                "private static void dumpDexCookie",
                "private static String buildDexDumpName");
        int nativeDump = cookieDump.indexOf("dumpDexCookieNative(cookie, outputDir.getAbsolutePath())");
        int successDedupe = cookieDump.indexOf("DUMPED_DEX_KEYS.add(key)", nativeDump);
        assertTrue("Dex cookie address dedupe should happen only after native dump success so transient failures can retry",
                nativeDump >= 0 && successDedupe > nativeDump);
        assertTrue("Dex cookie native failures should be bounded by a retry counter instead of poisoning the dumped-key set forever",
                source.contains("DEX_COOKIE_MAX_FAILED_ATTEMPTS")
                        && source.contains("DEX_COOKIE_FAILURE_COUNTS")
                        && cookieDump.contains("recordDexCookieFailure(key)"));
        assertFalse("dump path handling must not reintroduce broad process memory scanning",
                source.contains("/proc/self/mem") || source.contains("dumpProcessDex"));
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
}
