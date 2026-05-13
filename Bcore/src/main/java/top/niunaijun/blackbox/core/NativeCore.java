package top.niunaijun.blackbox.core;


import android.os.Process;
import android.os.Build;

import androidx.annotation.Keep;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import dalvik.system.DexFile;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.compat.DexFileCompat;

import static top.niunaijun.blackbox.core.env.BEnvironment.EMPTY_JAR;

/**
 * Created by Milk on 4/9/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class NativeCore {
    public static final String TAG = "NativeCore";
    private static final SeccompInstallGate SECCOMP_INSTALL_GATE = new SeccompInstallGate();

    static {
        new File("");
        System.loadLibrary("blackbox");
    }

    public static native void init(int apiLevel);

    public static native void enableIO();

    public static native void addIORule(String targetPath, String relocatePath);

    public static native void hideXposed();

    public static native void installSeccompShield();

    public static void installSeccompShieldIfNeeded() {
        if (!SECCOMP_INSTALL_GATE.tryInstall(Build.SUPPORTED_ABIS)) {
            return;
        }
        installSeccompShield();
    }

    @Keep
    public static Class<?> getFileSystemClass() {
        try {
            Field fs = File.class.getDeclaredField("fs");
            fs.setAccessible(true);
            Object fileSystem = fs.get(null);
            if (fileSystem != null) {
                return fileSystem.getClass();
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    @Keep
    public static Method findMethod(Class<?> clazz, String name, String desc) {
        if (clazz == null) {
            return null;
        }
        try {
            for (Method declaredMethod : clazz.getDeclaredMethods()) {
                if (name.equals(declaredMethod.getName())
                        && desc.equals(top.niunaijun.jnihook.MethodUtils.getDesc(declaredMethod))) {
                    declaredMethod.setAccessible(true);
                    return declaredMethod;
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void dumpDex(ClassLoader classLoader, String packageName) {
        List<Long> cookies = DexFileCompat.getCookies(classLoader);
        for (Long cookie : cookies) {
            if (cookie == 0)
                continue;
//            File file = new File(BlackBoxCore.get().getDexDumpDir(), packageName);
//            FileUtils.mkdirs(file);
//            dumpDex(cookie, file.getAbsolutePath());
        }
    }

    @Keep
    public static int getCallingUid(int origCallingUid) {
        // 系统uid
        if (origCallingUid > 0 && origCallingUid < Process.FIRST_APPLICATION_UID)
            return origCallingUid;
        // 非用户应用
        if (origCallingUid > Process.LAST_APPLICATION_UID)
            return origCallingUid;

        if (origCallingUid == BlackBoxCore.getHostUid()) {
//            Log.d(TAG, "origCallingUid: " + origCallingUid + " => " + BActivityThread.getCallingBUid());
            return BActivityThread.getCallingBUid();
        }
        return origCallingUid;
    }

    @Keep
    public static String redirectPath(String path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static File redirectPath(File path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static long[] loadEmptyDex() {
        try {
            DexFile dexFile = new DexFile(EMPTY_JAR);
            List<Long> cookies = DexFileCompat.getCookies(dexFile);
            long[] longs = new long[cookies.size()];
            for (int i = 0; i < cookies.size(); i++) {
                longs[i] = cookies.get(i);
            }
            return longs;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new long[]{};
    }
}
