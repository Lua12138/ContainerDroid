package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClipboardProxySourceTest {

    @Test
    public void clipboardProxyDoesNotDereferenceLazyClipboardServiceInConstructor() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/IClipboardManagerProxy.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IClipboardManagerProxy.java");

        assertFalse(source.contains("BRClipboardManager.get().getService().asBinder()"));
        assertTrue(source.contains("BRServiceManager.get().getService(Context.CLIPBOARD_SERVICE)"));
        assertTrue(source.contains("BRIClipboardStub.get().asInterface"));
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
