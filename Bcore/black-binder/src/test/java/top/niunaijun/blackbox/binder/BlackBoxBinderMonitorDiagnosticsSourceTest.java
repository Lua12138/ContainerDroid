package top.niunaijun.blackbox.binder;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.binder.SourceAssertions.readSource;

public class BlackBoxBinderMonitorDiagnosticsSourceTest {

    @Test
    public void startupLogsExposeConfigAndHookInstallState() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/binder/BlackBoxBinderMonitor.java",
                "Bcore/black-binder/src/main/java/top/niunaijun/blackbox/binder/BlackBoxBinderMonitor.java");

        assertTrue(source.contains("Binder monitor init:"));
        assertTrue(source.contains("enabled="));
        assertTrue(source.contains("recordProxy="));
        assertTrue(source.contains("recordNative="));
        assertTrue(source.contains("recordIoctl="));
        assertTrue(source.contains("hooksInstalled="));
        assertTrue(source.contains("writeInterfaceToken="));
        assertTrue(source.contains("binderProxyTransact="));
    }
}
