package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LegacyAspectProxySourceTest {

    @Test
    public void legacyAspectActivitiesUseDedicatedNonResizableProxyStubs() throws Exception {
        String manifest = readSource("src/main/AndroidManifest.xml", "Bcore/src/main/AndroidManifest.xml");
        String proxyManifest = readSource(
                "src/main/java/top/niunaijun/blackbox/proxy/ProxyManifest.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/proxy/ProxyManifest.java");
        String legacyProxy = readSource(
                "src/main/java/top/niunaijun/blackbox/proxy/LegacyAspectProxyActivity.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/proxy/LegacyAspectProxyActivity.java");

        assertTrue("Legacy aspect proxy class should provide the same process-indexed P0..P49 shape as ProxyActivity",
                legacyProxy.contains("class LegacyAspectProxyActivity extends ProxyActivity")
                        && legacyProxy.contains("class P0 extends LegacyAspectProxyActivity")
                        && legacyProxy.contains("class P49 extends LegacyAspectProxyActivity"));
        assertTrue("ProxyManifest should expose a dedicated legacy-aspect activity name instead of hardcoding target packages",
                proxyManifest.contains("getLegacyAspectProxyActivity(int index)")
                        && proxyManifest.contains("top.niunaijun.blackbox.proxy.LegacyAspectProxyActivity$P%d"));
        assertTrue("Manifest must declare legacy aspect proxy stubs with Android's pre-O default max aspect ratio",
                manifest.contains("android:name=\".proxy.LegacyAspectProxyActivity$P0\"")
                        && manifest.contains("android:name=\".proxy.LegacyAspectProxyActivity$P49\"")
                        && manifest.contains("android:resizeableActivity=\"false\"")
                        && manifest.contains("android:maxAspectRatio=\"1.86\""));
    }

    @Test
    public void activityStackRoutesPreOAspectLimitedActivitiesToLegacyAspectProxy() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/core/system/am/ActivityStack.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/core/system/am/ActivityStack.java");

        assertTrue("ActivityStack should compute the proxy class through a helper that can mirror target aspect compatibility",
                source.contains("resolveProxyActivityClass("));
        assertTrue("Legacy aspect routing must use target ActivityInfo/ApplicationInfo, not target package name hardcoding",
                source.contains("shouldUseLegacyAspectProxy(ActivityInfo activityInfo)")
                        && source.contains("activityInfo.applicationInfo.targetSdkVersion < Build.VERSION_CODES.O")
                        && source.contains("getActivityMaxAspectRatio(activityInfo)")
                        && source.contains("ProxyManifest.getLegacyAspectProxyActivity(vpid)"));
        assertFalse("Legacy aspect routing must not special-case the BestV package",
                source.contains("com.bestv.tv.video.iqy.tjdx"));
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
