package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class NetworkInterfaceMacProxySourceTest {

    @Test
    public void networkInterfaceMacProxyUsesPhysicalSysfsMacWithoutTargetPackageGate() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/NetworkInterfaceMacProxy.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/NetworkInterfaceMacProxy.java");

        assertTrue("proxy should hook AOSP java.net.NetworkInterface.getHardwareAddress()",
                source.contains("NetworkInterface.class.getDeclaredMethod(\"getHardwareAddress\")"));
        assertTrue("hardware address hook must short-circuit before the framework netlink/sysfs lookup",
                source.contains("public void beforeCall(Pine.CallFrame callFrame)")
                        && source.contains("maybeProvideHardwareAddress(callFrame)"));
        assertTrue("short-circuiting should avoid running the original AOSP getByName/getAll path",
                source.contains("callFrame.setResult(realHardwareAddress.clone())")
                        && source.contains("return;")
                        && !source.contains("public void afterCall(Pine.CallFrame callFrame)"));
        assertTrue("loopback must not receive a fake hardware address",
                source.contains("isPhysicalNetworkInterface")
                        && source.contains("\"wlan0\"")
                        && !source.contains("\"lo\".equals(name)"));
        assertTrue("placeholder MAC values should be replaced from physical netdev sysfs when available",
                source.contains("DEFAULT_ANDROID_MAC_BYTES")
                        && source.contains("/sys/class/net/")
                        && source.contains("readSysfsHardwareAddress")
                        && source.contains("callFrame.setResult(realHardwareAddress.clone())"));
        assertTrue("Android R/SELinux may deny netdev sysfs; vendor/system properties should be a generic fallback",
                source.contains("PROPERTY_WIFI_MAC_KEYS")
                        && source.contains("ro.ril.oem.wifimac")
                        && source.contains("readSystemPropertyMacAddress")
                        && source.contains("wifi.interface"));
        assertTrue("Wi-Fi MAC properties must not synthesize Ethernet interfaces when eth0 sysfs is absent",
                source.contains("isWifiIdentityInterface")
                        && source.contains("if (!isWifiIdentityInterface(name))"));
        assertTrue("replacements should be visible in binder/proxy evidence",
                source.contains("BlackBoxBinderMonitor.recordProxyCall"));
        assertTrue("proxy must not be gated to a single app package",
                !source.contains("BActivityThread.getAppPackageName()")
                        && !source.contains("com.bestv"));
    }

    @Test
    public void networkInterfaceMacProxyDoesNotHookStaticEnumerationByDefault() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/NetworkInterfaceMacProxy.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/NetworkInterfaceMacProxy.java");

        assertTrue("NetworkInterface static Pine hooks are too broad; getifaddrs should be modeled in OsStub instead",
                !source.contains("NetworkInterface.class.getDeclaredMethod(\"getNetworkInterfaces\")")
                        && !source.contains("NetworkInterface.class.getDeclaredMethod(\"getByName\", String.class)")
                        && !source.contains("NetworkInterface.class.getDeclaredMethod(\"getByIndex\", int.class)")
                        && !source.contains("NetworkInterface.class.getDeclaredMethod(\"getByInetAddress\", InetAddress.class)"));
    }

    @Test
    public void hookManagerDoesNotInstallNetworkInterfaceMacProxyByDefault() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java");

        assertTrue("NetworkInterface Pine hooks add an observable core-Java hook footprint; default runtime should rely on OsStub getifaddrs instead",
                !source.contains("import top.niunaijun.blackbox.fake.service.NetworkInterfaceMacProxy;")
                        && !source.contains("new NetworkInterfaceMacProxy()"));
    }
}
