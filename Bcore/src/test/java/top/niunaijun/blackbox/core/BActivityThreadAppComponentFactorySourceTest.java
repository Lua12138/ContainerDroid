package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BActivityThreadAppComponentFactorySourceTest {

    @Test
    public void bindApplicationClearsVirtualAppComponentFactoryBeforeMakeApplication() throws Exception {
        String source = readBActivityThreadSource();

        int applicationInfo = source.indexOf("applicationInfo =");
        int makeApplication = source.indexOf("makeApplication(false, null)");
        int compatReset = source.indexOf("resetAppComponentFactory(applicationInfo)");

        assertTrue("BActivityThread should load applicationInfo before makeApplication", applicationInfo >= 0);
        assertTrue("BActivityThread should call LoadedApk.makeApplication", makeApplication > applicationInfo);
        assertTrue("BActivityThread should reset appComponentFactory before LoadedApk.makeApplication",
                compatReset > applicationInfo && compatReset < makeApplication);
        assertTrue("reset helper should clear ApplicationInfo.appComponentFactory",
                source.contains("applicationInfo.appComponentFactory = null"));
    }

    @Test
    public void bindApplicationPropagatesLoadedApkMakeApplicationFailure() throws Exception {
        String source = readBActivityThreadSource();

        int makeApplication = source.indexOf("BRLoadedApk.getWithException(loadedApk).makeApplication(false, null)");
        int contextFix = source.indexOf("ContextCompat.fixVirtual(mInitialApplication, packageName)", makeApplication);

        assertTrue("BActivityThread should call LoadedApk.makeApplication with exception propagation",
                makeApplication >= 0);
        assertTrue("BActivityThread should create the Application before ContextCompat.fixVirtual",
                contextFix > makeApplication);
        assertFalse("BActivityThread should not retry Application construction after JNI_OnLoad failure",
                source.contains("makeFallbackApplication("));
        assertFalse("BActivityThread should not bypass LoadedApk with Instrumentation.newApplication",
                source.contains("Instrumentation.newApplication"));
    }

    @Test
    public void bindApplicationLogsUidIdentityBeforeMakeApplication() throws Exception {
        String source = readBActivityThreadSource();

        int loadedApkInfo = source.indexOf("_set_mApplicationInfo(applicationInfo)");
        int identityLog = source.indexOf("logApplicationIdentity(packageName, processName, applicationInfo)");
        int makeApplication = source.indexOf("BRLoadedApk.getWithException(loadedApk).makeApplication(false, null)");

        assertTrue("BActivityThread should set LoadedApk ApplicationInfo first", loadedApkInfo >= 0);
        assertTrue("BActivityThread should log virtual and Linux uid identity before app native code loads",
                identityLog > loadedApkInfo && identityLog < makeApplication);
        assertTrue("identity diagnostic should include the kernel uid returned by Process.myUid",
                source.contains("Process.myUid()"));
        assertTrue("identity diagnostic should include the hooked libcore uid path",
                source.contains("android.system.Os.getuid()"));
        assertTrue("identity diagnostic should include the BlackBox host uid",
                source.contains("BlackBoxCore.getHostUid()"));
        assertTrue("identity diagnostic should include the full virtual uid",
                source.contains("BActivityThread.getBUid()"));
        assertTrue("identity diagnostic should include the virtual app id",
                source.contains("BActivityThread.getBAppId()"));
        assertTrue("identity diagnostic should include ApplicationInfo.uid",
                source.contains("applicationInfo.uid"));
    }

    @Test
    public void bindApplicationConfiguresNativeVirtualUidBeforeTerminationShieldPackage() throws Exception {
        String source = readBActivityThreadSource();

        int nativeInit = source.indexOf("NativeCore.init(Build.VERSION.SDK_INT)");
        int setVirtualUid = source.indexOf("NativeCore.setVirtualUid(BActivityThread.getBUid())", nativeInit);
        int terminationPackage = source.indexOf("NativeCore.setNativeTerminationShieldPackage(packageName)", nativeInit);

        assertTrue("BActivityThread should initialize NativeCore first", nativeInit >= 0);
        assertTrue("BActivityThread should pass the virtual uid to native code before native termination shielding is configured",
                setVirtualUid > nativeInit && setVirtualUid < terminationPackage);
    }

    @Test
    public void bindApplicationLogsPlatformInitialApplicationAfterMakeApplication() throws Exception {
        String source = readBActivityThreadSource();

        int makeApplication = source.indexOf("BRLoadedApk.getWithException(loadedApk).makeApplication(false, null)");
        int setInitialApplication = source.indexOf("_set_mInitialApplication(mInitialApplication)", makeApplication);
        int afterSetLog = source.indexOf("logInitialApplicationState(\"afterSetInitialApplication\"", setInitialApplication);
        int applicationOnCreate = source.indexOf("AppInstrumentation.get().callApplicationOnCreate(application)", afterSetLog);
        int syncAfterOnCreate = source.indexOf("syncInitialApplicationFromRuntime(mInitialApplication, loadedApk)", applicationOnCreate);
        int afterOnCreateLog = source.indexOf("logInitialApplicationState(\"afterApplicationOnCreate\"", afterSetLog);

        assertTrue("BActivityThread should set ActivityThread.mInitialApplication after LoadedApk.makeApplication",
                setInitialApplication > makeApplication);
        assertTrue("BActivityThread should log the reflected ActivityThread.mInitialApplication readback after setting it",
                afterSetLog > setInitialApplication);
        assertTrue("BActivityThread should resync after protected apps swap StubApp to the real Application in onCreate",
                syncAfterOnCreate > applicationOnCreate && syncAfterOnCreate < afterOnCreateLog);
        assertTrue("sync helper should prefer the real LoadedApk or ActivityThread Application over the stale StubApp",
                source.contains("syncInitialApplicationFromRuntime")
                        && source.contains("BRLoadedApk.get(loadedApk).mApplication()")
                        && source.contains("BRActivityThread.get(BlackBoxCore.mainThread()).mInitialApplication()")
                        && source.contains("_set_mInitialApplication(syncedApplication)"));
        assertTrue("sync helper should restore LoadedApk.mApplication when protected loaders clear it",
                source.contains("_set_mApplication(syncedApplication)"));
        assertTrue("sync helper should restore framework references even when the selected Application remains local",
                source.contains("threadInitialApplication != syncedApplication")
                        && source.contains("loadedApkApplication != syncedApplication"));
        assertTrue("BActivityThread should log whether ActivityThread.mInitialApplication survived application onCreate",
                afterOnCreateLog > afterSetLog);
        assertTrue("diagnostic should compare the local application against platform ActivityThread.mInitialApplication",
                source.contains("threadInitialSameLocal="));
        assertTrue("diagnostic should include LoadedApk.mApplication for state correlation",
                source.contains("loadedApkApplication="));
    }

    @Test
    public void launchActivityRevalidatesApplicationReferencesBeforeFrameworkDispatch() throws Exception {
        String activityThreadSource = readBActivityThreadSource();
        String hCallbackSource = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/HCallbackProxy.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/HCallbackProxy.java");

        assertTrue("BActivityThread should expose a generic lifecycle guard for launch-time state repair",
                activityThreadSource.contains("ensureInitialApplicationState(")
                        && activityThreadSource.contains("syncInitialApplicationFromRuntime(mInitialApplication, mBoundApplication.info)"));

        int initializedBranch = hCallbackSource.indexOf("if (!BActivityThread.currentActivityThread().isInit())");
        int ensureBeforeLaunch = hCallbackSource.indexOf("ensureInitialApplicationState(\"beforeLaunchActivity\")", initializedBranch);
        int taskId = hCallbackSource.indexOf("getTaskForActivity(token, false)", initializedBranch);

        assertTrue("HCallbackProxy should repair ActivityThread/LoadedApk Application references after bind and before framework launch dispatch",
                ensureBeforeLaunch > initializedBranch && ensureBeforeLaunch < taskId);
    }

    @Test
    public void bindApplicationLogsPackageContextIdentityBeforeMakeApplication() throws Exception {
        String source = readBActivityThreadSource();

        int enableRedirect = source.indexOf("IOCore.get().enableRedirect(packageContext)");
        int contextLog = source.indexOf("logContextIdentity(\"beforeMakeApplication\", packageContext, packageName)");
        int makeApplication = source.indexOf("BRLoadedApk.getWithException(loadedApk).makeApplication(false, null)");

        assertTrue("BActivityThread should enable IO redirect before context diagnostics", enableRedirect >= 0);
        assertTrue("BActivityThread should log the attachBaseContext package context before app native code runs",
                contextLog > enableRedirect && contextLog < makeApplication);
        assertTrue("context diagnostic should include Context.getPackageName",
                source.contains("context.getPackageName()"));
        assertTrue("context diagnostic should include Context.getOpPackageName because protectors query it via app-ops",
                source.contains(".method(\"getOpPackageName\")"));
        assertTrue("context diagnostic should include public data dir exposed to Java protectors",
                source.contains("context.getDataDir()"));
        assertTrue("context diagnostic should include files dir exposed to Java protectors",
                source.contains("context.getFilesDir()"));
        assertTrue("context diagnostic should include native library dir from ApplicationInfo",
                source.contains("context.getApplicationInfo().nativeLibraryDir"));
        assertTrue("context diagnostic should include the class loader handed to StubApp.attachBaseContext",
                source.contains("context.getClassLoader()"));
    }

    private static String readBActivityThreadSource() throws Exception {
        return readSource(
                "src/main/java/top/niunaijun/blackbox/app/BActivityThread.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java");
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
