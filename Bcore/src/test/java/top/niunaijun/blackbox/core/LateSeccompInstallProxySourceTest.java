package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class LateSeccompInstallProxySourceTest {

    @Test
    public void lateSeccompProxyDoesNotUseJavaAndroidLogHooks() throws Exception {
        String source = readOptionalSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/LateSeccompInstallProxy.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/LateSeccompInstallProxy.java");

        if (source == null) {
            return;
        }
        assertFalse("LateSeccompInstallProxy must not hook android.util.Log after Pine log hook failures",
                source.contains("android.util.Log"));
        assertFalse("LateSeccompInstallProxy must not install Pine hooks on hot logging paths",
                source.contains("Pine.hook"));
        assertFalse("LateSeccompInstallProxy must not hook public Log.d",
                source.contains("getDeclaredMethod(\"d\", String.class, String.class)"));
        assertFalse("LateSeccompInstallProxy must not hook hidden println_native",
                source.contains("println_native"));
    }

    @Test
    public void hookManagerDoesNotInstallLateSeccompProxy() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java");

        assertFalse("HookManager must not import the failed Java logging seccomp trigger",
                source.contains("import top.niunaijun.blackbox.fake.service.LateSeccompInstallProxy;"));
        assertFalse("HookManager must not install the failed Java logging seccomp trigger",
                source.contains("new LateSeccompInstallProxy()"));
        assertTrue("RuntimeExitProxy should remain installed",
                source.contains("new RuntimeExitProxy()"));
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

    private static String readOptionalSource(String moduleRelativePath, String rootRelativePath) throws Exception {
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
        return null;
    }
}
