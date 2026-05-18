package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class BActivityThreadLifecycleBoundarySourceTest {

    @Test
    public void bindApplicationLogsClassLoaderStateAtLifecycleBoundaries() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/app/BActivityThread.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java");

        assertTrue("makeApplication entry boundary should log loader state",
                source.contains("logApplicationBoundary(\"beforeMakeApplication\""));
        assertTrue("makeApplication return boundary should log loader state",
                source.contains("logApplicationBoundary(\"afterMakeApplication\""));
        assertTrue("provider install entry boundary should log loader state",
                source.contains("logApplicationBoundary(\"beforeInstallProviders\""));
        assertTrue("provider install return boundary should log loader state",
                source.contains("logApplicationBoundary(\"afterInstallProviders\""));
        assertTrue("Application.onCreate entry boundary should log loader state",
                source.contains("logApplicationBoundary(\"beforeApplicationOnCreate\""));
        assertTrue("Application.onCreate return boundary should log loader state",
                source.contains("logApplicationBoundary(\"afterApplicationOnCreate\""));

        assertTrue("boundary log should include context class loader identity",
                source.contains("contextClassLoader="));
        assertTrue("boundary log should include application class loader identity",
                source.contains("applicationClassLoader="));
        assertTrue("boundary log should include LoadedApk class loader identity",
                source.contains("loadedApkClassLoader="));
        assertTrue("boundary log should include current thread context class loader identity",
                source.contains("threadContextClassLoader="));
        assertTrue("boundary log should include LoadedApk application identity",
                source.contains("loadedApkApplication="));
    }
}
