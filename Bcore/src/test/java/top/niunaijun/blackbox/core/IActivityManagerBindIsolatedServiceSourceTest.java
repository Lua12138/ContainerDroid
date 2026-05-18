package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class IActivityManagerBindIsolatedServiceSourceTest {
    @Test
    public void bindIsolatedServicePreservesCallerInstanceName() throws Exception {
        String source = readSource("Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IActivityManagerProxy.java");

        String hookBody = classBody(source, "BindIsolatedService");
        assertTrue("bindIsolatedService should reuse the bindService virtualization path",
                hookBody.contains("extends BindService"));
        assertFalse("AOSP exposes instanceName as a caller-controlled isolated-service identity; clearing args[6] changes WebView/isolated-service process identity",
                hookBody.contains("args[6] = null"));
    }

    private static String classBody(String source, String className) {
        String marker = "class " + className;
        int start = source.indexOf(marker);
        if (start < 0) {
            throw new AssertionError(className + " not found");
        }
        int nextProxy = source.indexOf("@ProxyMethod", start + marker.length());
        if (nextProxy < 0) {
            nextProxy = source.length();
        }
        return source.substring(start, nextProxy);
    }
}
