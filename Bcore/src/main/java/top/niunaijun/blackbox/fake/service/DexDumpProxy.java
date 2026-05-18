package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dalvik.system.DexFile;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.binder.BlackBoxBinderMonitor;
import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.SystemPropertiesCompat;

public class DexDumpProxy implements IInjectHook {
    private static final String TAG = "DexDumpProxy";
    private static final String SECCOMP_SERVICE = "seccomp";
    private static final String DEXLOAD_SECCOMP_ENV = "BLACKBOX_DEXLOAD_SECCOMP";
    private static final String DEXLOAD_SECCOMP_PROPERTY = "debug.blackbox.dexload_seccomp";
    private static final long DEX_LOAD_DUMP_DELAY_MS = 0;
    private static boolean sInstalled;
    private static final AtomicBoolean sDexLoadSeccompInstalled = new AtomicBoolean();
    private static final ScheduledExecutorService sDexLoadDumpExecutor =
            Executors.newSingleThreadScheduledExecutor(Executors.defaultThreadFactory());

    @Override
    public void injectHook() {
        if (!isDexDumpEnabled()) {
            return;
        }
        synchronized (DexDumpProxy.class) {
            if (sInstalled) {
                return;
            }
            sInstalled = true;
        }
        hookClassLoaderConstructors("dalvik.system.BaseDexClassLoader");
        hookClassLoaderConstructors("dalvik.system.InMemoryDexClassLoader");
        hookDexFilePublicApis();
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    private void hookClassLoaderConstructors(final String className) {
        try {
            Class<?> clazz = Class.forName(className);
            for (final Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                constructor.setAccessible(true);
                Pine.hook(constructor, new MethodHook() {
                    @Override
                    public void beforeCall(Pine.CallFrame callFrame) {
                        scheduleByteBufferArgs(callFrame.args, className + ".<init>");
                        scheduleStringPathArgs(callFrame.args, className + ".<init>");
                    }

                    @Override
                    public void afterCall(Pine.CallFrame callFrame) {
                        if (callFrame.thisObject instanceof ClassLoader) {
                            scheduleClassLoaderDump((ClassLoader) callFrame.thisObject,
                                    currentPackageName(),
                                    className + ".<init>");
                        }
                    }
                });
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable e) {
            Slog.d(TAG, "hook class loader constructors failed: " + className + " " + e);
        }
    }

    private void hookDexFilePublicApis() {
        try {
            Class<?> clazz = Class.forName("dalvik.system.DexFile");
            for (final Constructor<?> constructor : clazz.getConstructors()) {
                constructor.setAccessible(true);
                Pine.hook(constructor, new MethodHook() {
                    @Override
                    public void beforeCall(Pine.CallFrame callFrame) {
                        scheduleStringPathArgs(callFrame.args, "dalvik.system.DexFile.<init>");
                    }

                    @Override
                    public void afterCall(Pine.CallFrame callFrame) {
                        if (callFrame.thisObject instanceof DexFile) {
                            scheduleDexFileDump((DexFile) callFrame.thisObject,
                                    "dalvik.system.DexFile.<init>");
                        }
                    }
                });
            }
            for (final Method method : clazz.getMethods()) {
                if (!"loadDex".equals(method.getName())) {
                    continue;
                }
                method.setAccessible(true);
                Pine.hook(method, new MethodHook() {
                    @Override
                    public void beforeCall(Pine.CallFrame callFrame) {
                        scheduleStringPathArgs(callFrame.args, "dalvik.system.DexFile.loadDex");
                        maybeInstallSeccompForStandaloneDexLoad("dalvik.system.DexFile.loadDex");
                    }

                    @Override
                    public void afterCall(Pine.CallFrame callFrame) {
                        Object result = callFrame.getResult();
                        if (result instanceof DexFile) {
                            scheduleDexFileDump((DexFile) result, "dalvik.system.DexFile.loadDex");
                        }
                    }
                });
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable e) {
            Slog.d(TAG, "hook DexFile public APIs failed: " + e);
        }
    }

    private static void maybeInstallSeccompForStandaloneDexLoad(String sourceTag) {
        if (!isDexDumpEnabled()) {
            return;
        }
        String packageName = currentPackageName();
        if (packageName == null) {
            return;
        }
        if (!isDexLoadSeccompDiagnosticsEnabled()) {
            return;
        }
        if (!sDexLoadSeccompInstalled.compareAndSet(false, true)) {
            return;
        }
        try {
            NativeCore.installSeccompShield();
            Slog.d(TAG, "seccomp shield requested before " + sourceTag
                    + " for " + packageName);
            recordDexLoadSeccompInstall(packageName, sourceTag,
                    "seccomp shield requested before standalone dex load");
        } catch (Throwable e) {
            sDexLoadSeccompInstalled.set(false);
            Slog.d(TAG, "install seccomp before " + sourceTag + " failed: " + e);
        }
    }

    private static boolean isDexLoadSeccompDiagnosticsEnabled() {
        return isTruthy(System.getenv(DEXLOAD_SECCOMP_ENV))
                || isTruthy(System.getProperty("blackbox.dexload_seccomp"))
                || isTruthy(SystemPropertiesCompat.get(DEXLOAD_SECCOMP_PROPERTY));
    }

    private static boolean isTruthy(String value) {
        return "1".equals(value)
                || "true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value);
    }

    private static void recordDexLoadSeccompInstall(String packageName, String sourceTag,
                                                   String resultSummary) {
        BlackBoxBinderMonitor.recordProxyCall(
                SECCOMP_SERVICE,
                "dalvik.system.DexFile",
                "loadDex",
                DexDumpProxy.class.getSimpleName(),
                "package=" + packageName + ", source=" + sourceTag,
                resultSummary,
                "handled",
                false,
                false,
                false);
    }

    public static void scheduleClassLoaderDump(final ClassLoader classLoader,
                                               final String packageName,
                                               final String sourceTag) {
        if (!isDexDumpEnabled()
                || classLoader == null || packageName == null || packageName.length() == 0) {
            return;
        }
        scheduleDexLoadDump(sourceTag, new Runnable() {
            @Override
            public void run() {
                try {
                    NativeCore.dumpDex(classLoader, packageName);
                } catch (Throwable e) {
                    Slog.d(TAG, "dump class loader failed: " + sourceTag + " " + e);
                }
            }
        });
    }

    private static void dumpClassLoader(ClassLoader classLoader, String sourceTag) {
        if (!isDexDumpEnabled()) {
            return;
        }
        String packageName = currentPackageName();
        if (packageName == null || classLoader == null) {
            return;
        }
        try {
            NativeCore.dumpDex((ClassLoader) classLoader, packageName);
        } catch (Throwable e) {
            Slog.d(TAG, "dump class loader failed: " + sourceTag + " " + e);
        }
    }

    private static void dumpDexFile(DexFile dexFile, String sourceTag) {
        if (!isDexDumpEnabled()) {
            return;
        }
        String packageName = currentPackageName();
        if (packageName == null || dexFile == null) {
            return;
        }
        dumpDexFileForPackage(dexFile, packageName, sourceTag);
    }

    private static void scheduleDexFileDump(final DexFile dexFile, final String sourceTag) {
        if (!isDexDumpEnabled()) {
            return;
        }
        final String packageName = currentPackageName();
        if (packageName == null || dexFile == null) {
            return;
        }
        scheduleDexLoadDump(sourceTag, new Runnable() {
            @Override
            public void run() {
                dumpDexFileForPackage(dexFile, packageName, sourceTag);
            }
        });
    }

    private static void dumpDexFileForPackage(DexFile dexFile, String packageName, String sourceTag) {
        if (!isDexDumpEnabled()) {
            return;
        }
        try {
            NativeCore.dumpDexFile(dexFile, packageName, sourceTag);
        } catch (Throwable e) {
            Slog.d(TAG, "dump DexFile failed: " + sourceTag + " " + e);
        }
    }

    private static void scheduleStringPathArgs(Object[] args, final String sourceTag) {
        if (!isDexDumpEnabled()) {
            return;
        }
        final String packageName = currentPackageName();
        if (packageName == null || args == null) {
            return;
        }
        final List<String> paths = new ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof String && ((String) arg).length() > 0) {
                paths.add((String) arg);
            }
        }
        if (paths.isEmpty()) {
            return;
        }
        scheduleDexLoadDump(sourceTag, new Runnable() {
            @Override
            public void run() {
                for (String path : paths) {
                    dumpDexPathList(path, packageName, sourceTag);
                }
            }
        });
    }

    private static void scheduleDexLoadDump(String sourceTag, Runnable task) {
        if (!isDexDumpEnabled()) {
            return;
        }
        try {
            sDexLoadDumpExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    if (isDexDumpEnabled()) {
                        task.run();
                    }
                }
            }, DEX_LOAD_DUMP_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            Slog.d(TAG, "schedule dex dump failed: " + sourceTag + " " + e);
        }
    }

    private static void dumpStringPathArgs(Object[] args, String sourceTag) {
        if (!isDexDumpEnabled()) {
            return;
        }
        String packageName = currentPackageName();
        if (packageName == null || args == null) {
            return;
        }
        for (Object arg : args) {
            if (arg instanceof String) {
                dumpDexPathList((String) arg, packageName, sourceTag);
            }
        }
    }

    private static void dumpDexPathList(String value, String packageName, String sourceTag) {
        if (!isDexDumpEnabled()) {
            return;
        }
        if (value == null || value.length() == 0) {
            return;
        }
        String[] paths = value.split(java.io.File.pathSeparator);
        for (String path : paths) {
            if (path == null || path.length() == 0) {
                continue;
            }
            try {
                NativeCore.dumpDexPath(path, packageName, sourceTag);
            } catch (Throwable e) {
                Slog.d(TAG, "dump dex path failed: " + path + " " + e);
            }
        }
    }

    private static void scheduleByteBufferArgs(Object[] args, final String sourceTag) {
        if (!isDexDumpEnabled()) {
            return;
        }
        final String packageName = currentPackageName();
        if (packageName == null || args == null) {
            return;
        }
        final List<ByteBuffer> buffers = new ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof ByteBuffer) {
                buffers.add(((ByteBuffer) arg).asReadOnlyBuffer());
            } else if (arg instanceof ByteBuffer[]) {
                for (ByteBuffer buffer : (ByteBuffer[]) arg) {
                    if (buffer != null) {
                        buffers.add(buffer.asReadOnlyBuffer());
                    }
                }
            }
        }
        if (buffers.isEmpty()) {
            return;
        }
        scheduleDexLoadDump(sourceTag, new Runnable() {
            @Override
            public void run() {
                NativeCore.dumpDexByteBuffers(buffers.toArray(new ByteBuffer[0]), packageName, sourceTag);
            }
        });
    }

    private static void dumpByteBufferArgs(Object[] args, String sourceTag) {
        if (!isDexDumpEnabled()) {
            return;
        }
        String packageName = currentPackageName();
        if (packageName == null || args == null) {
            return;
        }
        for (Object arg : args) {
            if (arg instanceof ByteBuffer) {
                NativeCore.dumpDexByteBuffers(new ByteBuffer[]{(ByteBuffer) arg}, packageName, sourceTag);
            } else if (arg instanceof ByteBuffer[]) {
                NativeCore.dumpDexByteBuffers((ByteBuffer[]) arg, packageName, sourceTag);
            }
        }
    }

    private static boolean isDexDumpEnabled() {
        try {
            return BlackBoxCore.get().isDexDumpEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String currentPackageName() {
        try {
            return BActivityThread.getAppPackageName();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
