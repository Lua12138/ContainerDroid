package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PineXposedResLintSourceTest {

    @Test
    public void ioUtilsDoesNotDirectlyReferenceApi21ErrnoExceptionOnMinSdk19() throws Exception {
        String source = readSource(
                "pine-xposed-res/src/main/java/top/canyie/dreamland/utils/IOUtils.java",
                "Bcore/pine-xposed-res/src/main/java/top/canyie/dreamland/utils/IOUtils.java");

        assertFalse("pine-xposed-res minSdk is 19, so IOUtils must not directly reference API 21 android.system.ErrnoException",
                source.contains("import android.system.ErrnoException")
                        || source.contains("instanceof ErrnoException")
                        || source.contains("(ErrnoException)"));
        assertTrue("IOUtils should still extract errno values from nested IOException causes",
                source.contains("getErrno")
                        && source.contains("errno")
                        && source.contains("getField"));
    }

    private static String readSource(String moduleRelativePath, String rootRelativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            Path moduleCandidate = dir.resolve(moduleRelativePath);
            if (Files.isRegularFile(moduleCandidate)) {
                return new String(Files.readAllBytes(moduleCandidate), StandardCharsets.UTF_8);
            }
            Path rootCandidate = dir.resolve(rootRelativePath);
            if (Files.isRegularFile(rootCandidate)) {
                return new String(Files.readAllBytes(rootCandidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(rootRelativePath + " not found from " + current);
    }
}
