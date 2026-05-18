package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class ServiceManagerProxySourceTest {

    @Test
    public void serviceManagerProxyRedirectsPackageLookupsToCachedBinderStub() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/ServiceManagerProxy.java");

        assertTrue(source.contains("ANDROID_SERVICE_MANAGER = \"android.os.ServiceManager\""));
        assertTrue(source.contains("Class.forName(ANDROID_SERVICE_MANAGER)"));
        assertTrue(source.contains("hookServiceLookup(serviceManager, \"getService\")"));
        assertTrue(source.contains("hookServiceLookup(serviceManager, \"checkService\")"));
        assertTrue(source.contains("hookServiceLookup(serviceManager, \"waitForService\")"));
        assertTrue(source.contains("getDeclaredMethod(methodName, String.class)"));
        assertTrue(source.contains("PACKAGE_SERVICE.equals(name)"));
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
}
