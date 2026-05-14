package top.niunaijun.blackbox.fake.service;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Method;

import black.android.app.BRContextImpl;
import black.android.app.BRContextImplKitkat;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.binder.BlackBoxBinderMonitor;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.utils.Slog;

public class ContextDataDirProxy implements IInjectHook {
    private static final String TAG = "ContextDataDirProxy";
    private static final String CONTEXT_SERVICE = "context";
    private static final String CONTEXT_IMPL = "android.app.ContextImpl";

    @Override
    public void injectHook() {
        try {
            Class<?> contextImpl = Class.forName("android.app.ContextImpl");
            hookDirMethod(contextImpl, "getDataDir", new DirResolver() {
                @Override
                public File resolve(File dataDir) {
                    return dataDir;
                }
            });
            hookDirMethod(contextImpl, "getFilesDir", new DirResolver() {
                @Override
                public File resolve(File dataDir) {
                    return new File(dataDir, "files");
                }
            });
            hookDirMethod(contextImpl, "getCacheDir", new DirResolver() {
                @Override
                public File resolve(File dataDir) {
                    return new File(dataDir, "cache");
                }
            });
            hookDirMethod(contextImpl, "getCodeCacheDir", new DirResolver() {
                @Override
                public File resolve(File dataDir) {
                    return new File(dataDir, "code_cache");
                }
            });
            hookDirMethod(contextImpl, "getNoBackupFilesDir", new DirResolver() {
                @Override
                public File resolve(File dataDir) {
                    return new File(dataDir, "no_backup");
                }
            });
        } catch (Throwable e) {
            Slog.d(TAG, "hook ContextImpl data dirs failed: " + e);
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    private void hookDirMethod(Class<?> contextImpl, final String methodName, final DirResolver resolver) {
        try {
            Method method = contextImpl.getDeclaredMethod(methodName);
            method.setAccessible(true);
            Pine.hook(method, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    File dataDir = publicDataDir(callFrame.thisObject);
                    if (dataDir == null) {
                        return;
                    }
                    File result = resolver.resolve(dataDir);
                    recordVirtualDir(methodName, result);
                    callFrame.setResult(result);
                }
            });
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable e) {
            Slog.d(TAG, "hook ContextImpl." + methodName + " failed: " + e);
        }
    }

    private static File publicDataDir(Object context) {
        String packageName = BActivityThread.getAppPackageName();
        if (packageName == null || packageName.length() == 0) {
            return null;
        }
        if (!shouldRewriteContext(context)) {
            return null;
        }
        return new File("/data/user/" + BlackBoxCore.getHostUserId(), packageName);
    }

    private static boolean shouldRewriteContext(Object context) {
        String virtualPackage = BActivityThread.getAppPackageName();
        if (context == null || virtualPackage == null || virtualPackage.length() == 0) {
            return false;
        }
        String hostPackage = BlackBoxCore.getHostPkg();
        String basePackageName = readBasePackageName(context);
        if (hostPackage.equals(basePackageName)) {
            return false;
        }
        if (virtualPackage.equals(basePackageName)) {
            return true;
        }
        String opPackageName = readOpPackageName(context);
        if (hostPackage.equals(opPackageName)) {
            return false;
        }
        if (virtualPackage.equals(opPackageName)) {
            return true;
        }
        if (context instanceof Context) {
            String packageName = ((Context) context).getPackageName();
            if (hostPackage.equals(packageName)) {
                return false;
            }
            return virtualPackage.equals(((Context) context).getPackageName());
        }
        return false;
    }

    private static String readBasePackageName(Object context) {
        try {
            return BRContextImpl.get(context).mBasePackageName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readOpPackageName(Object context) {
        try {
            return BRContextImplKitkat.get(context).mOpPackageName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void recordVirtualDir(String methodName, File result) {
        BlackBoxBinderMonitor.recordProxyCall(
                CONTEXT_SERVICE,
                CONTEXT_IMPL,
                methodName,
                ContextDataDirProxy.class.getSimpleName(),
                "0 args",
                result.getAbsolutePath(),
                "handled",
                false,
                true,
                false);
    }

    private interface DirResolver {
        File resolve(File dataDir);
    }
}
