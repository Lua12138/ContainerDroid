package top.niunaijun.blackbox.utils.compat;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;

import java.io.File;

import black.android.app.BRContextImpl;
import black.android.app.BRContextImplKitkat;
import black.android.app.BRLoadedApk;
import black.android.content.AttributionSourceStateContext;
import black.android.content.BRAttributionSource;
import black.android.content.BRAttributionSourceState;
import black.android.content.BRContentResolver;
import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Created by Milk on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ContextCompat {
    public static final String TAG = "ContextCompat";

    public static void fixAttributionSourceState(Object obj, int uid) {
        Object mAttributionSourceState;
        if (obj != null && BRAttributionSource.get(obj)._check_mAttributionSourceState() != null) {
            mAttributionSourceState = BRAttributionSource.get(obj).mAttributionSourceState();

            AttributionSourceStateContext attributionSourceStateContext = BRAttributionSourceState.get(mAttributionSourceState);
            attributionSourceStateContext._set_packageName(BlackBoxCore.getHostPkg());
            attributionSourceStateContext._set_uid(uid);
            fixAttributionSourceState(BRAttributionSource.get(obj).getNext(), uid);
        }
    }

    public static void fix(Context context) {
        fix(context, BlackBoxCore.getHostPkg(), BlackBoxCore.getHostUid(), false);
    }

    public static void fixVirtual(Context context, String packageName) {
        String basePackageName = packageName == null || packageName.length() == 0
                ? BlackBoxCore.getHostPkg()
                : packageName;
        fix(context, basePackageName, BlackBoxCore.getHostUid(), true);
    }

    private static void fix(Context context, String basePackageName, int attributionUid, boolean virtualDataDirs) {
        try {
            int deep = 0;
            while (context instanceof ContextWrapper) {
                context = ((ContextWrapper) context).getBaseContext();
                deep++;
                if (deep >= 10) {
                    return;
                }
            }
            BRContextImpl.get(context)._set_mPackageManager(null);
            try {
                context.getPackageManager();
            } catch (Throwable e) {
                e.printStackTrace();
            }

            BRContextImpl.get(context)._set_mBasePackageName(basePackageName);
            String opPackageName = virtualDataDirs ? basePackageName : BlackBoxCore.getHostPkg();
            BRContextImplKitkat.get(context)._set_mOpPackageName(opPackageName);
            BRContentResolver.get(context.getContentResolver())._set_mPackageName(opPackageName);
            if (virtualDataDirs) {
                fixVirtualDataDirs(context, basePackageName);
            }

            if (BuildCompat.isS()) {
                fixAttributionSourceState(BRContextImpl.get(context).getAttributionSource(), attributionUid);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void fixVirtualDataDirs(Context context, String basePackageName) {
        if (context == null || basePackageName == null || basePackageName.length() == 0
                || BlackBoxCore.getHostPkg().equals(basePackageName)) {
            return;
        }
        File publicDataDir = new File("/data/user/" + BlackBoxCore.getHostUserId() + "/" + basePackageName);
        try {
            ApplicationInfo applicationInfo = context.getApplicationInfo();
            if (applicationInfo != null) {
                context.getApplicationInfo().dataDir = publicDataDir.getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        try {
            BRContextImpl.get(context)._set_mDataDir(publicDataDir);
        } catch (Throwable ignored) {
        }
        try {
            BRContextImpl.get(context)._set_mFilesDir(new File(publicDataDir, "files"));
        } catch (Throwable ignored) {
        }
        try {
            BRContextImpl.get(context)._set_mPreferencesDir(new File(publicDataDir, "shared_prefs"));
        } catch (Throwable ignored) {
        }
        try {
            BRContextImpl.get(context)._set_mNoBackupFilesDir(new File(publicDataDir, "no_backup"));
        } catch (Throwable ignored) {
        }
        try {
            BRContextImpl.get(context)._set_mCacheDir(new File(publicDataDir, "cache"));
        } catch (Throwable ignored) {
        }
        try {
            BRContextImpl.get(context)._set_mCodeCacheDir(new File(publicDataDir, "code_cache"));
        } catch (Throwable ignored) {
        }
        try {
            Object packageInfo = BRContextImpl.get(context).mPackageInfo();
            if (packageInfo != null) {
                BRLoadedApk.get(packageInfo)._set_mDataDir(publicDataDir.getAbsolutePath());
                BRLoadedApk.get(packageInfo)._set_mDataDirFile(publicDataDir);
                BRLoadedApk.get(packageInfo)._set_mCredentialProtectedDataDirFile(publicDataDir);
                BRLoadedApk.get(packageInfo)._set_mDeviceProtectedDataDirFile(
                        new File("/data/user_de/" + BlackBoxCore.getHostUserId() + "/" + basePackageName));
            }
        } catch (Throwable ignored) {
        }
    }
}
