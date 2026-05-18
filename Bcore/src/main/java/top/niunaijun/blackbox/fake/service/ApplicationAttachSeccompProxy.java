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
import top.niunaijun.blackbox.utils.DiagnosticSwitch;
import top.niunaijun.blackbox.utils.Slog;

public class ApplicationAttachSeccompProxy implements IInjectHook {
    private static final String TAG = "ApplicationAttachSeccompProxy";
    private static final String SECCOMP_SERVICE = "seccomp";
    private static final String SYSTEM_PROPERTIES_CLASS = "android.os.SystemProperties";
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
        String receiverClassName = receiver.getClass().getName();
        NativeCore.installSeccompShield();
        Slog.d(TAG, "seccomp shield installed after Application.attach for "
                + receiverClassName);
        recordAttachResult(receiverClassName, "seccomp shield installed after attach");
    }

    private boolean maybeInstallTerminationTrapAfterAttach(Object receiver) {
        if (!shouldInstallFor(receiver) || !isAttachTerminationTrapEnabled()) {
            return false;
        }
        if (!terminationTrapInstalled.compareAndSet(false, true)) {
            return true;
        }
        String receiverClassName = receiver.getClass().getName();
        NativeCore.installTerminationTrapSeccompShield();
        Slog.d(TAG, "termination trap seccomp shield installed after Application.attach for "
                + receiverClassName);
        recordAttachResult(receiverClassName,
                "termination trap seccomp shield installed after attach");
        return true;
    }

    private boolean maybeInstallRawSyscallProbeAfterAttach(Object receiver) {
        if (!shouldInstallFor(receiver) || !isAttachRawSyscallProbeEnabled()) {
            return false;
        }
        if (!rawSyscallProbeInstalled.compareAndSet(false, true)) {
            return true;
        }
        String receiverClassName = receiver.getClass().getName();
        NativeCore.installRawSyscallTerminationProbe();
        Slog.d(TAG, "raw syscall termination probe installed after Application.attach for "
                + receiverClassName);
        recordAttachResult(receiverClassName,
                "raw syscall termination probe installed after attach");
        return true;
    }

    private static void recordAttachResult(String receiverClassName, String resultSummary) {
        BlackBoxBinderMonitor.recordProxyCall(
                SECCOMP_SERVICE,
                "android.app.Application",
                "attach",
                ApplicationAttachSeccompProxy.class.getSimpleName(),
                "receiver=" + receiverClassName,
                resultSummary,
                "handled",
                false,
                false,
                false);
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
        return isAnyDiagnosticSwitchEnabled(
                ATTACH_SECCOMP_ENV,
                ATTACH_SECCOMP_PROPERTY,
                ATTACH_SECCOMP_SYSTEM_PROPERTY);
    }

    static boolean isAttachTerminationTrapEnabled() {
        return isAnyDiagnosticSwitchEnabled(
                ATTACH_TERMINATION_TRAP_ENV,
                ATTACH_TERMINATION_TRAP_PROPERTY,
                ATTACH_TERMINATION_TRAP_SYSTEM_PROPERTY);
    }

    static boolean isAttachRawSyscallProbeEnabled() {
        return isAnyDiagnosticSwitchEnabled(
                ATTACH_RAW_SYSCALL_PROBE_ENV,
                ATTACH_RAW_SYSCALL_PROBE_PROPERTY,
                ATTACH_RAW_SYSCALL_PROBE_SYSTEM_PROPERTY);
    }

    private static boolean isAnyDiagnosticSwitchEnabled(String envKey,
                                                        String javaPropertyKey,
                                                        String systemPropertyKey) {
        return DiagnosticSwitch.isTruthy(System.getenv(envKey))
                || DiagnosticSwitch.isTruthy(System.getProperty(javaPropertyKey))
                || DiagnosticSwitch.isTruthy(getAndroidSystemProperty(systemPropertyKey));
    }

    private static String getAndroidSystemProperty(String key) {
        try {
            Class<?> systemProperties = Class.forName(SYSTEM_PROPERTIES_CLASS);
            Method get = systemProperties.getDeclaredMethod("get", String.class);
            Object value = get.invoke(null, key);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
