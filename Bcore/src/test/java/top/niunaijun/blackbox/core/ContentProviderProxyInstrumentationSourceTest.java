package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class ContentProviderProxyInstrumentationSourceTest {

    @Test
    public void contentProviderStubsRecordProxyEvents() throws Exception {
        String contentProviderStub = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/context/providers/ContentProviderStub.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/context/providers/ContentProviderStub.java");
        String systemProviderStub = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/context/providers/SystemProviderStub.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/context/providers/SystemProviderStub.java");

        assertTrue(contentProviderStub.contains("BlackBoxBinderMonitor.recordProxyCall"));
        assertTrue(contentProviderStub.contains("\"content_provider\""));
        assertTrue(contentProviderStub.contains("\"android.content.IContentProvider\""));
        assertTrue(contentProviderStub.contains("argsRewritten"));

        assertTrue(systemProviderStub.contains("BlackBoxBinderMonitor.recordProxyCall"));
        assertTrue(systemProviderStub.contains("\"settings_provider\""));
        assertTrue(systemProviderStub.contains("\"android.content.IContentProvider\""));
        assertTrue(systemProviderStub.contains("argsRewritten"));
    }

    private static String readSource(String modulePath, String rootPath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            Path moduleCandidate = dir.resolve(modulePath);
            if (Files.isRegularFile(moduleCandidate)) {
                return new String(Files.readAllBytes(moduleCandidate), StandardCharsets.UTF_8);
            }
            Path rootCandidate = dir.resolve(rootPath);
            if (Files.isRegularFile(rootCandidate)) {
                return new String(Files.readAllBytes(rootCandidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(rootPath + " not found from " + current);
    }
}
