package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IActivityManagerPublishProvidersSourceTest {

    @Test
    public void publishContentProvidersPublishesOnlyHostProxyProviders() throws Exception {
        String source = readSource("Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IActivityManagerProxy.java");

        assertTrue("IActivityManagerProxy should hook provider publication",
                source.contains("@ProxyMethod(\"publishContentProviders\")"));

        String hookBody = methodHookBody(source, "PublishContentProviders");
        assertTrue("publishContentProviders is void and should return null locally",
                hookBody.contains("return null"));
        assertTrue("host proxy providers must still be published to host AMS",
                hookBody.contains("isHostPublishedProvider"));
        assertTrue("host proxy provider detection must use proxy authorities",
                hookBody.contains("ProxyManifest.isProxy"));
        assertTrue("mixed provider lists should forward only host proxy holders",
                hookBody.contains("forwardedArgs[providersIndex] = hostProviders"));
        assertTrue("filtered host provider publication should call AMS with filtered args",
                hookBody.contains("method.invoke(who, forwardedArgs)"));
        assertFalse("virtual providers must not be forwarded by replacing caller package only",
                hookBody.contains("MethodParameterUtils.replace"));
    }

    private static String methodHookBody(String source, String className) {
        String marker = "class " + className;
        int start = source.indexOf(marker);
        if (start < 0) {
            throw new AssertionError(className + " hook not found");
        }
        int nextProxy = source.indexOf("@ProxyMethod", start + marker.length());
        if (nextProxy < 0) {
            nextProxy = source.length();
        }
        return source.substring(start, nextProxy);
    }

    private static String readSource(String rootRelativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(rootRelativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(rootRelativePath + " not found from " + current);
    }
}
