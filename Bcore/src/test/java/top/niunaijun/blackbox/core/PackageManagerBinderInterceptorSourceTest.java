package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;
import static top.niunaijun.blackbox.core.SourceAssertions.sliceBetween;

public class PackageManagerBinderInterceptorSourceTest {

    @Test
    public void interceptorSynthesizesVirtualPackageManagerReplies() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/PackageManagerBinderInterceptor.java");

        assertTrue(source.contains("implements BlackBoxBinderMonitor.BinderTransactInterceptor"));
        assertTrue(source.contains("IPACKAGE_MANAGER.equals(descriptor)"));
        assertTrue(source.contains("BinderPayloadSummary.parsePackageManagerCall"));
        assertTrue(source.contains("BlackBoxCore.getBPackageManager().getPackageInfo"));
        assertTrue(source.contains("BlackBoxCore.getBPackageManager().getApplicationInfo"));
        assertTrue(source.contains("BActivityThread.getBUid()"));
        assertTrue(source.contains("reply.writeNoException()"));
        assertTrue(source.contains("writeToParcel(reply, 0)"));
        assertTrue("This interceptor writes directly into the caller-provided reply Parcel from a BinderProxy.transact hook; reset is required before the generated IPackageManager proxy reads readException()/payload",
                source.contains("resetReplyForInlineBinderRead")
                        && source.contains("reply.setDataPosition(0)")
                        && countOccurrences(source, "resetReplyForInlineBinderRead(reply)") >= 3);
        assertTrue(source.contains("BlackBoxBinderMonitor.recordProxyCall"));
        assertTrue(source.contains("describePackageInfo(packageInfo)"));
        assertTrue(source.contains("signatureHash"));
        assertTrue(source.contains("sourceDir="));
    }

    @Test
    public void interceptorSanitizesApplicationInfoDataDirsBeforeParcelReply() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/PackageManagerBinderInterceptor.java");

        assertTrue(source.contains("sanitizePackageInfoForReply(packageInfo, call.getPackageName())"));
        assertTrue(source.contains("sanitizeApplicationInfoForReply(applicationInfo, call.getPackageName())"));
        assertTrue(source.contains("new ApplicationInfo(applicationInfo)"));
        assertTrue(source.contains("String publicDataDir = publicDataDir(packageName)"));
        assertTrue(source.contains("copy.dataDir = publicDataDir"));
        assertTrue(source.contains("setStringFieldIfPresent(copy, \"credentialProtectedDataDir\""));
        assertTrue(source.contains("setStringFieldIfPresent(copy, \"deviceProtectedDataDir\""));
        assertTrue(source.contains("\"/data/user/\" + BlackBoxCore.getHostUserId()"));
    }

    @Test
    public void interceptorLeavesHostAndRealPackagesToSystemPackageManager() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/PackageManagerBinderInterceptor.java");
        String packageInfoReply = sliceBetween(source,
                "private boolean writePackageInfoReply",
                "private boolean writeApplicationInfoReply");
        String applicationInfoReply = sliceBetween(source,
                "private boolean writeApplicationInfoReply",
                "private boolean writePackageUidReply");
        String uidReply = sliceBetween(source,
                "private boolean writePackageUidReply",
                "private static boolean isVirtualInstalledPackage");

        assertTrue("Native Binder PM interception should synthesize replies only for virtual-installed packages; host/system packages must fall through to the real PackageManager so their UID and data dirs stay real.",
                source.contains("private static boolean isVirtualInstalledPackage(String packageName)")
                        && source.contains("BlackBoxCore.get().isInstalled(packageName, BActivityThread.getUserId())"));
        assertTrue("getPackageInfo must not sanitize or synthesize host/system package replies.",
                packageInfoReply.contains("if (!isVirtualInstalledPackage(call.getPackageName()))")
                        && packageInfoReply.contains("return false;"));
        assertTrue("getApplicationInfo must not sanitize or synthesize host/system package replies.",
                applicationInfoReply.contains("if (!isVirtualInstalledPackage(call.getPackageName()))")
                        && applicationInfoReply.contains("return false;"));
        assertTrue("getPackageUid must not return the virtual app uid for non-virtual package names.",
                uidReply.contains("if (!isVirtualInstalledPackage(call.getPackageName()))")
                        && uidReply.contains("return false;"));
        assertFalse("The package manager bypass must stay generic and must not hardcode the observed sample or host application id.",
                source.contains("com.bestv.tv.video.iqy.tjdx")
                        || source.contains("top.niunaijun.blackboxa32"));
    }

    @Test
    public void javaPackageManagerProxyReturnsVirtualUidOnlyForVirtualPackages() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IPackageManagerProxy.java");
        String getPackageUid = sliceBetween(source,
                "@ProxyMethod(\"getPackageUid\")",
                "private static void dumpReportedDexLoads");

        assertTrue("Java IPackageManager.getPackageUid proxy must keep non-virtual package names untouched so host/system package UID probes see the real system UID.",
                getPackageUid.contains("String virtualPackage = MethodParameterUtils.replaceFirstAppPkg(args);")
                        && getPackageUid.contains("if (virtualPackage != null)")
                        && getPackageUid.contains("return BActivityThread.getBUid();")
                        && getPackageUid.contains("return method.invoke(who, args);"));
        assertFalse("The Java package UID bypass must stay generic and must not hardcode the observed sample or host application id.",
                source.contains("com.bestv.tv.video.iqy.tjdx")
                        || source.contains("top.niunaijun.blackboxa32"));
    }

    @Test
    public void bActivityThreadRegistersPackageManagerBinderInterceptor() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java");

        assertTrue(source.contains("import top.niunaijun.blackbox.fake.service.PackageManagerBinderInterceptor;"));
        assertTrue(source.contains("BlackBoxBinderMonitor.setTransactInterceptor(new PackageManagerBinderInterceptor())"));
    }
    private static int countOccurrences(String source, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(pattern, index)) >= 0) {
            count++;
            index += pattern.length();
        }
        return count;
    }
}
