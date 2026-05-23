package top.niunaijun.blackbox.app;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.Service;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.StrictMode;
import android.text.TextUtils;
import android.webkit.WebView;

import java.io.File;
import java.lang.reflect.Method;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import black.android.app.ActivityThreadAppBindDataContext;
import black.android.app.BRActivity;
import black.android.app.BRActivityManagerNative;
import black.android.app.BRActivityThread;
import black.android.app.BRActivityThreadActivityClientRecord;
import black.android.app.BRActivityThreadAppBindData;
import black.android.app.BRActivityThreadNMR1;
import black.android.app.BRActivityThreadQ;
import black.android.app.BRContextImpl;
import black.android.app.BRLoadedApk;
import black.android.app.BRLoadedApkICS;
import black.android.app.BRService;
import black.android.content.BRBroadcastReceiver;
import black.android.content.BRContentProviderClient;
import black.android.content.res.BRCompatibilityInfo;
import black.android.graphics.BRCompatibility;
import black.android.security.net.config.BRNetworkSecurityConfigProvider;
import black.com.android.internal.content.BRReferrerIntent;
import black.dalvik.system.BRVMRuntime;
import top.canyie.pine.xposed.PineXposed;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;
import top.niunaijun.blackbox.binder.BinderMonitorConfig;
import top.niunaijun.blackbox.binder.BlackBoxBinderMonitor;
import top.niunaijun.blackbox.binder.VirtualIdentity;
import top.niunaijun.blackbox.app.dispatcher.AppServiceDispatcher;
import top.niunaijun.blackbox.core.CrashHandler;
import top.niunaijun.blackbox.core.IBActivityThread;
import top.niunaijun.blackbox.core.IOCore;
import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.core.env.VirtualRuntime;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.entity.am.ReceiverData;
import top.niunaijun.blackbox.entity.pm.InstalledModule;
import top.niunaijun.blackbox.fake.delegate.AppInstrumentation;
import top.niunaijun.blackbox.fake.delegate.ContentProviderDelegate;
import top.niunaijun.blackbox.fake.frameworks.BXposedManager;
import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.fake.service.DexDumpProxy;
import top.niunaijun.blackbox.fake.service.HCallbackProxy;
import top.niunaijun.blackbox.fake.service.PackageManagerBinderInterceptor;
import top.niunaijun.blackbox.utils.DiagnosticSwitch;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.ActivityManagerCompat;
import top.niunaijun.blackbox.utils.compat.BuildCompat;
import top.niunaijun.blackbox.utils.compat.ContextCompat;
import top.niunaijun.blackbox.utils.compat.StrictModeCompat;
import top.niunaijun.blackbox.utils.compat.SystemPropertiesCompat;

/**
 * Created by Milk on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BActivityThread extends IBActivityThread.Stub {
    public static final String TAG = "BActivityThread";
    private static final String NATIVE_TERMINATION_SHIELD_ENV =
            "BLACKBOX_NATIVE_TERMINATION_SHIELD";
    private static final String NATIVE_TERMINATION_SHIELD_PROPERTY =
            "blackbox.native_termination_shield";
    private static final String NATIVE_TERMINATION_SHIELD_SYSTEM_PROPERTY =
            "debug.blackbox.native_termination_shield";
    private static final String APP_LIFECYCLE_DIAG_ENV =
            "BLACKBOX_APP_LIFECYCLE_DIAG";
    private static final String APP_LIFECYCLE_DIAG_PROPERTY =
            "blackbox.app_lifecycle_diag";
    private static final String APP_LIFECYCLE_DIAG_SYSTEM_PROPERTY =
            "debug.blackbox.app_lifecycle_diag";

    private static BActivityThread sBActivityThread;
    private AppBindData mBoundApplication;
    private Application mInitialApplication;
    private AppConfig mAppConfig;
    private final List<ProviderInfo> mProviders = new ArrayList<>();
    private final Handler mH = BlackBoxCore.get().getHandler();
    private static final Object mConfigLock = new Object();

    public static boolean isThreadInit() {
        return sBActivityThread != null;
    }

    public static BActivityThread currentActivityThread() {
        if (sBActivityThread == null) {
            synchronized (BActivityThread.class) {
                if (sBActivityThread == null) {
                    sBActivityThread = new BActivityThread();
                }
            }
        }
        return sBActivityThread;
    }

    public static AppConfig getAppConfig() {
        synchronized (mConfigLock) {
            return currentActivityThread().mAppConfig;
        }
    }

    public static List<ProviderInfo> getProviders() {
        return currentActivityThread().mProviders;
    }

    public static String getAppProcessName() {
        if (getAppConfig() != null) {
            return getAppConfig().processName;
        } else if (currentActivityThread().mBoundApplication != null) {
            return currentActivityThread().mBoundApplication.processName;
        } else {
            return null;
        }
    }

    public static String getAppPackageName() {
        if (getAppConfig() != null) {
            return getAppConfig().packageName;
        } else if (currentActivityThread().mInitialApplication != null) {
            return currentActivityThread().mInitialApplication.getPackageName();
        } else {
            return null;
        }
    }

    public static Application getApplication() {
        return currentActivityThread().mInitialApplication;
    }

    public static int getAppPid() {
        return getAppConfig() == null ? -1 : getAppConfig().bpid;
    }

    public static int getBUid() {
        return getAppConfig() == null ? BUserHandle.AID_APP_START : getAppConfig().buid;
    }

    public static int getBAppId() {
        return BUserHandle.getAppId(getBUid());
    }

    public static int getCallingBUid() {
        return getAppConfig() == null ? BlackBoxCore.getHostUid() : getAppConfig().callingBUid;
    }

    public static int getUid() {
        return getAppConfig() == null ? -1 : getAppConfig().uid;
    }

    public static int getUserId() {
        return getAppConfig() == null ? 0 : getAppConfig().userId;
    }

    public void initProcess(AppConfig appConfig) {
        synchronized (mConfigLock) {
            if (this.mAppConfig != null && !this.mAppConfig.packageName.equals(appConfig.packageName)) {
                // 该进程已被attach
                throw new RuntimeException("reject init process: " + appConfig.processName + ", this process is : " + this.mAppConfig.processName);
            }
            this.mAppConfig = appConfig;
            IBinder iBinder = asBinder();
            try {
                iBinder.linkToDeath(new DeathRecipient() {
                    @Override
                    public void binderDied() {
                        synchronized (mConfigLock) {
                            try {
                                iBinder.linkToDeath(this, 0);
                            } catch (RemoteException ignored) {
                            }
                            mAppConfig = null;
                        }
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    public boolean isInit() {
        return mBoundApplication != null;
    }

    public Service createService(ServiceInfo serviceInfo, IBinder token) {
        if (!BActivityThread.currentActivityThread().isInit()) {
            BActivityThread.currentActivityThread().bindApplication(serviceInfo.packageName, serviceInfo.processName);
        }
        ClassLoader classLoader = BRLoadedApk.get(mBoundApplication.info).getClassLoader();
        Service service;
        try {
            service = (Service) classLoader.loadClass(serviceInfo.name).newInstance();
        } catch (Exception e) {
            e.printStackTrace();
            Slog.e(TAG, "Unable to instantiate service " + serviceInfo.name
                    + ": " + e.toString());
            return null;
        }

        try {
            Context context = BlackBoxCore.getContext().createPackageContext(
                    serviceInfo.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );
            BRContextImpl.get(context).setOuterContext(service);
            BRService.get(service).attach(
                    context,
                    BlackBoxCore.mainThread(),
                    serviceInfo.name,
                    token,
                    mInitialApplication,
                    BRActivityManagerNative.get().getDefault()
            );
            ContextCompat.fixVirtual(context, serviceInfo.packageName);
            service.onCreate();
            return service;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Unable to create service " + serviceInfo.name
                            + ": " + e.toString(), e);
        }
    }

    public JobService createJobService(ServiceInfo serviceInfo) {
        if (!BActivityThread.currentActivityThread().isInit()) {
            BActivityThread.currentActivityThread().bindApplication(serviceInfo.packageName, serviceInfo.processName);
        }
        ClassLoader classLoader = BRLoadedApk.get(mBoundApplication.info).getClassLoader();
        JobService service;
        try {
            service = (JobService) classLoader.loadClass(serviceInfo.name).newInstance();
        } catch (Exception e) {
            e.printStackTrace();
            Slog.e(TAG, "Unable to create JobService " + serviceInfo.name
                    + ": " + e.toString());
            return null;
        }

        try {
            Context context = BlackBoxCore.getContext().createPackageContext(
                    serviceInfo.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );
            BRContextImpl.get(context).setOuterContext(service);
            BRService.get(service).attach(
                    context,
                    BlackBoxCore.mainThread(),
                    serviceInfo.name,
                    BActivityThread.currentActivityThread().getActivityThread(),
                    mInitialApplication,
                    BRActivityManagerNative.get().getDefault()
            );
            ContextCompat.fixVirtual(context, serviceInfo.packageName);
            service.onCreate();
            service.onBind(null);
            return service;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Unable to create JobService " + serviceInfo.name
                            + ": " + e.toString(), e);
        }
    }

    public void bindApplication(final String packageName, final String processName) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final ConditionVariable conditionVariable = new ConditionVariable();
            BlackBoxCore.get().getHandler().post(() -> {
                handleBindApplication(packageName, processName);
                conditionVariable.open();
            });
            conditionVariable.block();
        } else {
            handleBindApplication(packageName, processName);
        }
    }

    public synchronized void handleBindApplication(String packageName, String processName) {
        if (isInit())
            return;
        try {
            CrashHandler.create();
        } catch (Throwable ignored) {
        }

        PackageInfo packageInfo = BlackBoxCore.getBPackageManager().getPackageInfo(packageName, PackageManager.GET_PROVIDERS, BActivityThread.getUserId());
        ApplicationInfo applicationInfo = packageInfo.applicationInfo;
        if (packageInfo.providers == null) {
            packageInfo.providers = new ProviderInfo[]{};
        }
        mProviders.addAll(Arrays.asList(packageInfo.providers));

        Object boundApplication = BRActivityThread.get(BlackBoxCore.mainThread()).mBoundApplication();

        Context packageContext = createPackageContext(applicationInfo);
        Object loadedApk = BRContextImpl.get(packageContext).mPackageInfo();
        BRLoadedApk.get(loadedApk)._set_mSecurityViolation(false);
        // fix applicationInfo
        resetAppComponentFactory(applicationInfo);
        BRLoadedApk.get(loadedApk)._set_mApplicationInfo(applicationInfo);

        int targetSdkVersion = applicationInfo.targetSdkVersion;
        if (targetSdkVersion < Build.VERSION_CODES.GINGERBREAD) {
            StrictMode.ThreadPolicy newPolicy = new StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy()).permitNetwork().build();
            StrictMode.setThreadPolicy(newPolicy);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (targetSdkVersion < Build.VERSION_CODES.N) {
                StrictModeCompat.disableDeathOnFileUriExposure();
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix(getUserId() + ":" + packageName + ":" + processName);
        }

        VirtualRuntime.setupRuntime(processName, applicationInfo);

        BRVMRuntime.get(BRVMRuntime.get().getRuntime()).setTargetSdkVersion(applicationInfo.targetSdkVersion);
        if (BuildCompat.isS()) {
            BRCompatibility.get().setTargetSdkVersion(applicationInfo.targetSdkVersion);
        }

        NativeCore.init(Build.VERSION.SDK_INT);
        NativeCore.setVirtualUid(BActivityThread.getBUid());
        assert packageContext != null;
        IOCore.get().enableRedirect(packageContext);
        ContextCompat.fixVirtual(packageContext, packageName);
        NativeCore.setNativeSandboxEnvironment(packageName, processName);
        if (isNativeTerminationShieldDiagnosticEnabled()) {
            NativeCore.setNativeTerminationShieldPackage(packageName);
        }
        if (BlackBoxCore.get().isDexDumpEnabled()) {
            new DexDumpProxy().injectHook();
        }

        AppBindData bindData = new AppBindData();
        bindData.appInfo = applicationInfo;
        bindData.processName = processName;
        bindData.info = loadedApk;
        bindData.providers = mProviders;
        bindData.compatibilityInfo = createCompatibilityInfo(applicationInfo);
        applyCompatibilityInfo(boundApplication, loadedApk, bindData.compatibilityInfo);

        ActivityThreadAppBindDataContext activityThreadAppBindData = BRActivityThreadAppBindData.get(boundApplication);
        activityThreadAppBindData._set_instrumentationName(new ComponentName(bindData.appInfo.packageName, Instrumentation.class.getName()));
        activityThreadAppBindData._set_appInfo(bindData.appInfo);
        activityThreadAppBindData._set_info(bindData.info);
        if (bindData.compatibilityInfo != null) {
            activityThreadAppBindData._set_compatInfo(bindData.compatibilityInfo);
        }
        activityThreadAppBindData._set_processName(bindData.processName);
        activityThreadAppBindData._set_providers(bindData.providers);

        mBoundApplication = bindData;
        BinderMonitorConfig binderMonitorConfig = BinderMonitorConfig.load(BlackBoxCore.getContext());
        binderMonitorConfig = binderMonitorConfig.withLogcat(BlackBoxCore.get().isDiagnosticLogcatEnabled());
        BlackBoxBinderMonitor.init(
                BlackBoxCore.getContext(),
                binderMonitorConfig,
                createBinderMonitorIdentity(packageName, processName));
        BlackBoxBinderMonitor.setTransactInterceptor(new PackageManagerBinderInterceptor());
        NativeCore.enableBinderMonitor(
                binderMonitorConfig.isEnabled() && binderMonitorConfig.isRecordNative(),
                binderMonitorConfig.isEnabled() && binderMonitorConfig.isRecordIoctl());

        //ssl适配
        if (BRNetworkSecurityConfigProvider.getRealClass() != null) {
            Security.removeProvider("AndroidNSSP");
            BRNetworkSecurityConfigProvider.get().install(packageContext);
        }
        Application application;
        try {
            logApplicationIdentity(packageName, processName, applicationInfo);
            logContextIdentity("beforeMakeApplication", packageContext, packageName);
            onBeforeCreateApplication(packageName, processName, packageContext);
            logApplicationBoundary("beforeMakeApplication", packageContext, null, loadedApk);
            application = BRLoadedApk.getWithException(loadedApk).makeApplication(false, null);
            logApplicationBoundary("afterMakeApplication", packageContext, application, loadedApk);
            mInitialApplication = application;
            BRActivityThread.get(BlackBoxCore.mainThread())._set_mInitialApplication(mInitialApplication);
            logInitialApplicationState("afterSetInitialApplication", mInitialApplication, loadedApk);
            ContextCompat.fix((Context) BRActivityThread.get(BlackBoxCore.mainThread()).getSystemContext());
            ContextCompat.fixVirtual(mInitialApplication, packageName);
            NativeCore.disableEarlyProcMapsShim();
            scheduleClassLoaderDumpIfEnabled(application.getClassLoader(), packageName,
                    "BActivityThread.afterMakeApplication");
            logApplicationBoundary("beforeInstallProviders", mInitialApplication, application, loadedApk);
            installProviders(mInitialApplication, bindData.processName, bindData.providers);
            logApplicationBoundary("afterInstallProviders", mInitialApplication, application, loadedApk);

            onBeforeApplicationOnCreate(packageName, processName, application);
            logApplicationBoundary("beforeApplicationOnCreate", mInitialApplication, application, loadedApk);
            AppInstrumentation.get().callApplicationOnCreate(application);
            logApplicationBoundary("afterApplicationOnCreate", mInitialApplication, application, loadedApk);
            mInitialApplication = syncInitialApplicationFromRuntime(mInitialApplication, loadedApk);
            application = mInitialApplication;
            ContextCompat.fixVirtual(mInitialApplication, packageName);
            scheduleClassLoaderDumpIfEnabled(application.getClassLoader(), packageName,
                    "BActivityThread.afterApplicationOnCreate");
            onAfterApplicationOnCreate(packageName, processName, application);
            logInitialApplicationState("afterApplicationOnCreate", mInitialApplication, loadedApk);

            HookManager.get().checkEnv(HCallbackProxy.class);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Unable to makeApplication", e);
        }
    }

    public static Context createPackageContext(ApplicationInfo info) {
        try {
            return BlackBoxCore.getContext().createPackageContext(info.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static void scheduleClassLoaderDumpIfEnabled(ClassLoader classLoader, String packageName,
                                                         String sourceTag) {
        if (!BlackBoxCore.get().isDexDumpEnabled()) {
            return;
        }
        DexDumpProxy.scheduleClassLoaderDump(classLoader, packageName, sourceTag);
    }

    static boolean isNativeTerminationShieldDiagnosticEnabled() {
        return DiagnosticSwitch.isTruthy(System.getenv(NATIVE_TERMINATION_SHIELD_ENV))
                || DiagnosticSwitch.isTruthy(System.getProperty(NATIVE_TERMINATION_SHIELD_PROPERTY))
                || DiagnosticSwitch.isTruthy(SystemPropertiesCompat.get(
                        NATIVE_TERMINATION_SHIELD_SYSTEM_PROPERTY));
    }

    private static boolean isAppLifecycleDiagnosticsEnabled() {
        return BlackBoxCore.get().isDiagnosticLogcatEnabled()
                && (DiagnosticSwitch.isTruthy(System.getenv(APP_LIFECYCLE_DIAG_ENV))
                || DiagnosticSwitch.isTruthy(System.getProperty(APP_LIFECYCLE_DIAG_PROPERTY))
                || DiagnosticSwitch.isTruthy(SystemPropertiesCompat.get(
                        APP_LIFECYCLE_DIAG_SYSTEM_PROPERTY)));
    }

    public void ensureInitialApplicationState(String stage) {
        if (mBoundApplication == null || mBoundApplication.info == null) {
            return;
        }
        mInitialApplication = syncInitialApplicationFromRuntime(mInitialApplication, mBoundApplication.info);
        if (mInitialApplication != null) {
            String packageName = getAppPackageName();
            if (packageName != null) {
                ContextCompat.fixVirtual(mInitialApplication, packageName);
            }
        }
        logInitialApplicationState(stage, mInitialApplication, mBoundApplication.info);
    }

    private static Application syncInitialApplicationFromRuntime(Application localApplication, Object loadedApk) {
        Application loadedApkApplication = null;
        Application threadInitialApplication = null;
        try {
            loadedApkApplication = BRLoadedApk.get(loadedApk).mApplication();
        } catch (Throwable ignored) {
        }
        try {
            threadInitialApplication = BRActivityThread.get(BlackBoxCore.mainThread()).mInitialApplication();
        } catch (Throwable ignored) {
        }

        Application syncedApplication = localApplication;
        if (loadedApkApplication != null && loadedApkApplication != localApplication) {
            syncedApplication = loadedApkApplication;
        } else if (threadInitialApplication != null && threadInitialApplication != localApplication) {
            syncedApplication = threadInitialApplication;
        } else if (syncedApplication == null && loadedApkApplication != null) {
            syncedApplication = loadedApkApplication;
        } else if (syncedApplication == null) {
            syncedApplication = threadInitialApplication;
        }

        if (syncedApplication != null) {
            boolean restoredThreadInitial = false;
            boolean restoredLoadedApk = false;
            String threadRestoreError = null;
            String loadedApkRestoreError = null;
            if (threadInitialApplication != syncedApplication) {
                try {
                    BRActivityThread.get(BlackBoxCore.mainThread())._set_mInitialApplication(syncedApplication);
                    restoredThreadInitial = true;
                } catch (Throwable e) {
                    threadRestoreError = e.getClass().getName() + ":" + e.getMessage();
                }
            }
            if (loadedApkApplication != syncedApplication) {
                try {
                    BRLoadedApk.get(loadedApk)._set_mApplication(syncedApplication);
                    restoredLoadedApk = true;
                } catch (Throwable e) {
                    loadedApkRestoreError = e.getClass().getName() + ":" + e.getMessage();
                }
            }
            if (syncedApplication != localApplication || restoredThreadInitial || restoredLoadedApk
                    || threadRestoreError != null || loadedApkRestoreError != null) {
                if (isAppLifecycleDiagnosticsEnabled()) {
                    Slog.i(TAG, "Synced initial application from runtime"
                            + " previous=" + describeApplication(localApplication)
                            + " synced=" + describeApplication(syncedApplication)
                            + " loadedApk=" + describeApplication(loadedApkApplication)
                            + " threadInitial=" + describeApplication(threadInitialApplication)
                            + " restoredThreadInitial=" + restoredThreadInitial
                            + " restoredLoadedApk=" + restoredLoadedApk
                            + (threadRestoreError == null ? "" : " threadRestoreError=" + threadRestoreError)
                            + (loadedApkRestoreError == null ? "" : " loadedApkRestoreError=" + loadedApkRestoreError));
                }
            }
        } else if (isAppLifecycleDiagnosticsEnabled()) {
            Slog.i(TAG, "Synced initial application from runtime"
                    + " previous=" + describeApplication(localApplication)
                    + " synced=null"
                    + " loadedApk=" + describeApplication(loadedApkApplication)
                    + " threadInitial=" + describeApplication(threadInitialApplication));
        }
        return syncedApplication;
    }

    private static void logApplicationIdentity(String packageName, String processName, ApplicationInfo applicationInfo) {
        if (!isAppLifecycleDiagnosticsEnabled()) {
            return;
        }
        int libcoreUid = -1;
        String libcoreUidError = null;
        try {
            libcoreUid = android.system.Os.getuid();
        } catch (Throwable e) {
            libcoreUidError = e.getClass().getName() + ":" + e.getMessage();
        }
        int appInfoUid = applicationInfo == null ? -1 : applicationInfo.uid;
        Slog.i(TAG, "Virtual identity before makeApplication"
                + " package=" + packageName
                + " process=" + processName
                + " pid=" + Process.myPid()
                + " linuxUid=" + Process.myUid()
                + " libcoreUid=" + libcoreUid
                + " hostUid=" + BlackBoxCore.getHostUid()
                + " appConfigUid=" + BActivityThread.getUid()
                + " virtualUid=" + BActivityThread.getBUid()
                + " virtualAppId=" + BActivityThread.getBAppId()
                + " applicationInfo.uid=" + appInfoUid
                + " applicationInfoAppId=" + BUserHandle.getAppId(appInfoUid)
                + (libcoreUidError == null ? "" : " libcoreUidError=" + libcoreUidError));
    }

    private static void logContextIdentity(String stage, Context context, String expectedPackage) {
        if (!isAppLifecycleDiagnosticsEnabled()) {
            return;
        }
        if (context == null) {
            Slog.i(TAG, "Package context identity stage=" + stage
                    + " expectedPackage=" + expectedPackage
                    + " context=null");
            return;
        }

        String packageName;
        try {
            packageName = context.getPackageName();
        } catch (Throwable e) {
            packageName = "error:" + e.getClass().getName() + ":" + e.getMessage();
        }

        String opPackageName;
        try {
            opPackageName = Reflector.QuietReflector.with(context).method("getOpPackageName").call();
        } catch (Throwable e) {
            opPackageName = "error:" + e.getClass().getName() + ":" + e.getMessage();
        }

        String dataDir;
        try {
            File dir = context.getDataDir();
            dataDir = dir == null ? "null" : dir.getAbsolutePath();
        } catch (Throwable e) {
            dataDir = "error:" + e.getClass().getName() + ":" + e.getMessage();
        }

        String filesDir;
        try {
            File dir = context.getFilesDir();
            filesDir = dir == null ? "null" : dir.getAbsolutePath();
        } catch (Throwable e) {
            filesDir = "error:" + e.getClass().getName() + ":" + e.getMessage();
        }

        String nativeLibraryDir;
        String sourceDir;
        String appDataDir;
        try {
            nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
            sourceDir = context.getApplicationInfo().sourceDir;
            appDataDir = context.getApplicationInfo().dataDir;
        } catch (Throwable e) {
            nativeLibraryDir = "error:" + e.getClass().getName() + ":" + e.getMessage();
            sourceDir = nativeLibraryDir;
            appDataDir = nativeLibraryDir;
        }

        String classLoader;
        try {
            ClassLoader loader = context.getClassLoader();
            classLoader = loader == null ? "null" : loader.toString();
        } catch (Throwable e) {
            classLoader = "error:" + e.getClass().getName() + ":" + e.getMessage();
        }

        Slog.i(TAG, "Package context identity"
                + " stage=" + stage
                + " expectedPackage=" + expectedPackage
                + " packageName=" + packageName
                + " opPackageName=" + opPackageName
                + " dataDir=" + dataDir
                + " filesDir=" + filesDir
                + " appInfoDataDir=" + appDataDir
                + " nativeLibraryDir=" + nativeLibraryDir
                + " sourceDir=" + sourceDir
                + " classLoader=" + classLoader);
    }

    private static void logInitialApplicationState(String stage, Application localApplication, Object loadedApk) {
        if (!isAppLifecycleDiagnosticsEnabled()) {
            return;
        }
        Application threadInitial = null;
        Application loadedApkApplication = null;
        String threadInitialError = null;
        String loadedApkError = null;
        try {
            threadInitial = BRActivityThread.get(BlackBoxCore.mainThread()).mInitialApplication();
        } catch (Throwable e) {
            threadInitialError = e.getClass().getName() + ":" + e.getMessage();
        }
        try {
            loadedApkApplication = BRLoadedApk.get(loadedApk).mApplication();
        } catch (Throwable e) {
            loadedApkError = e.getClass().getName() + ":" + e.getMessage();
        }
        Slog.i(TAG, "ActivityThread initial application state"
                + " stage=" + stage
                + " localApplication=" + describeApplication(localApplication)
                + " threadInitialApplication=" + describeApplication(threadInitial)
                + " threadInitialSameLocal=" + (threadInitial == localApplication)
                + " loadedApkApplication=" + describeApplication(loadedApkApplication)
                + " loadedApkSameLocal=" + (loadedApkApplication == localApplication)
                + (threadInitialError == null ? "" : " threadInitialError=" + threadInitialError)
                + (loadedApkError == null ? "" : " loadedApkError=" + loadedApkError));
    }

    private static String describeApplication(Application application) {
        if (application == null) {
            return "null";
        }
        return application.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(application));
    }

    private static void logApplicationBoundary(String stage, Context context, Application application, Object loadedApk) {
        if (!isAppLifecycleDiagnosticsEnabled()) {
            return;
        }
        Slog.i(TAG, "Application lifecycle boundary"
                + " stage=" + stage
                + " context=" + describeContext(context)
                + " application=" + describeApplication(application)
                + " loadedApkApplication=" + describeApplication(getLoadedApkApplicationQuietly(loadedApk))
                + " contextClassLoader=" + describeClassLoader(getContextClassLoaderQuietly(context))
                + " applicationClassLoader=" + describeClassLoader(getApplicationClassLoaderQuietly(application))
                + " loadedApkClassLoader=" + describeClassLoader(getLoadedApkClassLoaderQuietly(loadedApk))
                + " threadContextClassLoader=" + describeClassLoader(Thread.currentThread().getContextClassLoader()));
    }

    private static String describeContext(Context context) {
        if (context == null) {
            return "null";
        }
        return context.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(context));
    }

    private static ClassLoader getContextClassLoaderQuietly(Context context) {
        if (context == null) {
            return null;
        }
        try {
            return context.getClassLoader();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ClassLoader getApplicationClassLoaderQuietly(Application application) {
        if (application == null) {
            return null;
        }
        try {
            return application.getClassLoader();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ClassLoader getLoadedApkClassLoaderQuietly(Object loadedApk) {
        if (loadedApk == null) {
            return null;
        }
        try {
            return BRLoadedApk.get(loadedApk).getClassLoader();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Application getLoadedApkApplicationQuietly(Object loadedApk) {
        if (loadedApk == null) {
            return null;
        }
        try {
            return BRLoadedApk.get(loadedApk).mApplication();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String describeClassLoader(ClassLoader classLoader) {
        if (classLoader == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(classLoader.getClass().getName())
                .append('@')
                .append(Integer.toHexString(System.identityHashCode(classLoader)))
                .append('[')
                .append(String.valueOf(classLoader))
                .append(']');
        ClassLoader parent = classLoader.getParent();
        if (parent != null) {
            builder.append(" parent=")
                    .append(parent.getClass().getName())
                    .append('@')
                    .append(Integer.toHexString(System.identityHashCode(parent)));
        }
        return builder.toString();
    }

    private static void resetAppComponentFactory(ApplicationInfo applicationInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || applicationInfo == null) {
            return;
        }
        String factory = applicationInfo.appComponentFactory;
        if ("android.support.v4.app.CoreComponentFactory".equals(factory)
                || "androidx.core.app.CoreComponentFactory".equals(factory)) {
            Slog.i(TAG, "reset AppComponentFactory appComponentFactory=" + factory
                    + " package=" + applicationInfo.packageName);
            applicationInfo.appComponentFactory = null;
        }
    }

    private void installProviders(Context context, String processName, List<ProviderInfo> provider) {
        long origId = Binder.clearCallingIdentity();
        try {
            for (ProviderInfo providerInfo : provider) {
                try {
                    if (processName.equals(providerInfo.processName) ||
                            providerInfo.processName.equals(context.getPackageName()) || providerInfo.multiprocess) {
                        installProvider(BlackBoxCore.mainThread(), context, providerInfo, null);
                    }
                } catch (Throwable e) {
                    Slog.e(TAG, "install provider failed"
                            + " name=" + providerInfo.name
                            + " authority=" + providerInfo.authority
                            + " providerProcess=" + providerInfo.processName
                            + " appProcess=" + processName
                            + " package=" + providerInfo.packageName, e);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
            ContentProviderDelegate.init();
        }
    }

    public Object getPackageInfo() {
        return mBoundApplication.info;
    }

    public Object getCompatibilityInfo() {
        return mBoundApplication == null ? null : mBoundApplication.compatibilityInfo;
    }

    private static Object createCompatibilityInfo(ApplicationInfo applicationInfo) {
        if (applicationInfo == null) {
            return null;
        }
        try {
            Configuration configuration = BlackBoxCore.getContext().getResources().getConfiguration();
            boolean forceCompat = applicationInfo.targetSdkVersion > 0
                    && applicationInfo.targetSdkVersion < Build.VERSION_CODES.O;
            return BRCompatibilityInfo.get()._new(
                    applicationInfo,
                    configuration.screenLayout,
                    configuration.smallestScreenWidthDp,
                    forceCompat);
        } catch (Throwable e) {
            Slog.d(TAG, "create CompatibilityInfo failed: " + e);
            return null;
        }
    }

    private static void applyCompatibilityInfo(Object boundApplication, Object loadedApk, Object compatibilityInfo) {
        if (compatibilityInfo == null) {
            return;
        }
        try {
            BRLoadedApk.get(loadedApk).setCompatibilityInfo(compatibilityInfo);
        } catch (Throwable ignored) {
        }
        try {
            BRLoadedApkICS.get(loadedApk)._set_mCompatibilityInfo(compatibilityInfo);
        } catch (Throwable ignored) {
        }
        try {
            BRActivityThreadAppBindData.get(boundApplication)._set_compatInfo(compatibilityInfo);
        } catch (Throwable ignored) {
        }
    }

    public static void installProvider(Object mainThread, Context context, ProviderInfo providerInfo, Object holder) throws Throwable {
        Method installProvider = findInstallProviderMethod(mainThread.getClass());
        if (installProvider != null) {
            installProvider.setAccessible(true);
            installProvider.invoke(mainThread, context, holder, providerInfo, false, true, true);
        }
    }

    private static Method findInstallProviderMethod(Class<?> activityThreadClass) {
        for (Class<?> clazz = activityThreadClass; clazz != null; clazz = clazz.getSuperclass()) {
            for (Method installProvider : clazz.getDeclaredMethods()) {
                if (!"installProvider".equals(installProvider.getName())) {
                    continue;
                }
                Class<?>[] parameterTypes = installProvider.getParameterTypes();
                if (installProvider.getParameterTypes().length == 6
                        && Context.class.isAssignableFrom(parameterTypes[0])
                        && ProviderInfo.class.isAssignableFrom(parameterTypes[2])
                        && boolean.class == parameterTypes[3]
                        && boolean.class == parameterTypes[4]
                        && boolean.class == parameterTypes[5]) {
                    return installProvider;
                }
            }
        }
        return null;
    }

    public void loadXposed(Context context) {
        String vPackageName = getAppPackageName();
        String vProcessName = getAppProcessName();
        if (!TextUtils.isEmpty(vPackageName) && !TextUtils.isEmpty(vProcessName) && BXposedManager.get().isXPEnable()) {
            assert vPackageName != null;
            assert vProcessName != null;

            boolean isFirstApplication = vPackageName.equals(vProcessName);

            List<InstalledModule> installedModules = BXposedManager.get().getInstalledModules();
            for (InstalledModule installedModule : installedModules) {
                if (!installedModule.enable) {
                    continue;
                }
                try {
                    PineXposed.loadModule(new File(installedModule.getApplication().sourceDir));
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
            try {
                PineXposed.onPackageLoad(vPackageName, vProcessName, context.getApplicationInfo(), isFirstApplication, context.getClassLoader());
            } catch (Throwable ignored) {
            }
        }
        if (BlackBoxCore.get().isHideXposed()) {
            NativeCore.hideXposed();
        }
    }

    @Override
    public IBinder getActivityThread() {
        return BRActivityThread.get(BlackBoxCore.mainThread()).getApplicationThread();
    }

    @Override
    public void bindApplication() {
        if (!isInit()) {
            bindApplication(getAppPackageName(), getAppProcessName());
        }
    }

    @Override
    public void stopService(Intent intent) {
        AppServiceDispatcher.get().stopService(intent);
    }

    @Override
    public void restartJobService(String selfId) throws RemoteException {

    }

    @Override
    public IBinder acquireContentProviderClient(ProviderInfo providerInfo) throws RemoteException {
        if (!isInit()) {
            bindApplication(BActivityThread.getAppConfig().packageName, BActivityThread.getAppConfig().processName);
        }
        String[] split = providerInfo.authority.split(";");
        for (String auth : split) {
            ContentProviderClient contentProviderClient = BlackBoxCore.getContext()
                    .getContentResolver().acquireContentProviderClient(auth);
            IInterface iInterface = BRContentProviderClient.get(contentProviderClient).mContentProvider();
            if (iInterface == null)
                continue;
            return iInterface.asBinder();
        }
        return null;
    }

    @Override
    public IBinder peekService(Intent intent) {
        return AppServiceDispatcher.get().peekService(intent);
    }

    @Override
    public void finishActivity(final IBinder token) {
        mH.post(() -> {
            Map<IBinder, Object> activities = BRActivityThread.get(BlackBoxCore.mainThread()).mActivities();
            if (activities.isEmpty())
                return;
            Object clientRecord = activities.get(token);
            if (clientRecord == null)
                return;
            Activity activity = getActivityByToken(token);

            while (activity.getParent() != null) {
                activity = activity.getParent();
            }

            int resultCode = BRActivity.get(activity).mResultCode();
            Intent resultData = BRActivity.get(activity).mResultData();
            ActivityManagerCompat.finishActivity(token, resultCode, resultData);
            BRActivity.get(activity)._set_mFinished(true);
        });
    }

    @Override
    public void handleNewIntent(final IBinder token, final Intent intent) {
        mH.post(() -> {
            Intent newIntent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                newIntent = BRReferrerIntent.get()._new(intent, BlackBoxCore.getHostPkg());
            } else {
                newIntent = intent;
            }
            Object mainThread = BlackBoxCore.mainThread();
            if (BRActivityThread.get(BlackBoxCore.mainThread())._check_performNewIntents(null, null) != null) {
                BRActivityThread.get(mainThread).performNewIntents(
                        token,
                        Collections.singletonList(newIntent)
                );
            } else if (BRActivityThreadNMR1.get(mainThread)._check_performNewIntents(null, null, false) != null) {
                BRActivityThreadNMR1.get(mainThread).performNewIntents(
                        token,
                        Collections.singletonList(newIntent),
                        true);
            } else if (BRActivityThreadQ.get(mainThread)._check_handleNewIntent(null, null) != null) {
                BRActivityThreadQ.get(mainThread).handleNewIntent(token, Collections.singletonList(newIntent));
            }
        });
    }

    @Override
    public void scheduleReceiver(ReceiverData data) throws RemoteException {
        if (!isInit()) {
            bindApplication();
        }
        mH.post(() -> {
            BroadcastReceiver mReceiver = null;
            Intent intent = data.intent;
            ActivityInfo activityInfo = data.activityInfo;
            BroadcastReceiver.PendingResult pendingResult = data.data.build();

            try {
                Context baseContext = mInitialApplication.getBaseContext();
                ClassLoader classLoader = baseContext.getClassLoader();
                intent.setExtrasClassLoader(classLoader);

                mReceiver = (BroadcastReceiver) classLoader.loadClass(activityInfo.name).newInstance();
                BRBroadcastReceiver.get(mReceiver).setPendingResult(pendingResult);
                mReceiver.onReceive(baseContext, intent);
                BroadcastReceiver.PendingResult finish = BRBroadcastReceiver.get(mReceiver).getPendingResult();
                if (finish != null) {
                    finish.finish();
                }
                BlackBoxCore.getBActivityManager().finishBroadcast(data.data);
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                Slog.e(TAG,
                        "Error receiving broadcast " + intent
                                + " in " + mReceiver);
            }
        });
    }

    public static Activity getActivityByToken(IBinder token) {
        Map<IBinder, Object> iBinderObjectMap =
                BRActivityThread.get(BlackBoxCore.mainThread()).mActivities();
        return BRActivityThreadActivityClientRecord.get(iBinderObjectMap.get(token)).activity();
    }

    private void onBeforeCreateApplication(String packageName, String processName, Context context) {
        for (AppLifecycleCallback appLifecycleCallback : BlackBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.beforeCreateApplication(packageName, processName, context, BActivityThread.getUserId());
        }
    }

    private void onBeforeApplicationOnCreate(String packageName, String processName, Application application) {
        for (AppLifecycleCallback appLifecycleCallback : BlackBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.beforeApplicationOnCreate(packageName, processName, application, BActivityThread.getUserId());
        }
    }

    private void onAfterApplicationOnCreate(String packageName, String processName, Application application) {
        for (AppLifecycleCallback appLifecycleCallback : BlackBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.afterApplicationOnCreate(packageName, processName, application, BActivityThread.getUserId());
        }
    }

    private static VirtualIdentity createBinderMonitorIdentity(String packageName, String processName) {
        int virtualPid = BActivityThread.getAppPid();
        return new VirtualIdentity(
                ProxyManifest.getProcessName(virtualPid),
                packageName,
                processName,
                BActivityThread.getBUid(),
                BActivityThread.getUserId(),
                virtualPid);
    }

    public static class AppBindData {
        String processName;
        ApplicationInfo appInfo;
        List<ProviderInfo> providers;
        Object info;
        Object compatibilityInfo;
    }
}
