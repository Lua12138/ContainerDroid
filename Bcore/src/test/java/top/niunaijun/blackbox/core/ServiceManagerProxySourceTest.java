package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class ServiceManagerProxySourceTest {

    @Test
    public void serviceManagerProxyRedirectsPackageLookupsToCachedBinderStub() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/ServiceManagerProxy.java");

        assertTrue(source.contains("Class.forName(\"android.os.ServiceManager\")"));
        assertTrue(source.contains("hookServiceLookup(serviceManager, \"getService\")"));
        assertTrue(source.contains("hookServiceLookup(serviceManager, \"checkService\")"));
        assertTrue(source.contains("hookServiceLookup(serviceManager, \"waitForService\")"));
        assertTrue(source.contains("getDeclaredMethod(methodName, String.class)"));
        assertTrue(source.contains("\"package\".equals(name)"));
        assertTrue(source.contains("BRServiceManager.get().sCache()"));
        assertTrue(source.contains("callFrame.setResult(cachedBinder)"));
        assertTrue(source.contains("BlackBoxBinderMonitor.recordProxyCall"));
    }

    @Test
    public void hookManagerInstallsServiceManagerProxy() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java");

        assertTrue(source.contains("import top.niunaijun.blackbox.fake.service.ServiceManagerProxy;"));
        assertTrue(source.contains("addInjector(new ServiceManagerProxy())"));
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
