package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class ActivityThreadIdentityProxySourceTest {

    @Test
    public void activityThreadIdentityProxyVirtualizesStaticIdentityMethods() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/ActivityThreadIdentityProxy.java");

        assertTrue(source.contains("Class.forName(\"android.app.ActivityThread\")"));
        assertTrue(source.contains("hookIdentityMethod(activityThread, \"currentPackageName\")"));
        assertTrue(source.contains("hookIdentityMethod(activityThread, \"currentProcessName\")"));
        assertTrue(source.contains("hookIdentityMethod(activityThread, \"currentOpPackageName\")"));
        assertTrue(source.contains("BActivityThread.getAppPackageName()"));
        assertTrue(source.contains("BActivityThread.getAppProcessName()"));
        assertTrue(source.contains("BlackBoxBinderMonitor.recordProxyCall"));
    }

    @Test
    public void hookManagerRegistersActivityThreadIdentityProxyBeforeServiceHooks() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java");

        assertTrue(source.contains("import top.niunaijun.blackbox.fake.service.ActivityThreadIdentityProxy;"));
        assertTrue(source.indexOf("new ActivityThreadIdentityProxy()")
                < source.indexOf("new IPackageManagerProxy()"));
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
