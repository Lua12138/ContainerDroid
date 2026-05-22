package top.niunaijun.blackbox.fake.service;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Parcel;

import java.lang.reflect.Field;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.binder.BinderPayloadSummary;
import top.niunaijun.blackbox.binder.BlackBoxBinderMonitor;

public class PackageManagerBinderInterceptor implements BlackBoxBinderMonitor.BinderTransactInterceptor {
    private static final String PACKAGE_SERVICE = "package";
    private static final String IPACKAGE_MANAGER = "android.content.pm.IPackageManager";

    @Override
    public boolean onTransact(Object binderProxy, int code, Parcel data, Parcel reply, int flags,
                              String descriptor, String method, String argsSummary) {
        if (!IPACKAGE_MANAGER.equals(descriptor) || reply == null) {
            return false;
        }
        BinderPayloadSummary.PackageManagerCall call = BinderPayloadSummary.parsePackageManagerCall(
                descriptor,
                method,
                data,
                Build.VERSION.SDK_INT);
        if (call == null) {
            return false;
        }
        if ("getPackageInfo".equals(call.getMethod())) {
            return writePackageInfoReply(reply, call);
        }
        if ("getApplicationInfo".equals(call.getMethod())) {
            return writeApplicationInfoReply(reply, call);
        }
        if ("getPackageUid".equals(call.getMethod())) {
            return writePackageUidReply(reply, call);
        }
        return false;
    }

    private boolean writePackageInfoReply(Parcel reply, BinderPayloadSummary.PackageManagerCall call) {
        if (!isVirtualInstalledPackage(call.getPackageName())) {
            return false;
        }
        int flags = (int) call.getFlags();
        PackageInfo packageInfo = BlackBoxCore.getBPackageManager().getPackageInfo(
                call.getPackageName(),
                flags,
                BActivityThread.getUserId());
        if (packageInfo == null) {
            return false;
        }
        packageInfo = sanitizePackageInfoForReply(packageInfo, call.getPackageName());
        reply.writeNoException();
        reply.writeInt(1);
        packageInfo.writeToParcel(reply, 0);
        resetReplyForInlineBinderRead(reply);
        recordHandled(call, describePackageInfo(packageInfo));
        return true;
    }

    private boolean writeApplicationInfoReply(Parcel reply, BinderPayloadSummary.PackageManagerCall call) {
        if (!isVirtualInstalledPackage(call.getPackageName())) {
            return false;
        }
        int flags = (int) call.getFlags();
        ApplicationInfo applicationInfo = BlackBoxCore.getBPackageManager().getApplicationInfo(
                call.getPackageName(),
                flags,
                BActivityThread.getUserId());
        if (applicationInfo == null) {
            return false;
        }
        applicationInfo = sanitizeApplicationInfoForReply(applicationInfo, call.getPackageName());
        reply.writeNoException();
        reply.writeInt(1);
        applicationInfo.writeToParcel(reply, 0);
        resetReplyForInlineBinderRead(reply);
        recordHandled(call, describeApplicationInfo(applicationInfo));
        return true;
    }

    private boolean writePackageUidReply(Parcel reply, BinderPayloadSummary.PackageManagerCall call) {
        if (!isVirtualInstalledPackage(call.getPackageName())) {
            return false;
        }
        PackageInfo packageInfo = BlackBoxCore.getBPackageManager().getPackageInfo(
                call.getPackageName(),
                0,
                BActivityThread.getUserId());
        if (packageInfo == null) {
            return false;
        }
        reply.writeNoException();
        reply.writeInt(BActivityThread.getBUid());
        resetReplyForInlineBinderRead(reply);
        recordHandled(call, "uid=" + BActivityThread.getBUid());
        return true;
    }

    private static boolean isVirtualInstalledPackage(String packageName) {
        return packageName != null
                && BlackBoxCore.get().isInstalled(packageName, BActivityThread.getUserId());
    }

    private static void resetReplyForInlineBinderRead(Parcel reply) {
        // BinderProxy.transact is intercepted in-process, so the caller reads this same Parcel.
        reply.setDataPosition(0);
    }

    private void recordHandled(BinderPayloadSummary.PackageManagerCall call, String resultSummary) {
        BlackBoxBinderMonitor.recordProxyCall(
                PACKAGE_SERVICE,
                IPACKAGE_MANAGER,
                call.getMethod(),
                getClass().getSimpleName(),
                "package=" + call.getPackageName() + ", flags=" + call.getFlags()
                        + ", userId=" + call.getUserId(),
                resultSummary,
                "handled",
                false,
                true,
                false);
    }

    private static PackageInfo sanitizePackageInfoForReply(PackageInfo packageInfo, String packageName) {
        PackageInfo copy = copyPackageInfo(packageInfo);
        copy.applicationInfo = sanitizeApplicationInfoForReply(copy.applicationInfo, packageName);
        return copy;
    }

    private static ApplicationInfo sanitizeApplicationInfoForReply(ApplicationInfo applicationInfo, String packageName) {
        if (applicationInfo == null) {
            return null;
        }
        ApplicationInfo copy = new ApplicationInfo(applicationInfo);
        String publicDataDir = publicDataDir(packageName);
        copy.dataDir = publicDataDir;
        setStringFieldIfPresent(copy, "credentialProtectedDataDir", publicDataDir);
        setStringFieldIfPresent(copy, "deviceProtectedDataDir", publicDataDir);
        return copy;
    }

    private static void setStringFieldIfPresent(ApplicationInfo applicationInfo, String fieldName, String value) {
        try {
            Field field = ApplicationInfo.class.getField(fieldName);
            field.set(applicationInfo, value);
        } catch (Throwable ignored) {
        }
    }

    private static PackageInfo copyPackageInfo(PackageInfo packageInfo) {
        Parcel parcel = Parcel.obtain();
        try {
            packageInfo.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return PackageInfo.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    private static String publicDataDir(String packageName) {
        return "/data/user/" + BlackBoxCore.getHostUserId() + "/" + packageName;
    }

    private static String describePackageInfo(PackageInfo packageInfo) {
        if (packageInfo == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(PackageInfo.class.getName())
                .append("{package=").append(packageInfo.packageName)
                .append(", versionCode=").append(packageInfo.versionCode)
                .append(", versionName=").append(packageInfo.versionName);
        if (packageInfo.applicationInfo != null) {
            builder.append(", appInfo=").append(describeApplicationInfo(packageInfo.applicationInfo));
        }
        Signature[] signatures = packageInfo.signatures;
        builder.append(", signatures=").append(signatures == null ? 0 : signatures.length);
        if (signatures != null && signatures.length > 0 && signatures[0] != null) {
            builder.append(", signatureHash=").append(Integer.toHexString(signatures[0].hashCode()));
        }
        return builder.append('}').toString();
    }

    private static String describeApplicationInfo(ApplicationInfo applicationInfo) {
        if (applicationInfo == null) {
            return "null";
        }
        return ApplicationInfo.class.getName()
                + "{package=" + applicationInfo.packageName
                + ", uid=" + applicationInfo.uid
                + ", sourceDir=" + applicationInfo.sourceDir
                + ", publicSourceDir=" + applicationInfo.publicSourceDir
                + ", dataDir=" + applicationInfo.dataDir
                + "}";
    }
}
