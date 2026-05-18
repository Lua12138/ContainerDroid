package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class ActivityThreadIdentityProxySourceTest {

    @Test
    public void activityThreadIdentityProxyVirtualizesStaticIdentityMethods() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/ActivityThreadIdentityProxy.java");

        assertTrue(source.contains("ACTIVITY_THREAD_CLASS = \"android.app.ActivityThread\""));
        assertTrue(source.contains("Class.forName(ACTIVITY_THREAD_CLASS)"));
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
}
