package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.utils.Slog;

public class FrameworkWtfProxy implements IInjectHook {
    private static final String TAG = "FrameworkWtfProxy";

    @Override
    public void injectHook() {
        try {
            Class<?> logClass = Class.forName("android.util.Log");
            hookWtf(logClass.getDeclaredMethod("wtf", String.class, String.class));
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable e) {
            Slog.d(TAG, "hook android.util.Log.wtf failed: " + e);
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    private static void hookWtf(Method method) {
        method.setAccessible(true);
        Pine.hook(method, new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame callFrame) {
                if (isLooperIdentityWtf(callFrame.args)) {
                    callFrame.setResult(0);
                }
            }
        });
    }

    private static boolean isLooperIdentityWtf(Object[] args) {
        if (args == null || args.length < 2) {
            return false;
        }
        Object tagArg = args[0];
        Object messageArg = args[1];
        if (!(tagArg instanceof String) || !(messageArg instanceof String)) {
            return false;
        }
        String tag = (String) tagArg;
        String message = (String) messageArg;
        return "Looper".equals(tag)
                && message.startsWith("Thread identity changed from");
    }
}
