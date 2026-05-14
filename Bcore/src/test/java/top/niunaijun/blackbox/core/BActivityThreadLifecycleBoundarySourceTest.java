package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

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
