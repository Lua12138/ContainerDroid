package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

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
}
