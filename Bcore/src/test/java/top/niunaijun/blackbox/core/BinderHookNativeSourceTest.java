package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class BinderHookNativeSourceTest {

    @Test
    public void nativeMonitorInstallsExplicitBpBinderTransactHook() throws Exception {
        String source = readBinderHookSource();

        assertTrue(source.contains("_ZN7android8BpBinder8transactEjRKNS_6ParcelEPS1_j"));
        assertTrue(source.contains("installBpBinderTransactHook"));
        assertTrue(source.contains("Native.BpBinder.transact"));
        assertTrue(source.contains("recordNativeBpBinderTransact"));
        assertTrue(configureBinderMonitorBody(source).contains("installBpBinderTransactHook();"));
    }

    @Test
    public void ioctlMonitorParsesReadAndWriteBinderCommands() throws Exception {
        String source = readBinderHookSource();

        assertTrue(source.contains("inspectBinderReadBuffer"));
        assertTrue(source.contains("BR_TRANSACTION"));
        assertTrue(source.contains("BR_REPLY"));
        assertTrue(source.contains("transaction.target.handle"));
        assertTrue(source.contains("driverCommand"));
    }

    private static String readBinderHookSource() throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            Path moduleCandidate = dir.resolve("src/main/cpp/Hook/BinderHook.cpp");
            if (Files.isRegularFile(moduleCandidate)) {
                return new String(Files.readAllBytes(moduleCandidate), StandardCharsets.UTF_8);
            }
            Path rootCandidate = dir.resolve("Bcore/src/main/cpp/Hook/BinderHook.cpp");
            if (Files.isRegularFile(rootCandidate)) {
                return new String(Files.readAllBytes(rootCandidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("BinderHook.cpp not found from " + current);
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
