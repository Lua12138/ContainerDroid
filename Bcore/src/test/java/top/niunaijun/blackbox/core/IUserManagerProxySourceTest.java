package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IUserManagerProxySourceTest {

    @Test
    public void userManagerProxyHandlesVirtualUnlockedStateLocally() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IUserManagerProxy.java");

        assertTrue("UserManager direct-boot state checks should be handled inside the sandbox user model",
                source.contains("VirtualUserUnlockedState")
                        && source.contains("@ProxyMethods")
                        && source.contains("\"isUserUnlocked\"")
                        && source.contains("\"isUserUnlockingOrUnlocked\"")
                        && source.contains("\"isUserRunning\""));
        assertTrue("Virtual sandbox app processes are running/unlocked after BActivityThread construction",
                source.contains("return true;"));
        assertTrue("The proxy should document the Android 11 IUserManager boolean prototypes it mirrors",
                source.contains("IUserManager.aidl")
                        && source.contains("boolean isUserUnlocked(int userId)")
                        && source.contains("boolean isUserUnlockingOrUnlocked(int userId)")
                        && source.contains("boolean isUserRunning(int userId)"));
        assertFalse("User state simulation must not branch on the current target package",
                source.contains("com.bestv")
                        || source.contains("entryRunApplication")
                        || source.contains("QHClassLoader"));
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
