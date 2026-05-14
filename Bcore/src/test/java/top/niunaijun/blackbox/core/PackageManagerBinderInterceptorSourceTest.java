package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class PackageManagerBinderInterceptorSourceTest {

    @Test
    public void interceptorSynthesizesVirtualPackageManagerReplies() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/PackageManagerBinderInterceptor.java");

        assertTrue(source.contains("implements BlackBoxBinderMonitor.BinderTransactInterceptor"));
        assertTrue(source.contains("\"android.content.pm.IPackageManager\".equals(descriptor)"));
        assertTrue(source.contains("BinderPayloadSummary.parsePackageManagerCall"));
        assertTrue(source.contains("BlackBoxCore.getBPackageManager().getPackageInfo"));
        assertTrue(source.contains("BlackBoxCore.getBPackageManager().getApplicationInfo"));
        assertTrue(source.contains("BActivityThread.getBUid()"));
        assertTrue(source.contains("reply.writeNoException()"));
        assertTrue(source.contains("writeToParcel(reply, 0)"));
        assertTrue(source.contains("reply.setDataPosition(0)"));
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

    private static String readSource(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(relativePath + " not found from " + current);
    }
}
