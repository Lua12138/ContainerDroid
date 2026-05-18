package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

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
