package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class FrameworkWtfProxySourceTest {
    @Test
    public void looperIdentityWtfIsSuppressedWithoutChangingBinderIdentity() throws Exception {
        String proxy = readSource("Bcore/src/main/java/top/niunaijun/blackbox/fake/service/FrameworkWtfProxy.java");
        String hookManager = readSource("Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java");
        String nativeFileHook = readSource("Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("HookManager should install the framework WTF proxy in sandbox app processes",
                hookManager.contains("new FrameworkWtfProxy()"));
        assertTrue("Only Looper identity mismatch WTFs should be suppressed; other framework/app WTFs must keep original behavior",
                proxy.contains("Class.forName(\"android.util.Log\")")
                        && proxy.contains("getDeclaredMethod(\"wtf\", String.class, String.class)")
                        && proxy.contains("Pine.hook")
                        && proxy.contains("isLooperIdentityWtf")
                        && proxy.contains("\"Looper\".equals(tag)")
                        && proxy.contains("message.startsWith(\"Thread identity changed from\")")
                        && proxy.contains("callFrame.setResult(0)"));
        assertTrue("The mitigation must not expose the host Binder/libc identity to protected apps",
                nativeFileHook.contains("extern \"C\" uid_t getuid()")
                        && nativeFileHook.contains("return isNativeVirtualUidConfigured() ? virtualUid() : callSyscall(fn, number, args)")
                        && !nativeFileHook.contains("shouldUseHostIdentityForNativeCaller"));
    }
}
