package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.Map;

import black.android.os.BRServiceManager;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.niunaijun.blackbox.binder.BlackBoxBinderMonitor;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.utils.Slog;

public class ServiceManagerProxy implements IInjectHook {
    private static final String TAG = "ServiceManagerProxy";
    private static final String ANDROID_SERVICE_MANAGER = "android.os.ServiceManager";
    private static final String PACKAGE_SERVICE = "package";
    private static final String SERVICE_MANAGER = "servicemanager";
    private static final String ISERVICE_MANAGER = "android.os.IServiceManager";

    @Override
    public void injectHook() {
        try {
            Class<?> serviceManager = Class.forName(ANDROID_SERVICE_MANAGER);
            hookServiceLookup(serviceManager, "getService");
            hookServiceLookup(serviceManager, "checkService");
            hookServiceLookup(serviceManager, "waitForService");
            hookServiceLookup(serviceManager, "rawGetService");
        } catch (Throwable e) {
            Slog.d(TAG, "hook android.os.ServiceManager failed: " + e);
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    private void hookServiceLookup(Class<?> serviceManager, final String methodName) {
        try {
            Method method = serviceManager.getDeclaredMethod(methodName, String.class);
            method.setAccessible(true);
            Pine.hook(method, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    IBinder cachedBinder = getCachedPackageBinder(callFrame.args);
                    if (cachedBinder == null) {
                        return;
                    }
                    recordRedirect(methodName, cachedBinder);
                    callFrame.setResult(cachedBinder);
                }
            });
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable e) {
            Slog.d(TAG, "hook ServiceManager." + methodName + " failed: " + e);
        }
    }

    private static IBinder getCachedPackageBinder(Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof String)) {
            return null;
        }
        String name = (String) args[0];
        if (!PACKAGE_SERVICE.equals(name)) {
            return null;
        }
        Map<String, IBinder> services = BRServiceManager.get().sCache();
        if (services == null) {
            return null;
        }
        return services.get(name);
    }

    private static void recordRedirect(String methodName, IBinder cachedBinder) {
        BlackBoxBinderMonitor.recordProxyCall(
                SERVICE_MANAGER,
                ISERVICE_MANAGER,
                methodName,
                ServiceManagerProxy.class.getSimpleName(),
                "name=" + PACKAGE_SERVICE,
                cachedBinder.getClass().getName(),
                "handled",
                false,
                true,
                false);
    }
}
