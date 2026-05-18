package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class BActivityThreadSeccompInstallSourceTest {

    @Test
    public void bActivityThreadDoesNotInstallSeccompBeforeApplicationBootstrap() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/app/BActivityThread.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java");

        assertFalse("seccomp shield must not be installed before packer Application.attach returns",
                source.contains("NativeCore.installSeccompShieldIfNeeded()"));
        assertFalse("seccomp install must not be gated to a target package",
                source.contains("installSeccompShieldForPackageIfNeeded"));
    }

    @Test
    public void applicationAttachProxyKeepsSeccompDiagnosticOptInAtGenericAttachBoundary() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/ApplicationAttachSeccompProxy.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/ApplicationAttachSeccompProxy.java");

        assertTrue("proxy should hook AOSP Application.attach(Context) lifecycle boundary",
                source.contains("Application.class.getDeclaredMethod(\"attach\", Context.class)"));
        assertTrue("Application.attach seccomp must be explicit opt-in, not default-on",
                source.contains("BLACKBOX_ATTACH_SECCOMP")
                        && source.contains("blackbox.attach_seccomp")
                        && source.contains("debug.blackbox.attach_seccomp")
                        && source.contains("isAttachSeccompEnabled()"));
        assertTrue("proxy should only install seccomp after Application.attach returns and the explicit gate is true",
                source.contains("afterCall")
                        && source.indexOf("isAttachSeccompEnabled()") >= 0
                        && source.indexOf("NativeCore.installSeccompShield()") > source.indexOf("isAttachSeccompEnabled()"));
        assertFalse("proxy must not use the process-wide diagnostic marker gate at this late lifecycle boundary",
                source.contains("installSeccompShieldIfNeeded"));
        assertTrue("proxy should install only once per process",
                source.contains("AtomicBoolean")
                        && source.contains("compareAndSet(false, true)"));
        String seccompInstallPath = methodBody(source, "maybeInstallAfterAttach")
                + methodBody(source, "maybeInstallTerminationTrapAfterAttach")
                + methodBody(source, "maybeInstallRawSyscallProbeAfterAttach")
                + methodBody(source, "shouldInstallFor");
        assertFalse("proxy must not wait for a target package or application class",
                source.contains("com.bestv") || seccompInstallPath.contains("BActivityThread.getAppPackageName()"));
        assertFalse("proxy must not hook hot android logging methods",
                source.contains("android.util.Log"));
    }

    @Test
    public void hookManagerInstallsApplicationAttachSeccompProxyAfterRuntimeExitHooks() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java");

        int runtimeExit = source.indexOf("new RuntimeExitProxy()");
        int applicationAttach = source.indexOf("new ApplicationAttachSeccompProxy()");

        assertTrue("HookManager should import ApplicationAttachSeccompProxy",
                source.contains("import top.niunaijun.blackbox.fake.service.ApplicationAttachSeccompProxy;"));
        assertTrue("ApplicationAttachSeccompProxy should be installed after runtime exit hooks are available",
                runtimeExit >= 0 && applicationAttach > runtimeExit);
    }
    private static String methodBody(String source, String methodName) {
        String marker = methodName + "(";
        int name = source.indexOf(marker);
        if (name < 0) {
            throw new AssertionError(methodName + " not found");
        }
        int open = source.indexOf('{', name);
        if (open < 0) {
            throw new AssertionError(methodName + " has no body");
        }
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError(methodName + " body not closed");
    }
}
