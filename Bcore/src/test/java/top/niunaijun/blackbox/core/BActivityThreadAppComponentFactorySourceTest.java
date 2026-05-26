package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class BActivityThreadAppComponentFactorySourceTest {
    private static final String[] B_ACTIVITY_THREAD_SOURCE = {
            "src/main/java/top/niunaijun/blackbox/app/BActivityThread.java",
            "Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java"
    };

    @Test
    public void bindApplicationClearsVirtualAppComponentFactoryBeforeMakeApplication() throws Exception {
        String source = readSource(B_ACTIVITY_THREAD_SOURCE);

        int applicationInfo = source.indexOf("applicationInfo =");
        int makeApplication = source.indexOf("makeApplication(false, null)");
        int compatReset = source.indexOf("resetAppComponentFactory(applicationInfo)");

        assertTrue("BActivityThread should load applicationInfo before makeApplication", applicationInfo >= 0);
        assertTrue("BActivityThread should call LoadedApk.makeApplication", makeApplication > applicationInfo);
        assertTrue("BActivityThread should reset appComponentFactory before LoadedApk.makeApplication",
                compatReset > applicationInfo && compatReset < makeApplication);
        assertTrue("reset helper should clear ApplicationInfo.appComponentFactory",
                source.contains("applicationInfo.appComponentFactory = null"));
        assertTrue("reset helper should log the generic factory compatibility rewrite so behavior changes are auditable",
                source.contains("reset AppComponentFactory")
                        && source.contains("appComponentFactory"));
    }

    @Test
    public void bindApplicationPropagatesLoadedApkMakeApplicationFailure() throws Exception {
        String source = readSource(B_ACTIVITY_THREAD_SOURCE);

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
        String source = readSource(B_ACTIVITY_THREAD_SOURCE);

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
    public void bindApplicationConfiguresNativeVirtualUidBeforeNativeSandboxEnvironment() throws Exception {
        String source = readSource(B_ACTIVITY_THREAD_SOURCE);

        int nativeInit = source.indexOf("NativeCore.init(Build.VERSION.SDK_INT)");
        int setVirtualUid = source.indexOf("NativeCore.setVirtualUid(BActivityThread.getBUid())", nativeInit);
        int sandboxEnvironment = source.indexOf("NativeCore.setNativeSandboxEnvironment(packageName, processName, BlackBoxCore.getHostPkg())", nativeInit);

        assertTrue("BActivityThread should initialize NativeCore first", nativeInit >= 0);
        assertTrue("BActivityThread should pass the virtual uid to native code before package-scoped native environment virtualization is configured",
                setVirtualUid > nativeInit && setVirtualUid < sandboxEnvironment);
    }

    @Test
    public void bindApplicationLogsPlatformInitialApplicationAfterMakeApplication() throws Exception {
        String source = readSource(B_ACTIVITY_THREAD_SOURCE);

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
        String activityThreadSource = readSource(B_ACTIVITY_THREAD_SOURCE);
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
        String source = readSource(B_ACTIVITY_THREAD_SOURCE);

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

    @Test
    public void lifecycleIdentityDiagnosticsAreExplicitOptInBeforeTouchingClassLoader() throws Exception {
        String source = readSource(B_ACTIVITY_THREAD_SOURCE);

        int diagnosticSwitch = source.indexOf("private static boolean isAppLifecycleDiagnosticsEnabled()");
        int contextGuard = source.indexOf("if (!isAppLifecycleDiagnosticsEnabled())", source.indexOf("logContextIdentity("));
        int contextClassLoader = source.indexOf("context.getClassLoader()", source.indexOf("logContextIdentity("));
        int boundaryGuard = source.indexOf("if (!isAppLifecycleDiagnosticsEnabled())", source.indexOf("logApplicationBoundary("));
        int boundaryClassLoader = source.indexOf("getContextClassLoaderQuietly(context)", source.indexOf("logApplicationBoundary("));
        int identityGuard = source.indexOf("if (!isAppLifecycleDiagnosticsEnabled())", source.indexOf("logApplicationIdentity("));
        int initialStateGuard = source.indexOf("if (!isAppLifecycleDiagnosticsEnabled())", source.indexOf("logInitialApplicationState("));

        assertTrue("BActivityThread should expose an explicit opt-in switch for lifecycle identity diagnostics",
                diagnosticSwitch >= 0
                        && source.contains("BLACKBOX_APP_LIFECYCLE_DIAG")
                        && source.contains("blackbox.app_lifecycle_diag")
                        && source.contains("debug.blackbox.app_lifecycle_diag")
                        && source.contains("BlackBoxCore.get().isDiagnosticLogcatEnabled()"));
        assertTrue("context identity diagnostics should return before Context.getClassLoader() unless opt-in",
                contextGuard >= 0 && contextGuard < contextClassLoader);
        assertTrue("application boundary diagnostics should return before class loader stringification unless opt-in",
                boundaryGuard >= 0 && boundaryGuard < boundaryClassLoader);
        assertTrue("uid identity diagnostics should be gated with the same opt-in switch",
                identityGuard >= 0);
        assertTrue("initial application diagnostics should be gated with the same opt-in switch",
                initialStateGuard >= 0);
    }

    @Test
    public void bindAndLaunchUseTargetCompatibilityInfoForLegacyDisplayScaling() throws Exception {
        String activityThreadSource = readSource(B_ACTIVITY_THREAD_SOURCE);
        String hCallbackSource = readSource(
                "src/main/java/top/niunaijun/blackbox/fake/service/HCallbackProxy.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/HCallbackProxy.java");
        String activityThreadMirror = readSource(
                "src/main/java/black/android/app/ActivityThread.java",
                "android-mirror/src/main/java/black/android/app/ActivityThread.java");
        String launchActivityItemMirror = readSource(
                "src/main/java/black/android/app/servertransaction/LaunchActivityItem.java",
                "android-mirror/src/main/java/black/android/app/servertransaction/LaunchActivityItem.java");
        String compatibilityInfoMirror = readSource(
                "src/main/java/black/android/content/res/CompatibilityInfo.java",
                "android-mirror/src/main/java/black/android/content/res/CompatibilityInfo.java");
        String loadedApkMirror = readSource(
                "src/main/java/black/android/app/LoadedApk.java",
                "android-mirror/src/main/java/black/android/app/LoadedApk.java");

        int createCompatInfo = activityThreadSource.indexOf("createCompatibilityInfo(applicationInfo)");
        int applyCompatInfo = activityThreadSource.indexOf("applyCompatibilityInfo(boundApplication, loadedApk, bindData.compatibilityInfo)");
        int setAppBindCompatInfo = activityThreadSource.indexOf("_set_compatInfo(bindData.compatibilityInfo)");
        int makeApplication = activityThreadSource.indexOf("BRLoadedApk.getWithException(loadedApk).makeApplication(false, null)");

        assertTrue("BActivityThread should create target CompatibilityInfo before application code starts",
                createCompatInfo >= 0 && createCompatInfo < makeApplication);
        assertTrue("BActivityThread should apply target CompatibilityInfo to LoadedApk/AppBindData before makeApplication",
                applyCompatInfo > createCompatInfo && applyCompatInfo < makeApplication);
        assertTrue("BActivityThread should populate ActivityThread.AppBindData.compatInfo for framework resource/display code",
                setAppBindCompatInfo > createCompatInfo && setAppBindCompatInfo < makeApplication);
        assertTrue("CompatibilityInfo should be computed from the target ApplicationInfo, not the host package",
                activityThreadSource.contains("BRCompatibilityInfo.get()._new(")
                        && activityThreadSource.contains("applicationInfo,")
                        && activityThreadSource.contains("configuration.screenLayout")
                        && activityThreadSource.contains("configuration.smallestScreenWidthDp")
                        && activityThreadSource.contains("applicationInfo.targetSdkVersion < Build.VERSION_CODES.O"));
        assertTrue("LoadedApk should receive the same target CompatibilityInfo instance through modern and legacy paths",
                activityThreadSource.contains("BRLoadedApk.get(loadedApk).setCompatibilityInfo(compatibilityInfo)")
                        && activityThreadSource.contains("BRLoadedApkICS.get(loadedApk)._set_mCompatibilityInfo(compatibilityInfo)"));
        assertTrue("BActivityThread should expose the bound CompatibilityInfo for launch transaction repair",
                activityThreadSource.contains("Object compatibilityInfo;")
                        && activityThreadSource.contains("getCompatibilityInfo()"));

        int launchActivityContext = hCallbackSource.indexOf("LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(launchActivityItem)");
        int setIntent = hCallbackSource.indexOf("_set_mIntent(intent)", launchActivityContext);
        int setInfo = hCallbackSource.indexOf("_set_mInfo(activityInfo)", launchActivityContext);
        int getCompatibilityInfo = hCallbackSource.indexOf("getCompatibilityInfo()", launchActivityContext);
        int setLaunchCompatInfo = hCallbackSource.indexOf("_set_mCompatInfo(compatibilityInfo)", getCompatibilityInfo);

        assertTrue("HCallbackProxy should rewrite LaunchActivityItem intent/info first",
                launchActivityContext >= 0 && setIntent > launchActivityContext && setInfo > setIntent);
        assertTrue("HCallbackProxy should propagate the target CompatibilityInfo into LaunchActivityItem",
                getCompatibilityInfo > setInfo && setLaunchCompatInfo > getCompatibilityInfo);
        assertTrue("ActivityThread.AppBindData mirror must expose compatInfo",
                activityThreadMirror.contains("@BClassName(\"android.app.ActivityThread$AppBindData\")")
                        && activityThreadMirror.contains("Object compatInfo();"));
        assertTrue("LaunchActivityItem mirror must expose mCompatInfo",
                launchActivityItemMirror.contains("@BClassName(\"android.app.servertransaction.LaunchActivityItem\")")
                        && launchActivityItemMirror.contains("Object mCompatInfo();"));
        assertTrue("CompatibilityInfo constructors should return framework objects, not the mirror interface type",
                compatibilityInfoMirror.contains("Object _new(ApplicationInfo ApplicationInfo0, int int1, int int2, boolean boolean3)"));
        assertFalse("CompatibilityInfo constructors must not cast framework instances to the mirror interface",
                compatibilityInfoMirror.contains("CompatibilityInfo _new(ApplicationInfo ApplicationInfo0, int int1, int int2, boolean boolean3)"));
        assertTrue("LoadedApk mirror should expose setCompatibilityInfo because Android R+ stores it in DisplayAdjustments",
                loadedApkMirror.contains("void setCompatibilityInfo(@BParamClassName(\"android.content.res.CompatibilityInfo\") Object compatInfo)"));
    }

}
