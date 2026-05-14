package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class WifiManagerMacProxySourceTest {

    @Test
    public void wifiInfoMacUsesPhysicalInterfaceWithoutTargetPackageGate() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/IWifiManagerProxy.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IWifiManagerProxy.java");

        assertTrue("Wi-Fi identity should try physical netdev sysfs addresses first",
                source.contains("WIFI_IDENTITY_INTERFACES")
                        && source.contains("/sys/class/net/")
                        && source.contains("readSysfsMacAddress"));
        assertTrue("Wi-Fi identity should fall back to generic system/vendor MAC properties when sysfs is unreadable",
                source.contains("PROPERTY_WIFI_MAC_KEYS")
                        && source.contains("ro.ril.oem.wifimac")
                        && source.contains("readSystemPropertyMacAddress")
                        && source.contains("wifi.interface"));
        assertTrue("Wi-Fi identity should reject Android placeholder MAC values",
                source.contains("02:00:00:00:00:00")
                        && source.contains("00:00:00:00:00:00"));
        assertTrue("Existing generic fallback Wi-Fi identity should remain available",
                source.contains("ac:62:5a:82:65:c4"));
        assertTrue("Wi-Fi identity must not be gated to a single app package",
                !source.contains("BActivityThread.getAppPackageName()")
                        && !source.contains("com.bestv"));
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
