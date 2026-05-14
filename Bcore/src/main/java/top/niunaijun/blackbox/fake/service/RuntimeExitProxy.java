package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.niunaijun.blackbox.binder.BlackBoxBinderMonitor;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.utils.Slog;

public class RuntimeExitProxy implements IInjectHook {
    private static final String TAG = "RuntimeExitProxy";
    private static final String RUNTIME_SERVICE = "runtime";
    private static final int SIGNAL_ABORT = 6;
    private static final int SIGNAL_KILL = 9;
    private static final int SIGNAL_TERM = 15;

    @Override
    public void injectHook() {
        try {
            Class<?> systemClass = Class.forName("java.lang.System");
            Class<?> runtimeClass = Class.forName("java.lang.Runtime");
            Class<?> processClass = Class.forName("android.os.Process");
            hookExitMethod(systemClass, "exit");
            hookExitMethod(runtimeClass, "exit");
            hookExitMethod(runtimeClass, "halt");
            hookProcessSignalMethod(processClass, "killProcess", int.class);
            hookProcessSignalMethod(processClass, "killProcessQuiet", int.class);
            hookProcessSignalMethod(processClass, "sendSignal", int.class, int.class);
            hookProcessSignalMethod(processClass, "sendSignalQuiet", int.class, int.class);
        } catch (Throwable e) {
            Slog.d(TAG, "hook runtime exit failed: " + e);
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    private void hookExitMethod(final Class<?> owner, final String methodName) {
        try {
            Method method = owner.getDeclaredMethod(methodName, int.class);
            method.setAccessible(true);
            Pine.hook(method, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    int status = parseStatus(callFrame.args);
                    if (!shouldBlockSandboxExit(status)) {
                        return;
                    }
                    String stack = stackTraceSummary();
                    Slog.d(TAG, "blocked " + owner.getName() + "." + methodName
                            + "(" + status + ") stack=" + stack);
                    recordBlockedExit(owner.getName(), methodName, status, stack);
                    callFrame.setResult(null);
                }
            });
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable e) {
            Slog.d(TAG, "hook " + owner.getName() + "." + methodName + " failed: " + e);
        }
    }

    private void hookProcessSignalMethod(final Class<?> owner, final String methodName,
                                         Class<?>... parameterTypes) {
        try {
            Method method = owner.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            Pine.hook(method, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    int pid = parsePid(callFrame.args);
                    int signal = parseSignal(methodName, callFrame.args);
                    String stack = stackTraceSummary();
                    if (!shouldBlockSandboxSignal(pid, signal)) {
                        if (shouldRecordSandboxSignal(pid, signal)) {
                            Slog.d(TAG, "observed " + owner.getName() + "." + methodName
                                    + "(pid=" + pid + ", signal=" + signal + ") stack=" + stack);
                            recordObservedProcessSignal(owner.getName(), methodName, pid, signal, stack);
                        }
                        return;
                    }
                    Slog.d(TAG, "blocked " + owner.getName() + "." + methodName
                            + "(pid=" + pid + ", signal=" + signal + ") stack=" + stack);
                    recordBlockedProcessSignal(owner.getName(), methodName, pid, signal, stack);
                    callFrame.setResult(null);
                }
            });
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable e) {
            Slog.d(TAG, "hook " + owner.getName() + "." + methodName + " failed: " + e);
        }
    }

    private static int parseStatus(Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof Number)) {
            return 0;
        }
        return ((Number) args[0]).intValue();
    }

    private static int parsePid(Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof Number)) {
            return -1;
        }
        return ((Number) args[0]).intValue();
    }

    private static int parseSignal(String methodName, Object[] args) {
        if ("killProcess".equals(methodName) || "killProcessQuiet".equals(methodName)) {
            return SIGNAL_KILL;
        }
        if (args == null || args.length < 2 || !(args[1] instanceof Number)) {
            return 0;
        }
        return ((Number) args[1]).intValue();
    }

    private static boolean shouldBlockSandboxExit(int status) {
        return true;
    }

    private static boolean shouldBlockSandboxSignal(int pid, int signal) {
        return pid == android.os.Process.myPid()
                && isTerminationSignal(signal);
    }

    private static boolean shouldRecordSandboxSignal(int pid, int signal) {
        return pid > 0 && isTerminationSignal(signal);
    }

    private static boolean isTerminationSignal(int signal) {
        return signal == SIGNAL_ABORT || signal == SIGNAL_KILL || signal == SIGNAL_TERM;
    }

    private static String stackTraceSummary() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (className.equals(Thread.class.getName())
                    || className.equals(RuntimeExitProxy.class.getName())
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
        return builder.toString();
    }

    private static void recordBlockedExit(String owner, String methodName, int status, String stack) {
        BlackBoxBinderMonitor.recordProxyCall(
                RUNTIME_SERVICE,
                owner,
                methodName,
                RuntimeExitProxy.class.getSimpleName(),
                "status=" + status + ", stack=" + stack,
                "blocked abnormal exit",
                "blocked",
                false,
                false,
                true);
    }

    private static void recordBlockedProcessSignal(String owner, String methodName, int pid,
                                                   int signal, String stack) {
        BlackBoxBinderMonitor.recordProxyCall(
                RUNTIME_SERVICE,
                owner,
                methodName,
                RuntimeExitProxy.class.getSimpleName(),
                "pid=" + pid + ", signal=" + signal + ", stack=" + stack,
                "blocked sandbox process signal",
                "blocked",
                false,
                false,
                true);
    }

    private static void recordObservedProcessSignal(String owner, String methodName, int pid,
                                                    int signal, String stack) {
        BlackBoxBinderMonitor.recordProxyCall(
                RUNTIME_SERVICE,
                owner,
                methodName,
                RuntimeExitProxy.class.getSimpleName(),
                "pid=" + pid + ", signal=" + signal + ", stack=" + stack,
                "observed android.os.Process signal",
                "forwarded",
                true,
                false,
                false);
    }
}
