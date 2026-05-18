package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class BinderHookNativeSourceTest {

    @Test
    public void nativeMonitorInstallsExplicitBpBinderTransactHook() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/BinderHook.cpp",
                "Bcore/src/main/cpp/Hook/BinderHook.cpp");

        assertTrue(source.contains("_ZN7android8BpBinder8transactEjRKNS_6ParcelEPS1_j"));
        assertTrue(source.contains("installBpBinderTransactHook"));
        assertTrue(source.contains("Native.BpBinder.transact"));
        assertTrue(source.contains("recordNativeBpBinderTransact"));
        assertTrue(configureBinderMonitorBody(source).contains("installBpBinderTransactHook();"));
    }

    @Test
    public void ioctlMonitorParsesReadAndWriteBinderCommands() throws Exception {
        String source = readSource(
                "src/main/cpp/Hook/BinderHook.cpp",
                "Bcore/src/main/cpp/Hook/BinderHook.cpp");

        assertTrue(source.contains("inspectBinderReadBuffer"));
        assertTrue(source.contains("BR_TRANSACTION"));
        assertTrue(source.contains("BR_REPLY"));
        assertTrue(source.contains("transaction.target.handle"));
        assertTrue(source.contains("driverCommand"));
    }

    private static String configureBinderMonitorBody(String source) {
        String needle = "void BinderHook::configureBinderMonitor";
        int start = source.indexOf(needle);
        if (start < 0) {
            throw new AssertionError("configureBinderMonitor implementation not found");
        }
        return source.substring(start);
    }
}
