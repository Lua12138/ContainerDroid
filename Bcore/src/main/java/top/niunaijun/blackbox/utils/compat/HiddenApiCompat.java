package top.niunaijun.blackbox.utils.compat;

import android.content.Context;
import android.os.Build;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import me.weishu.reflection.Reflection;
import top.niunaijun.blackbox.utils.Slog;

public class HiddenApiCompat {
    private static final String TAG = "HiddenApiCompat";
    private static volatile boolean sBypassed;

    public static boolean exemptAll(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            sBypassed = true;
            return true;
        }
        if (sBypassed) {
            return true;
        }
        synchronized (HiddenApiCompat.class) {
            if (sBypassed) {
                return true;
            }
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    HiddenApiBypass.addHiddenApiExemptions("");
                } else {
                    Reflection.unseal(context);
                }
                sBypassed = true;
                return true;
            } catch (Throwable e) {
                Slog.w(TAG, "Hidden API bypass failed on SDK " + Build.VERSION.SDK_INT + ": " + e);
                return false;
            }
        }
    }
}
