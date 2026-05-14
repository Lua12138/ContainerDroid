package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class OsStubSourceTest {

    @Test
    public void libcoreStatHooksVirtualizeUidAndGidOnlyForHostOwnedMetadata() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/libcore/OsStub.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/libcore/OsStub.java");

        assertTrue("libcore Os.stat/lstat/fstat should share one metadata sanitizer",
                source.contains("sanitizeStructStat")
                        && source.contains("@ProxyMethod(\"stat\")")
                        && source.contains("@ProxyMethod(\"lstat\")")
                        && source.contains("@ProxyMethod(\"fstat\")"));
        assertTrue("metadata sanitizer should rewrite both uid and gid instead of leaking the host BlackBox gid",
                source.contains("field(\"st_uid\").set")
                        && source.contains("field(\"st_gid\").set")
                        && source.contains("getFakeOwnerId"));
        assertTrue("root/system-owned files should keep their real owner ids while host-owned redirected app data becomes virtual-owned",
                source.contains("if (ownerId > 0 && ownerId <= Process.FIRST_APPLICATION_UID)")
                        && source.contains("BlackBoxCore.getHostUid()"));
    }

    @Test
    public void libcoreGetifaddrsProvidesSafeWifiEnumerationWithoutNetworkInterfaceStaticHooks() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/libcore/OsStub.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/libcore/OsStub.java");

        assertTrue("OsStub should intercept Android 11 Libcore.os.getifaddrs()",
                source.contains("@ProxyMethod(\"getifaddrs\")")
                        && source.contains("class getifaddrs extends MethodHook"));
        assertTrue("NetworkInterface.getByName/getAll also depend on if_nametoindex/if_indextoname",
                source.contains("@ProxyMethod(\"if_nametoindex\")")
                        && source.contains("@ProxyMethod(\"if_indextoname\")"));
        assertTrue("hidden android.system.StructIfaddrs should be created reflectively, not by SDK-hidden imports",
                source.contains("Class.forName(\"android.system.StructIfaddrs\")")
                        && source.contains("Array.newInstance")
                        && source.contains("getDeclaredConstructor(String.class, int.class, InetAddress.class, InetAddress.class, InetAddress.class, byte[].class)"));
        assertTrue("safe enumeration should use physical Wi-Fi sysfs/properties and skip unusable placeholder data",
                source.contains("PROPERTY_WIFI_INTERFACE_KEYS")
                        && source.contains("PROPERTY_WIFI_MAC_KEYS")
                        && source.contains("readSysfsHardwareAddress")
                        && source.contains("isWifiIdentityInterface")
                        && source.contains("DEFAULT_ANDROID_MAC_BYTES")
                        && source.contains("if (hardwareAddress == null)")
                        && source.contains("continue;"));
        assertTrue("Wi-Fi MAC properties must not synthesize Ethernet when eth0 sysfs is absent",
                source.contains("if (!isWifiIdentityInterface(name))"));
        assertTrue("OsStub network modeling must not hardcode the target package",
                !source.contains("com.bestv"));
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
