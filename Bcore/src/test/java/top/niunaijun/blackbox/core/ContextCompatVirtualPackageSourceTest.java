package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class ContextCompatVirtualPackageSourceTest {

    @Test
    public void appContextsExposeVirtualPackageIdentityBeforeSystemProxiesRewriteOps() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/utils/compat/ContextCompat.java");

        assertTrue(source.contains("fixVirtual(Context context, String packageName)"));
        assertTrue(source.contains("_set_mBasePackageName(basePackageName)"));
        assertTrue("Virtual app Context.getOpPackageName should not leak the host package to hardened stubs",
                source.contains("String opPackageName = virtualDataDirs ? basePackageName : BlackBoxCore.getHostPkg()")
                        && source.contains("_set_mOpPackageName(opPackageName)"));
        assertTrue("Virtual app ContentResolver attribution should match the target until binder proxies rewrite it",
                source.contains("_set_mPackageName(opPackageName)"));
    }

    @Test
    public void applicationCreationUsesVirtualPackageContextBeforeAttachBaseContext() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/delegate/AppInstrumentation.java");

        assertTrue(source.contains("ContextCompat.fixVirtual(context, BActivityThread.getAppPackageName())"));
        assertTrue(source.indexOf("ContextCompat.fixVirtual(context, BActivityThread.getAppPackageName())")
                < source.indexOf("super.newApplication(cl, className, context)"));
    }

    @Test
    public void bActivityThreadKeepsApplicationAndServiceContextsVirtual() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java");

        assertTrue(source.contains("ContextCompat.fixVirtual(context, serviceInfo.packageName)"));
        assertTrue(source.contains("ContextCompat.fixVirtual(mInitialApplication, packageName)"));
        assertTrue(source.contains("ContextCompat.fix((Context) BRActivityThread.get(BlackBoxCore.mainThread()).getSystemContext())"));

        int enableRedirect = source.indexOf("IOCore.get().enableRedirect(packageContext)");
        int fixPackageContext = source.indexOf("ContextCompat.fixVirtual(packageContext, packageName)", enableRedirect);
        int makeApplication = source.indexOf("BRLoadedApk.getWithException(loadedApk).makeApplication(false, null)");
        assertTrue("attachBaseContext should receive sanitized package context identity before jiagu native code runs",
                fixPackageContext > enableRedirect && fixPackageContext < makeApplication);
    }

    @Test
    public void appContextsExposePublicDataDirsWhileIoCoreRedirectsToSandboxStorage() throws Exception {
        String contextCompat = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/utils/compat/ContextCompat.java");
        String contextImpl = readSource(
                "android-mirror/src/main/java/black/android/app/ContextImpl.java");

        assertTrue("ContextCompat should repair cached ContextImpl data directories for hardened Java stubs",
                contextCompat.contains("fixVirtualDataDirs(context, basePackageName)"));
        assertTrue("Virtual context data roots should look like a normal app-owned /data/user tree",
                contextCompat.contains("\"/data/user/\"")
                        && contextCompat.contains("BlackBoxCore.getHostUserId()"));
        assertTrue("Virtual context should expose public files/cache directories",
                contextCompat.contains("_set_mDataDir(publicDataDir)")
                        && contextCompat.contains("_set_mFilesDir(new File(publicDataDir, \"files\"))")
                        && contextCompat.contains("_set_mCacheDir(new File(publicDataDir, \"cache\"))"));
        assertTrue("LoadedApk data dir cache should also be repaired because ContextImpl may delegate there",
                contextCompat.contains("BRLoadedApk.get(packageInfo)")
                        && contextCompat.contains("_set_mDataDir(publicDataDir.getAbsolutePath())")
                        && contextCompat.contains("_set_mDataDirFile(publicDataDir)"));
        assertTrue("ApplicationInfo.dataDir should also be sanitized because native protectors read it via ActivityThread",
                contextCompat.contains("context.getApplicationInfo().dataDir = publicDataDir.getAbsolutePath()"));
        assertTrue("ContextImpl mirror should expose cached directory fields for reflection repair",
                contextImpl.contains("File mDataDir()")
                        && contextImpl.contains("File mFilesDir()")
                        && contextImpl.contains("File mCacheDir()"));
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
