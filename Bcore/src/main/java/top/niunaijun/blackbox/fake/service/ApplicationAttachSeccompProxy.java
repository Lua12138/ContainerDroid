package top.niunaijun.blackbox.fake.service;

import android.app.Application;
import android.content.Context;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.binder.BlackBoxBinderMonitor;
import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.utils.Slog;

public class ApplicationAttachSeccompProxy implements IInjectHook {
    private static final String TAG = "ApplicationAttachSeccompProxy";
    private static final String SECCOMP_SERVICE = "seccomp";
    private static final String ATTACH_SECCOMP_ENV = "BLACKBOX_ATTACH_SECCOMP";
    private static final String ATTACH_SECCOMP_PROPERTY = "blackbox.attach_seccomp";
    private static final String ATTACH_SECCOMP_SYSTEM_PROPERTY = "debug.blackbox.attach_seccomp";
    private static final String ATTACH_TERMINATION_TRAP_ENV = "BLACKBOX_ATTACH_TERMINATION_TRAP";
    private static final String ATTACH_TERMINATION_TRAP_PROPERTY = "blackbox.attach_termination_trap";
    private static final String ATTACH_TERMINATION_TRAP_SYSTEM_PROPERTY = "debug.blackbox.attach_termination_trap";
    private static final String ATTACH_RAW_SYSCALL_PROBE_ENV = "BLACKBOX_ATTACH_RAW_SYSCALL_PROBE";
    private static final String ATTACH_RAW_SYSCALL_PROBE_PROPERTY = "blackbox.attach_raw_syscall_probe";
    private static final String ATTACH_RAW_SYSCALL_PROBE_SYSTEM_PROPERTY = "debug.blackbox.attach_raw_syscall_probe";

    private final AtomicBoolean installed = new AtomicBoolean();
    private final AtomicBoolean disabledLogged = new AtomicBoolean();
    private final AtomicBoolean terminationTrapInstalled = new AtomicBoolean();
    private final AtomicBoolean rawSyscallProbeInstalled = new AtomicBoolean();

    @Override
    public void injectHook() {
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            Pine.hook(attach, new MethodHook() {
                @Override
                public void afterCall(Pine.CallFrame callFrame) {
                    dumpApplicationClassLoaderAfterAttach(callFrame.thisObject);
                    if (!maybeInstallRawSyscallProbeAfterAttach(callFrame.thisObject)
                            && !maybeInstallTerminationTrapAfterAttach(callFrame.thisObject)) {
                        maybeInstallAfterAttach(callFrame.thisObject);
                    }
                }
            });
        } catch (Throwable e) {
            Slog.d(TAG, "hook Application.attach failed: " + e);
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    private void maybeInstallAfterAttach(Object receiver) {
        if (!shouldInstallFor(receiver)) {
            return;
        }
        if (!isAttachSeccompEnabled()) {
            if (disabledLogged.compareAndSet(false, true)) {
                Slog.d(TAG, "Application.attach seccomp disabled by debug property");
            }
            return;
        }
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        NativeCore.installSeccompShield();
        Slog.d(TAG, "seccomp shield installed after Application.attach for "
                + receiver.getClass().getName());
        BlackBoxBinderMonitor.recordProxyCall(
                SECCOMP_SERVICE,
                "android.app.Application",
                "attach",
                ApplicationAttachSeccompProxy.class.getSimpleName(),
                "receiver=" + receiver.getClass().getName(),
                "seccomp shield installed after attach",
                "handled",
                false,
                false,
                false);
    }

    private boolean maybeInstallTerminationTrapAfterAttach(Object receiver) {
        if (!shouldInstallFor(receiver) || !isAttachTerminationTrapEnabled()) {
            return false;
        }
        if (!terminationTrapInstalled.compareAndSet(false, true)) {
            return true;
        }
        NativeCore.installTerminationTrapSeccompShield();
        Slog.d(TAG, "termination trap seccomp shield installed after Application.attach for "
                + receiver.getClass().getName());
        BlackBoxBinderMonitor.recordProxyCall(
                SECCOMP_SERVICE,
                "android.app.Application",
                "attach",
                ApplicationAttachSeccompProxy.class.getSimpleName(),
                "receiver=" + receiver.getClass().getName(),
                "termination trap seccomp shield installed after attach",
                "handled",
                false,
                false,
                false);
        return true;
    }

    private boolean maybeInstallRawSyscallProbeAfterAttach(Object receiver) {
        if (!shouldInstallFor(receiver) || !isAttachRawSyscallProbeEnabled()) {
            return false;
        }
        if (!rawSyscallProbeInstalled.compareAndSet(false, true)) {
            return true;
        }
        NativeCore.installRawSyscallTerminationProbe();
        Slog.d(TAG, "raw syscall termination probe installed after Application.attach for "
                + receiver.getClass().getName());
        BlackBoxBinderMonitor.recordProxyCall(
                SECCOMP_SERVICE,
                "android.app.Application",
                "attach",
                ApplicationAttachSeccompProxy.class.getSimpleName(),
                "receiver=" + receiver.getClass().getName(),
                "raw syscall termination probe installed after attach",
                "handled",
                false,
                false,
                false);
        return true;
    }

    private static boolean shouldInstallFor(Object receiver) {
        return receiver != null;
    }

    private static void dumpApplicationClassLoaderAfterAttach(Object receiver) {
        if (!(receiver instanceof Application)) {
            return;
        }
        String packageName;
        try {
            packageName = BActivityThread.getAppPackageName();
        } catch (Throwable ignored) {
            return;
        }
        if (packageName == null || packageName.length() == 0) {
            return;
        }
        DexDumpProxy.scheduleClassLoaderDump(
                ((Application) receiver).getClassLoader(),
                packageName,
                "android.app.Application.attach");
    }

    static boolean isAttachSeccompEnabled() {
        return isTruthy(System.getenv(ATTACH_SECCOMP_ENV))
                || isTruthy(System.getProperty(ATTACH_SECCOMP_PROPERTY))
                || isTruthy(getAndroidSystemProperty(ATTACH_SECCOMP_SYSTEM_PROPERTY));
    }

    static boolean isAttachTerminationTrapEnabled() {
        return isTruthy(System.getenv(ATTACH_TERMINATION_TRAP_ENV))
                || isTruthy(System.getProperty(ATTACH_TERMINATION_TRAP_PROPERTY))
                || isTruthy(getAndroidSystemProperty(ATTACH_TERMINATION_TRAP_SYSTEM_PROPERTY));
    }

    static boolean isAttachRawSyscallProbeEnabled() {
        return isTruthy(System.getenv(ATTACH_RAW_SYSCALL_PROBE_ENV))
                || isTruthy(System.getProperty(ATTACH_RAW_SYSCALL_PROBE_PROPERTY))
                || isTruthy(getAndroidSystemProperty(ATTACH_RAW_SYSCALL_PROBE_SYSTEM_PROPERTY));
    }

    private static String getAndroidSystemProperty(String key) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method get = systemProperties.getDeclaredMethod("get", String.class);
            Object value = get.invoke(null, key);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        return "1".equals(normalized)
                || "true".equalsIgnoreCase(normalized)
                || "yes".equalsIgnoreCase(normalized)
                || "on".equalsIgnoreCase(normalized);
    }
}
