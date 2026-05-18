package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.binder.BlackBoxBinderMonitor;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.utils.Slog;

public class ActivityThreadIdentityProxy implements IInjectHook {
    private static final String TAG = "ActivityThreadIdentityProxy";
    private static final String ACTIVITY_THREAD = "activity_thread";
    private static final String ACTIVITY_THREAD_CLASS = "android.app.ActivityThread";
    private static final String ACTIVITY_THREAD_DESCRIPTOR = "android.app.ActivityThread";

    @Override
    public void injectHook() {
        try {
            Class<?> activityThread = Class.forName(ACTIVITY_THREAD_CLASS);
            hookIdentityMethod(activityThread, "currentPackageName");
            hookIdentityMethod(activityThread, "currentProcessName");
            hookIdentityMethod(activityThread, "currentOpPackageName");
        } catch (Throwable e) {
            Slog.d(TAG, "hook ActivityThread identity failed: " + e);
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    private void hookIdentityMethod(Class<?> activityThread, final String methodName) {
        try {
            Method method = activityThread.getDeclaredMethod(methodName);
            method.setAccessible(true);
            Pine.hook(method, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    String value = resolveVirtualIdentity(methodName);
                    if (value == null || value.length() == 0) {
                        return;
                    }
                    recordVirtualIdentity(methodName, value);
                    callFrame.setResult(value);
                }
            });
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable e) {
            Slog.d(TAG, "hook ActivityThread." + methodName + " failed: " + e);
        }
    }

    private static String resolveVirtualIdentity(String methodName) {
        if ("currentProcessName".equals(methodName)) {
            return BActivityThread.getAppProcessName();
        }
        if ("currentPackageName".equals(methodName) || "currentOpPackageName".equals(methodName)) {
            return BActivityThread.getAppPackageName();
        }
        return null;
    }

    private static void recordVirtualIdentity(String methodName, String value) {
        BlackBoxBinderMonitor.recordProxyCall(
                ACTIVITY_THREAD,
                ACTIVITY_THREAD_DESCRIPTOR,
                methodName,
                ActivityThreadIdentityProxy.class.getSimpleName(),
                "0 args",
                value,
                "handled",
                false,
                true,
                false);
    }
}
