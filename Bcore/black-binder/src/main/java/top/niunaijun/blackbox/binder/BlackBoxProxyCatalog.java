package top.niunaijun.blackbox.binder;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class BlackBoxProxyCatalog {
    private static final Map<String, String> SERVICE_DESCRIPTORS;
    private static final Map<String, String> PROXY_SERVICES;

    static {
        Map<String, String> serviceDescriptors = new HashMap<>();
        serviceDescriptors.put("activity", "android.app.IActivityManager");
        serviceDescriptors.put("activity_task", "android.app.IActivityTaskManager");
        serviceDescriptors.put("package", "android.content.pm.IPackageManager");
        serviceDescriptors.put("content", "android.content.IContentService");
        serviceDescriptors.put("content_provider", "android.content.IContentProvider");
        serviceDescriptors.put("settings_provider", "android.content.IContentProvider");
        serviceDescriptors.put("account", "android.accounts.IAccountManager");
        serviceDescriptors.put("location", "android.location.ILocationManager");
        serviceDescriptors.put("appops", "com.android.internal.app.IAppOpsService");
        serviceDescriptors.put("notification", "android.app.INotificationManager");
        serviceDescriptors.put("clipboard", "android.content.IClipboard");
        serviceDescriptors.put("user", "android.os.IUserManager");
        serviceDescriptors.put("device_policy", "android.app.admin.IDevicePolicyManager");
        serviceDescriptors.put("alarm", "android.app.IAlarmManager");
        serviceDescriptors.put("appwidget", "com.android.internal.appwidget.IAppWidgetService");
        serviceDescriptors.put("connectivity", "android.net.IConnectivityManager");
        serviceDescriptors.put("jobscheduler", "android.app.job.IJobScheduler");
        serviceDescriptors.put("launcherapps", "android.content.pm.ILauncherApps");
        serviceDescriptors.put("media_session", "android.media.session.ISessionManager");
        serviceDescriptors.put("power", "android.os.IPowerManager");
        serviceDescriptors.put("restrictions", "android.content.IRestrictionsManager");
        serviceDescriptors.put("phone", "com.android.internal.telephony.ITelephony");
        serviceDescriptors.put("iphonesubinfo", "com.android.internal.telephony.IPhoneSubInfo");
        serviceDescriptors.put("window", "android.view.IWindowManager");
        serviceDescriptors.put("wifi", "android.net.wifi.IWifiManager");
        serviceDescriptors.put("permissionmgr", "android.permission.IPermissionManager");
        serviceDescriptors.put("mount", "android.os.storage.IStorageManager");
        serviceDescriptors.put("storagestats", "android.app.usage.IStorageStatsManager");
        serviceDescriptors.put("storage_stats", "android.app.usage.IStorageStatsManager");
        serviceDescriptors.put("shortcut", "android.content.pm.IShortcutService");
        serviceDescriptors.put("autofill", "android.view.autofill.IAutoFillManager");
        serviceDescriptors.put("accessibility", "android.view.accessibility.IAccessibilityManager");
        serviceDescriptors.put("telephony.registry", "com.android.internal.telephony.ITelephonyRegistry");
        serviceDescriptors.put("media_router", "android.media.IMediaRouterService");
        serviceDescriptors.put("vibrator", "android.os.IVibratorService");
        serviceDescriptors.put("fingerprint", "android.hardware.fingerprint.IFingerprintService");
        serviceDescriptors.put("wifiscanner", "android.net.wifi.IWifiScanner");
        serviceDescriptors.put("netd", "android.os.INetworkManagementService");
        serviceDescriptors.put("network_management", "android.os.INetworkManagementService");
        serviceDescriptors.put("display", "android.hardware.display.IDisplayManager");
        SERVICE_DESCRIPTORS = Collections.unmodifiableMap(serviceDescriptors);

        Map<String, String> proxyServices = new HashMap<>();
        proxyServices.put("IActivityManagerProxy", "activity");
        proxyServices.put("IActivityTaskManagerProxy", "activity_task");
        proxyServices.put("IPackageManagerProxy", "package");
        proxyServices.put("ContentServiceStub", "content");
        proxyServices.put("ContentProviderStub", "content_provider");
        proxyServices.put("SystemProviderStub", "settings_provider");
        proxyServices.put("IAccountManagerProxy", "account");
        proxyServices.put("ILocationManagerProxy", "location");
        proxyServices.put("IAppOpsManagerProxy", "appops");
        proxyServices.put("INotificationManagerProxy", "notification");
        proxyServices.put("IClipboardManagerProxy", "clipboard");
        proxyServices.put("IUserManagerProxy", "user");
        proxyServices.put("IDevicePolicyManagerProxy", "device_policy");
        proxyServices.put("IAlarmManagerProxy", "alarm");
        proxyServices.put("IAppWidgetManagerProxy", "appwidget");
        proxyServices.put("IConnectivityManagerProxy", "connectivity");
        proxyServices.put("IJobServiceProxy", "jobscheduler");
        proxyServices.put("ILauncherAppsProxy", "launcherapps");
        proxyServices.put("IMediaSessionManagerProxy", "media_session");
        proxyServices.put("IPowerManagerProxy", "power");
        proxyServices.put("RestrictionsManagerStub", "restrictions");
        proxyServices.put("ITelephonyManagerProxy", "phone");
        proxyServices.put("IPhoneSubInfoProxy", "iphonesubinfo");
        proxyServices.put("IWindowManagerProxy", "window");
        proxyServices.put("IWifiManagerProxy", "wifi");
        proxyServices.put("IPermissionManagerProxy", "permissionmgr");
        proxyServices.put("IStorageManagerProxy", "mount");
        proxyServices.put("IStorageStatsManagerProxy", "storage_stats");
        proxyServices.put("IShortcutManagerProxy", "shortcut");
        proxyServices.put("IAutofillManagerProxy", "autofill");
        proxyServices.put("IAccessibilityManagerProxy", "accessibility");
        proxyServices.put("ITelephonyRegistryProxy", "telephony.registry");
        proxyServices.put("IMediaRouterServiceProxy", "media_router");
        proxyServices.put("IVibratorServiceProxy", "vibrator");
        proxyServices.put("IFingerprintManagerProxy", "fingerprint");
        proxyServices.put("IWifiScannerProxy", "wifiscanner");
        proxyServices.put("INetworkManagementServiceProxy", "network_management");
        proxyServices.put("IDisplayManagerProxy", "display");
        PROXY_SERVICES = Collections.unmodifiableMap(proxyServices);
    }

    private BlackBoxProxyCatalog() {
    }

    public static String getServiceName(String proxyClassSimpleName) {
        return proxyClassSimpleName == null ? null : PROXY_SERVICES.get(proxyClassSimpleName);
    }

    public static String getInterfaceDescriptor(String proxyClassSimpleName) {
        return getInterfaceDescriptorForService(getServiceName(proxyClassSimpleName));
    }

    public static String getInterfaceDescriptorForService(String serviceName) {
        return serviceName == null ? null : SERVICE_DESCRIPTORS.get(serviceName);
    }
}
