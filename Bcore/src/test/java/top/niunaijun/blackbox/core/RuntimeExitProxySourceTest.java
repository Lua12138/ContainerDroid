package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class RuntimeExitProxySourceTest {

    @Test
    public void runtimeExitProxyBlocksSandboxAbnormalExitAndRecordsStack() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/RuntimeExitProxy.java");

        assertTrue(source.contains("Class.forName(\"java.lang.System\")"));
        assertTrue(source.contains("Class.forName(\"java.lang.Runtime\")"));
        assertTrue(source.contains("Class.forName(\"android.os.Process\")"));
        assertTrue(source.contains("hookExitMethod(systemClass, \"exit\")"));
        assertTrue(source.contains("hookExitMethod(runtimeClass, \"exit\")"));
        assertTrue(source.contains("hookExitMethod(runtimeClass, \"halt\")"));
        assertTrue(source.contains("hookProcessSignalMethod(processClass, \"killProcess\", int.class)"));
        assertTrue(source.contains("hookProcessSignalMethod(processClass, \"killProcessQuiet\", int.class)"));
        assertTrue(source.contains("hookProcessSignalMethod(processClass, \"sendSignal\", int.class, int.class)"));
        assertTrue(source.contains("hookProcessSignalMethod(processClass, \"sendSignalQuiet\", int.class, int.class)"));
        assertTrue(source.contains("shouldBlockSandboxExit"));
        assertTrue(source.contains("shouldBlockSandboxSignal"));
        assertTrue(source.contains("android.os.Process.myPid()"));
        assertTrue(source.contains("Thread.currentThread().getStackTrace()"));
        assertTrue(source.contains("BlackBoxBinderMonitor.recordProxyCall"));
        assertTrue(source.contains("callFrame.setResult(null)"));
        assertTrue(source.contains("recordBlockedProcessSignal"));
        assertTrue("Process signal hooks should record stack traces even when the signal is not blocked",
                source.contains("recordObservedProcessSignal")
                        && source.contains("observed android.os.Process")
                        && source.contains("shouldRecordSandboxSignal"));
        assertFalse("termination shielding must not be gated to a single package",
                source.contains("BActivityThread.getAppPackageName()")
                        || source.contains("com.bestv"));
    }

    @Test
    public void hookManagerRegistersRuntimeExitProxy() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java");

        assertTrue(source.contains("import top.niunaijun.blackbox.fake.service.RuntimeExitProxy;"));
        assertTrue(source.contains("new RuntimeExitProxy()"));
    }
}
