package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class IConnectivityManagerProxySourceTest {
    @Test
    public void connectivityProxyRewritesCallingPackageAndUidBeforeForwardingToHostService() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/IConnectivityManagerProxy.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IConnectivityManagerProxy.java");

        assertTrue("Android 11 IConnectivityManager methods such as getNetworkCapabilities(Network,String) and requestNetwork(...,String,String) check that the calling package belongs to the Binder caller uid; virtual packages must be rewritten before forwarding",
                source.contains("MethodParameterUtils.replaceFirstAppPkg(args)")
                        && source.contains("@ProxyMethods")
                        && source.contains("\"getNetworkCapabilities\"")
                        && source.contains("\"requestNetwork\"")
                        && source.contains("\"listenForNetwork\"")
                        && source.contains("\"pendingRequestForNetwork\"")
                        && source.contains("\"pendingListenForNetwork\"")
                        && source.contains("\"registerConnectivityDiagnosticsCallback\""));
        assertTrue("Connectivity methods that take a uid must use the host uid when forwarding to the host connectivity service",
                source.contains("MethodParameterUtils.replaceFirstUid(args)")
                        && source.contains("\"getActiveNetworkForUid\"")
                        && source.contains("\"getActiveNetworkInfoForUid\"")
                        && source.contains("\"getNetworkInfoForUid\""));
        assertFalse("Connectivity proxy fixes must stay generic and not target a specific sample or app",
                source.contains("com.bestv.tv.video.iqy.tjdx")
                        || source.contains("com.example.tester"));
    }
}
