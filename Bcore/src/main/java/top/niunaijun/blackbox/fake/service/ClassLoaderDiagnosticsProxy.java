package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.binder.BlackBoxBinderMonitor;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.utils.DiagnosticSwitch;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.SystemPropertiesCompat;

public class ClassLoaderDiagnosticsProxy implements IInjectHook {
    private static final String TAG = "ClassLoaderDiagnosticsProxy";
    private static final String CLASSLOADER_SERVICE = "classloader";
    private static final String CLASSLOADER_DIAG_ENV = "BLACKBOX_CLASSLOADER_DIAG";
    private static final String CLASSLOADER_DIAG_JAVA_PROPERTY = "blackbox.classloader_diag";
    private static final String CLASSLOADER_DIAG_PROPERTY = "debug.blackbox.classloader_diag";
    private static final int MAX_FAILURE_RECORDS = 64;
    private static final AtomicInteger sFailureRecords = new AtomicInteger();
    private static boolean sInstalled;

    @Override
    public void injectHook() {
        if (!isDiagnosticsEnabled()) {
            Slog.d(TAG, "ClassLoader diagnostics disabled by debug property");
            return;
        }
        synchronized (ClassLoaderDiagnosticsProxy.class) {
            if (sInstalled) {
                return;
            }
            sInstalled = true;
        }
        hookLoadClass(String.class);
        hookLoadClass(String.class, boolean.class);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    private void hookLoadClass(Class<?>... parameterTypes) {
        try {
            Method method = ClassLoader.class.getDeclaredMethod("loadClass", parameterTypes);
            method.setAccessible(true);
            Pine.hook(method, new MethodHook() {
                @Override
                public void afterCall(Pine.CallFrame callFrame) {
                    Throwable throwable = callFrame.getThrowable();
                    if (!(throwable instanceof ClassNotFoundException)) {
                        return;
                    }
                    recordLoadClassFailure(callFrame, throwable);
                }
            });
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable e) {
            Slog.d(TAG, "hook ClassLoader.loadClass failed: " + e);
        }
    }

    private static void recordLoadClassFailure(Pine.CallFrame callFrame, Throwable throwable) {
        String packageName = currentPackageName();
        if (packageName == null || callFrame == null) {
            return;
        }
        int count = sFailureRecords.incrementAndGet();
        if (count > MAX_FAILURE_RECORDS) {
            return;
        }

        String className = firstStringArg(callFrame.args);
        ClassLoader classLoader = callFrame.thisObject instanceof ClassLoader
                ? (ClassLoader) callFrame.thisObject
                : null;
        String loaderSummary = classLoaderSummary(classLoader);
        String stack = stackTraceSummary();
        String argsSummary = "package=" + packageName
                + ", class=" + limit(className)
                + ", loader=" + loaderSummary
                + ", stack=" + stack;
        String resultSummary = throwable.getClass().getName() + ": " + limit(throwable.getMessage());
        Slog.d(TAG, "loadClass failed " + argsSummary + " throwable=" + resultSummary);
        BlackBoxBinderMonitor.recordProxyCall(
                CLASSLOADER_SERVICE,
                "java.lang.ClassLoader",
                "loadClass",
                ClassLoaderDiagnosticsProxy.class.getSimpleName(),
                argsSummary,
                resultSummary,
                "observed",
                false,
                false,
                false);
    }

    private static String firstStringArg(Object[] args) {
        if (args == null) {
            return "null";
        }
        for (Object arg : args) {
            if (arg instanceof String) {
                return (String) arg;
            }
        }
        return "unknown";
    }

    private static String classLoaderSummary(ClassLoader classLoader) {
        if (classLoader == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(classLoader.getClass().getName())
                .append('@')
                .append(Integer.toHexString(System.identityHashCode(classLoader)));
        ClassLoader parent = classLoader.getParent();
        if (parent != null) {
            builder.append(" parent=")
                    .append(parent.getClass().getName())
                    .append('@')
                    .append(Integer.toHexString(System.identityHashCode(parent)));
        }
        builder.append(" desc=").append(limit(String.valueOf(classLoader)));
        return builder.toString();
    }

    private static String stackTraceSummary() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (className.equals(Thread.class.getName())
                    || className.equals(ClassLoaderDiagnosticsProxy.class.getName())
                    || className.startsWith("top.canyie.pine.")) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" <- ");
            }
            builder.append(className)
                    .append('.')
                    .append(element.getMethodName())
                    .append(':')
                    .append(element.getLineNumber());
            count++;
            if (count >= 8) {
                break;
            }
        }
        return limit(builder.toString());
    }

    private static String limit(String value) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= 512) {
            return value;
        }
        return value.substring(0, 512) + "...";
    }

    private static String currentPackageName() {
        try {
            return BActivityThread.getAppPackageName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean isDiagnosticsEnabled() {
        return DiagnosticSwitch.isTruthy(System.getenv(CLASSLOADER_DIAG_ENV))
                || DiagnosticSwitch.isTruthy(System.getProperty(CLASSLOADER_DIAG_JAVA_PROPERTY))
                || DiagnosticSwitch.isTruthy(SystemPropertiesCompat.get(CLASSLOADER_DIAG_PROPERTY));
    }
}
