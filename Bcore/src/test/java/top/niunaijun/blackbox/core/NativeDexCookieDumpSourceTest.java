package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;
import static top.niunaijun.blackbox.core.SourceAssertions.sliceBetween;

public class NativeDexCookieDumpSourceTest {

    @Test
    public void classLoaderDexCookiesAreDumpedWithoutBroadProcessScan() throws Exception {
        String nativeCore = readSource("Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java");
        String activityThread = readSource("Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java");
        String boxCore = readSource("Bcore/src/main/cpp/BoxCore.cpp");

        assertTrue("NativeCore should expose a narrow ART DexFile-cookie dump native",
                nativeCore.contains("dumpDexCookieNative(long cookie, String outputDir)"));
        assertTrue("NativeCore.dumpDex should consume class loader DexFile cookies",
                nativeCore.contains("DexFileCompat.getCookies(classLoader)")
                        && nativeCore.contains("dumpDexCookie(cookie, outputDir)"));

        assertTrue("BActivityThread should schedule the application class loader dump before virtual providers run",
                activityThread.indexOf("scheduleClassLoaderDumpIfEnabled(application.getClassLoader(), packageName")
                        < activityThread.indexOf("installProviders(mInitialApplication"));
        assertTrue("BActivityThread should schedule another dump after Application.onCreate in case the packer swaps dex later",
                activityThread.lastIndexOf("scheduleClassLoaderDumpIfEnabled(application.getClassLoader(), packageName")
                        > activityThread.indexOf("AppInstrumentation.get().callApplicationOnCreate(application)"));
        assertTrue("BActivityThread dump scheduling should honor the sandbox dex dump option",
                activityThread.contains("scheduleClassLoaderDumpIfEnabled")
                        && activityThread.contains("BlackBoxCore.get().isDexDumpEnabled()"));
        assertFalse("BActivityThread must not synchronously dump dex on lifecycle-critical paths",
                activityThread.contains("NativeCore.dumpDex(application.getClassLoader(), packageName)"));

        assertTrue("native dump should validate dex magic/header from ART DexFile memory",
                boxCore.contains("dumpDexCookieNative")
                        && boxCore.contains("isLikelyDexHeader")
                        && boxCore.contains("writeDexCookieFile"));
        assertFalse("dex dump must not perform a broad synchronous /proc/self/mem scan",
                nativeCore.contains("dumpProcessDex")
                        || boxCore.contains("dumpProcessDex")
                        || boxCore.contains("/proc/self/mem"));
        assertFalse("dex dump must stay package-agnostic",
                nativeCore.contains("com.bestv") || boxCore.contains("com.bestv"));
    }

    @Test
    public void dexCookieMemoryProbeReadsMapsThroughRealLibcFopen() throws Exception {
        String boxCore = readSource("Bcore/src/main/cpp/BoxCore.cpp");
        String isReadableMemoryRange = sliceBetween(boxCore,
                "static bool isReadableMemoryRange(",
                "static bool writeDexCookieFile(");

        assertTrue("Dex cookie memory validation should bypass BlackBox's exported fopen hook for internal /proc/self/maps reads",
                boxCore.contains("openRealProcMapsFileForMemoryProbe")
                        && boxCore.contains("dlsym(RTLD_NEXT, \"fopen\")"));
        assertTrue("isReadableMemoryRange should use the real-libc maps helper instead of public fopen to avoid recursive file-probe telemetry",
                isReadableMemoryRange.contains("openRealProcMapsFileForMemoryProbe()")
                        && !isReadableMemoryRange.contains("fopen(\"/proc/self/maps\""));
    }

}
