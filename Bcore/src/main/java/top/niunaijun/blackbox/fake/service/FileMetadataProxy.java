package top.niunaijun.blackbox.fake.service;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.niunaijun.blackbox.binder.BlackBoxBinderMonitor;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.utils.Slog;

public class FileMetadataProxy implements IInjectHook {
    private static final String TAG = "FileMetadataProxy";
    private static final String FILE_SERVICE = "file";
    private static final String FILESYSTEM_INTERFACE = "java.io.FileSystem";
    private static final int ACCESS_WRITE = 0x02;
    private static boolean sInstalled;

    @Override
    public void injectHook() {
        synchronized (FileMetadataProxy.class) {
            if (sInstalled) {
                return;
            }
            sInstalled = true;
        }

        try {
            Object fileSystem = getFileSystem();
            if (fileSystem == null) {
                Slog.d(TAG, "java.io.File.fs unavailable");
                return;
            }
            Class<?> fileSystemClass = fileSystem.getClass();
            Method checkAccess = fileSystemClass.getDeclaredMethod("checkAccess", File.class, int.class);
            Method getLength = fileSystemClass.getDeclaredMethod("getLength", File.class);
            checkAccess.setAccessible(true);
            getLength.setAccessible(true);
            hookCheckAccess(checkAccess);
            hookGetLength(getLength);
            Slog.d(TAG, "hooked " + fileSystemClass.getName() + " checkAccess/getLength");
        } catch (Throwable e) {
            Slog.d(TAG, "hook FileSystem metadata failed: " + e);
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    private static Object getFileSystem() throws Exception {
        Field fs = File.class.getDeclaredField("fs");
        fs.setAccessible(true);
        return fs.get(null);
    }

    private static void hookCheckAccess(Method checkAccess) {
        Pine.hook(checkAccess, new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame callFrame) {
                File file = firstFileArg(callFrame.args);
                if (file == null || !isWriteAccess(callFrame.args) || !isProcCmdlineFile(file)) {
                    return;
                }
                recordMetadataResult("checkAccess", file.getPath(), "false");
                callFrame.setResult(false);
            }
        });
    }

    private static void hookGetLength(Method getLength) {
        Pine.hook(getLength, new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame callFrame) {
                File file = firstFileArg(callFrame.args);
                if (file == null || !isProcCmdlineFile(file)) {
                    return;
                }
                recordMetadataResult("getLength", file.getPath(), "0");
                callFrame.setResult(0L);
            }
        });
    }

    private static File firstFileArg(Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof File)) {
            return null;
        }
        return (File) args[0];
    }

    private static boolean isWriteAccess(Object[] args) {
        return args != null
                && args.length > 1
                && args[1] instanceof Number
                && (((Number) args[1]).intValue() & ACCESS_WRITE) != 0;
    }

    private static boolean isProcCmdlineFile(File file) {
        return isProcCmdlinePath(file.getPath()) || isProcCmdlinePath(file.getAbsolutePath());
    }

    private static boolean isProcCmdlinePath(String path) {
        if (path == null || !path.endsWith("/cmdline")) {
            return false;
        }
        return "/proc/self/cmdline".equals(path)
                || path.startsWith("/proc/")
                || path.contains("/blackbox/proc/");
    }

    private static void recordMetadataResult(String method, String path, String result) {
        BlackBoxBinderMonitor.recordProxyCall(
                FILE_SERVICE,
                FILESYSTEM_INTERFACE,
                method,
                FileMetadataProxy.class.getSimpleName(),
                "path=" + path,
                result,
                "handled",
                false,
                true,
                false);
    }
}
