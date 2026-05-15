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
        assertTrue("safe enumeration should include core Android interfaces even when sysfs details are hidden from app UIDs",
                source.contains("isExistingNetworkInterface")
                        && source.contains("appVisibleHardwareAddress")
                        && source.contains("shouldExposeSyntheticInterface")
                        && source.contains("CORE_NETWORK_INTERFACE_CANDIDATES")
                        && source.contains("new byte[0]"));
        assertTrue("Android R+ MAC privacy should be the model: app-visible getifaddrs hardware addresses stay empty/inaccessible",
                source.contains("NetworkInterface.getHardwareAddress()")
                        && source.contains("non-system apps")
                        && source.contains("empty"));
        assertTrue("loopback should be modeled as an interface but must not receive a synthetic hardware address",
                source.contains("\"lo\"")
                        && source.contains("NETWORK_INTERFACE_CANDIDATES"));
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
