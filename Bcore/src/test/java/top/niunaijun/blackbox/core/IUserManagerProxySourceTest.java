package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

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
}
