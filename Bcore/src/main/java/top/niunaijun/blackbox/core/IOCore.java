package top.niunaijun.blackbox.core;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Process;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.TrieTree;

/**
 * Created by Milk on 4/9/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
@SuppressLint("SdCardPath")
public class IOCore {
    public static final String TAG = "IOCore";

    private static final IOCore sIOCore = new IOCore();
    private static final TrieTree mTrieTree = new TrieTree();
    private static final TrieTree sBlackTree = new TrieTree();
    private final Map<String, String> mRedirectMap = new LinkedHashMap<>();
    private static final ThreadLocal<Boolean> sRefreshingProcMaps = new ThreadLocal<>();
    private static final long PROC_MAPS_REFRESH_INTERVAL_MS = 60000L;
    private static volatile long sLastProcMapsRefreshMs = 0L;

    private static final Map<String, Map<String, String>> sCachePackageRedirect = new HashMap<>();

    public static IOCore get() {
        return sIOCore;
    }

    // /data/data/com.google/  ----->  /data/data/com.virtual/data/com.google/
    public void addRedirect(String origPath, String redirectPath) {
        if (TextUtils.isEmpty(origPath) || TextUtils.isEmpty(redirectPath) || mRedirectMap.get(origPath) != null)
            return;
        //Add the key to TrieTree
        mTrieTree.add(origPath);
        mRedirectMap.put(origPath, redirectPath);
        File redirectFile = new File(redirectPath);
        if (!redirectFile.exists()) {
            FileUtils.mkdirs(redirectPath);
        }
        NativeCore.addIORule(origPath, redirectPath);
    }

    public void addBlackRedirect(String path) {
        if (TextUtils.isEmpty(path))
            return;
        sBlackTree.add(path);
    }

    public String redirectPath(String path) {
        if (TextUtils.isEmpty(path))
            return path;
        String procMapsPath = redirectProcMapsPath(path);
        if (!TextUtils.isEmpty(procMapsPath)) {
            return procMapsPath;
        }
        if (path.contains("/blackbox/")) {
            return path;
        }
        String search = sBlackTree.search(path);
        if (!TextUtils.isEmpty(search))
            return search;

        //Search the key from TrieTree
        String key = mTrieTree.search(path);
        if (!TextUtils.isEmpty(key))
            path = path.replace(key, Objects.requireNonNull(mRedirectMap.get(key)));

        return path;
    }

    private String redirectProcMapsPath(String path) {
        if (!isSelfProcMapsPath(path)) {
            return null;
        }
        if (Boolean.TRUE.equals(sRefreshingProcMaps.get())) {
            return path;
        }
        File procMaps = ensureProcMapsFile();
        if (procMaps != null && procMaps.isFile() && procMaps.length() > 0) {
            return procMaps.getAbsolutePath();
        }
        return null;
    }

    private boolean isSelfProcMapsPath(String path) {
        if ("/proc/self/maps".equals(path)) {
            return true;
        }
        if (path.equals("/proc/" + Process.myPid() + "/maps")) {
            return true;
        }
        int appPid = BActivityThread.getAppPid();
        return appPid > 0 && path.equals("/proc/" + appPid + "/maps");
    }

    private File ensureProcMapsFile() {
        int appPid = BActivityThread.getAppPid();
        if (appPid <= 0) {
            appPid = Process.myPid();
        }
        File procDir = BEnvironment.getProcDir(appPid);
        File maps = new File(procDir, "maps");
        long now = System.currentTimeMillis();
        if (maps.isFile() && maps.length() > 0 && now - sLastProcMapsRefreshMs < PROC_MAPS_REFRESH_INTERVAL_MS) {
            return maps;
        }
        sRefreshingProcMaps.set(Boolean.TRUE);
        try {
            if (writeSanitizedProcMapsFileNative(maps, BActivityThread.getAppPackageName())
                    || writeSanitizedProcMapsFileJava(maps, BActivityThread.getAppPackageName())) {
                sLastProcMapsRefreshMs = now;
            }
        } finally {
            sRefreshingProcMaps.remove();
        }
        return maps;
    }

    private boolean writeSanitizedProcMapsFileNative(File maps, String packageName) {
        File parent = maps.getParentFile();
        if (parent != null && !parent.exists()) {
            FileUtils.mkdirs(parent);
        }
        try {
            return NativeCore.writeSanitizedProcMapsSnapshot(maps.getAbsolutePath(), packageName);
        } catch (LinkageError ignored) {
            return false;
        }
    }

    private boolean writeSanitizedProcMapsFileJava(File maps, String packageName) {
        File parent = maps.getParentFile();
        if (parent != null && !parent.exists()) {
            FileUtils.mkdirs(parent);
        }
        boolean wroteAny = false;
        if (maps.exists()) {
            maps.setWritable(true, true);
        }
        boolean nativeBypass = beginInternalProcMapsRefresh();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"));
             BufferedWriter writer = new BufferedWriter(new FileWriter(maps, false))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (shouldHideProcMapsLine(line)) {
                    continue;
                }
                String sanitized = sanitizeProcMapsLine(line, packageName);
                if (shouldHideProcMapsLine(sanitized)) {
                    continue;
                }
                writer.write(sanitized);
                writer.newLine();
                wroteAny = true;
            }
        } catch (IOException ignored) {
            return false;
        } finally {
            endInternalProcMapsRefresh(nativeBypass);
        }
        maps.setReadable(true, false);
        maps.setWritable(false, false);
        return wroteAny;
    }

    private boolean beginInternalProcMapsRefresh() {
        try {
            NativeCore.enterNativeInternalFileProbe();
            return true;
        } catch (LinkageError ignored) {
            return false;
        }
    }

    private void endInternalProcMapsRefresh(boolean nativeBypass) {
        if (!nativeBypass) {
            return;
        }
        try {
            NativeCore.leaveNativeInternalFileProbe();
        } catch (LinkageError ignored) {
            // NativeCore may be unavailable in host-side source tests.
        }
    }

    private String sanitizeProcMapsLine(String line, String packageName) {
        String sanitized = replaceBlackBoxDataUserRoots(line);
        String hostPackageName = BlackBoxCore.getHostPkg();
        if (!TextUtils.isEmpty(packageName) && !TextUtils.isEmpty(hostPackageName)) {
            sanitized = sanitized.replace(hostPackageName, packageName);
        }
        sanitized = sanitized.replace("/blackbox/data/user/", "/data/user/");
        sanitized = sanitized.replace("/blackbox/", "/data/");
        return sanitized;
    }

    private String replaceBlackBoxDataUserRoots(String value) {
        if (TextUtils.isEmpty(value)) {
            return value;
        }
        final String marker = "/blackbox/data/user/";
        final String dataData = "/data/data/";
        final String publicRoot = "/data/user/";
        String result = value;
        int pos = result.indexOf(marker);
        while (pos >= 0) {
            int prefix = result.lastIndexOf(dataData, pos);
            int replaceStart = prefix >= 0 ? prefix : pos;
            int replaceEnd = pos + marker.length();
            result = result.substring(0, replaceStart) + publicRoot + result.substring(replaceEnd);
            pos = result.indexOf(marker, replaceStart + publicRoot.length());
        }
        return result;
    }

    private boolean shouldHideProcMapsLine(String line) {
        if (TextUtils.isEmpty(line)) {
            return false;
        }
        if (isWritableExecutableProcMapsLine(line)) {
            return true;
        }
        if (line.contains("/blackbox/data/user/")) {
            return false;
        }
        String hostPackageName = BlackBoxCore.getHostPkg();
        return (!TextUtils.isEmpty(hostPackageName) && line.contains(hostPackageName))
                || line.contains("libblackbox")
                || line.contains("libblackhook")
                || line.contains("libblackdex")
                || line.contains("libpine")
                || line.contains("[anon:pine codes]");
    }

    private boolean isWritableExecutableProcMapsLine(String line) {
        String trimmed = line.trim();
        if (trimmed.length() == 0) {
            return false;
        }
        String[] parts = trimmed.split("\\s+", 3);
        if (parts.length < 2) {
            return false;
        }
        String perms = parts[1];
        return perms.indexOf('w') >= 0 && perms.indexOf('x') >= 0;
    }

    public File redirectPath(File path) {
        if (path == null)
            return null;
        String pathStr = path.getAbsolutePath();
        return new File(redirectPath(pathStr));
    }

    public String redirectPath(String path, Map<String, String> rule) {
        if (TextUtils.isEmpty(path))
            return path;

        //Search the key from TrieTree
        String key = mTrieTree.search(path);
        if (!TextUtils.isEmpty(key))
            path = path.replace(key, Objects.requireNonNull(rule.get(key)));

        return path;
    }

    public File redirectPath(File path, Map<String, String> rule) {
        if (path == null)
            return null;
        String pathStr = path.getAbsolutePath();
        return new File(redirectPath(pathStr, rule));
    }

    // 由于正常情况Application已完成重定向，以下重定向是怕代码写死。
    public void enableRedirect(Context context) {
        Map<String, String> rule = new LinkedHashMap<>();
        Set<String> blackRule = new HashSet<>();
        String packageName = context.getPackageName();

        try {
            ApplicationInfo packageInfo = BlackBoxCore.getBPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA, BActivityThread.getUserId());
            int systemUserId = BlackBoxCore.getHostUserId();
            rule.put(String.format("/data/data/%s/lib", packageName), packageInfo.nativeLibraryDir);
            rule.put(String.format("/data/user/%d/%s/lib", systemUserId, packageName), packageInfo.nativeLibraryDir);

            rule.put(String.format("/data/data/%s", packageName), packageInfo.dataDir);
            rule.put(String.format("/data/user/%d/%s", systemUserId, packageName), packageInfo.dataDir);

            if (BlackBoxCore.getContext().getExternalCacheDir() != null && context.getExternalCacheDir() != null) {
                File external = BEnvironment.getExternalUserDir(BActivityThread.getUserId());

                // sdcard
                rule.put("/sdcard", external.getAbsolutePath());
                rule.put(String.format("/storage/emulated/%d", systemUserId), external.getAbsolutePath());

                blackRule.add("/sdcard/Pictures");
                blackRule.add(String.format("/storage/emulated/%d/Pictures", systemUserId));
            }
            if (BlackBoxCore.get().isHideRoot()) {
                hideRoot(rule);
            }
            proc(rule);
        } catch (Exception e) {
            e.printStackTrace();
        }
        for (String key : rule.keySet()) {
            get().addRedirect(key, rule.get(key));
        }
        for (String s : blackRule) {
            get().addBlackRedirect(s);
        }
        NativeCore.enableIO();
    }

    private void hideRoot(Map<String, String> rule) {
        rule.put("/system/app/Superuser.apk", "/system/app/Superuser.apk-fake");
        rule.put("/sbin/su", "/sbin/su-fake");
        rule.put("/system/bin/su", "/system/bin/su-fake");
        rule.put("/system/xbin/su", "/system/xbin/su-fake");
        rule.put("/data/local/xbin/su", "/data/local/xbin/su-fake");
        rule.put("/data/local/bin/su", "/data/local/bin/su-fake");
        rule.put("/system/sd/xbin/su", "/system/sd/xbin/su-fake");
        rule.put("/system/bin/failsafe/su", "/system/bin/failsafe/su-fake");
        rule.put("/data/local/su", "/data/local/su-fake");
        rule.put("/su/bin/su", "/su/bin/su-fake");
    }

    private void proc(Map<String, String> rule) {
        int appPid = BActivityThread.getAppPid();
        int pid = Process.myPid();
        String selfProc = "/proc/self/";
        String proc = "/proc/" + pid + "/";

        String cmdline = new File(BEnvironment.getProcDir(appPid), "cmdline").getAbsolutePath();
        rule.put(proc + "cmdline", cmdline);
        rule.put(selfProc + "cmdline", cmdline);

        File version = ensureProcVersionFile(appPid);
        if (version.isFile()) {
            rule.put("/proc/version", version.getAbsolutePath());
        }
    }

    private File ensureProcVersionFile(int appPid) {
        File version = new File(BEnvironment.getProcDir(appPid), "version");
        if (version.isFile() && version.length() > 0) {
            return version;
        }
        String kernelVersion = System.getProperty("os.version");
        if (TextUtils.isEmpty(kernelVersion)) {
            kernelVersion = "4.14.186";
        }
        String banner = "Linux version " + kernelVersion
                + " (android-build@localhost) #1 SMP PREEMPT\n";
        try {
            FileUtils.writeToFile(banner.getBytes(StandardCharsets.UTF_8), version);
        } catch (IOException ignored) {
        }
        return version;
    }
}
