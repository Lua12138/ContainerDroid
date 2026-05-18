package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readOptionalSource;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class LateSeccompInstallProxySourceTest {

    @Test
    public void lateSeccompProxyDoesNotUseJavaAndroidLogHooks() throws Exception {
        String source = readOptionalSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/LateSeccompInstallProxy.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/LateSeccompInstallProxy.java");

        if (source == null) {
            return;
        }
        assertFalse("LateSeccompInstallProxy must not hook android.util.Log after Pine log hook failures",
                source.contains("android.util.Log"));
        assertFalse("LateSeccompInstallProxy must not install Pine hooks on hot logging paths",
                source.contains("Pine.hook"));
        assertFalse("LateSeccompInstallProxy must not hook public Log.d",
                source.contains("getDeclaredMethod(\"d\", String.class, String.class)"));
        assertFalse("LateSeccompInstallProxy must not hook hidden println_native",
                source.contains("println_native"));
    }

    @Test
    public void hookManagerDoesNotInstallLateSeccompProxy() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java");

        assertFalse("HookManager must not import the failed Java logging seccomp trigger",
                source.contains("import top.niunaijun.blackbox.fake.service.LateSeccompInstallProxy;"));
        assertFalse("HookManager must not install the failed Java logging seccomp trigger",
                source.contains("new LateSeccompInstallProxy()"));
        assertTrue("RuntimeExitProxy should remain installed",
                source.contains("new RuntimeExitProxy()"));
    }
}
