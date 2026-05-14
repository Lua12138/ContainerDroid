package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class ContextDataDirProxySourceTest {

    @Test
    public void contextDataDirProxyVirtualizesJavaContextStorageDirectories() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/ContextDataDirProxy.java");

        assertTrue(source.contains("implements IInjectHook"));
        assertTrue(source.contains("Class.forName(\"android.app.ContextImpl\")"));
        assertTrue(source.contains("\"getDataDir\""));
        assertTrue(source.contains("\"getFilesDir\""));
        assertTrue(source.contains("\"getCacheDir\""));
        assertTrue(source.contains("new File(dataDir, \"files\")"));
        assertTrue(source.contains("\"/data/user/\" + BlackBoxCore.getHostUserId()"));
        assertTrue(source.contains("BActivityThread.getAppPackageName()"));
        assertTrue(source.contains("BlackBoxBinderMonitor.recordProxyCall"));
    }

    @Test
    public void contextDataDirProxyDoesNotRewriteHostOrSystemContexts() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/ContextDataDirProxy.java");

        assertTrue("Context dir hooks should inspect the concrete ContextImpl instance",
                source.contains("callFrame.thisObject") && source.contains("publicDataDir(callFrame.thisObject)"));
        assertTrue("Host BlackBox context must keep its own data dir while BActivityThread has a virtual package",
                source.contains("shouldRewriteContext(Object context)")
                        && source.contains("BlackBoxCore.getHostPkg()")
                        && source.contains("hostPackage.equals(basePackageName)"));
        assertTrue("Virtual package contexts should still expose normal /data/user paths",
                source.contains("virtualPackage.equals(basePackageName)")
                        && source.contains("virtualPackage.equals(opPackageName)")
                        && source.contains("virtualPackage.equals(((Context) context).getPackageName())"));
    }

    @Test
    public void hookManagerInstallsContextDataDirProxyBeforePackageManagerHooks() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java");

        assertTrue(source.contains("import top.niunaijun.blackbox.fake.service.ContextDataDirProxy;"));
        assertTrue(source.contains("addInjector(new ContextDataDirProxy())"));
        assertTrue(source.indexOf("addInjector(new ContextDataDirProxy())")
                < source.indexOf("addInjector(new IPackageManagerProxy())"));
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
