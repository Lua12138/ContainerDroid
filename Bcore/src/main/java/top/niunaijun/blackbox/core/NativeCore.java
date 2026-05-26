package top.niunaijun.blackbox.core;


import android.os.Process;
import android.os.Build;

import androidx.annotation.Keep;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexFile;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;
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
    private static final AtomicInteger DEX_DUMP_SEQUENCE = new AtomicInteger();
    private static final int DEX_COOKIE_MAX_FAILED_ATTEMPTS = 3;
    private static final int MAX_MEMORY_DEX_BUFFERS_PER_CALL = 16;
    private static final int MAX_MEMORY_DEX_BUFFER_BYTES = 16 * 1024 * 1024;
    private static final int MAX_MEMORY_DEX_BYTES_PER_CALL = 32 * 1024 * 1024;
    private static final Set<String> DUMPED_DEX_KEYS =
            Collections.synchronizedSet(new LinkedHashSet<String>());
    private static final Map<String, AtomicInteger> DEX_COOKIE_FAILURE_COUNTS =
            new ConcurrentHashMap<>();

    static {
        new File("");
        System.loadLibrary("blackbox");
    }

    public static native void init(int apiLevel);

    public static native void enableIO();

    public static native void addIORule(String targetPath, String relocatePath);

    public static native void hideXposed();

    public static native void installSeccompShield();

    public static native void installTerminationOnlySeccompShield();

    public static native void installTerminationTrapSeccompShield();

    public static native void installRawSyscallEnvironmentProbe();

    public static native void installRawSyscallTerminationProbe();

    public static native void setVirtualUid(int virtualUid);

    public static native void setNativeSandboxEnvironment(String packageName, String processName, String hostPackageName);

    public static native void setNativeSandboxEnvironmentPackage(String packageName);

    public static native void setNativeTerminationShieldPackage(String packageName);

    public static native void disableEarlyProcMapsShim();

    public static native void enterNativeInternalFileProbe();

    public static native void leaveNativeInternalFileProbe();

    public static native boolean writeSanitizedProcMapsSnapshot(String outputPath, String packageName);

    public static native void enableBinderMonitor(boolean recordNative, boolean recordIoctl);

    private static native boolean dumpDexCookieNative(long cookie, String outputDir);

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
        if (!BlackBoxCore.get().isDexDumpEnabled()) {
            return;
        }
        if (classLoader == null || packageName == null || BlackBoxCore.getContext() == null) {
            return;
        }
        File outputDir = new File(BlackBoxCore.getContext().getFilesDir(), packageName);
        FileUtils.mkdirs(outputDir);

        for (String sourcePath : collectDexSourcePaths(classLoader)) {
            if (!shouldDumpDexPath(sourcePath, packageName)) {
                continue;
            }
            dumpDexContainer(new File(sourcePath), outputDir, DEX_DUMP_SEQUENCE.getAndIncrement());
        }
        dumpDexCookies(DexFileCompat.getCookies(classLoader), outputDir);
    }

    public static void dumpDexPath(String sourcePath, String packageName, String sourceTag) {
        if (!BlackBoxCore.get().isDexDumpEnabled()) {
            return;
        }
        if (sourcePath == null || packageName == null || BlackBoxCore.getContext() == null) {
            return;
        }
        File outputDir = new File(BlackBoxCore.getContext().getFilesDir(), packageName);
        FileUtils.mkdirs(outputDir);
        dumpDexPathCandidates(sourcePath, outputDir, packageName);
    }

    public static void dumpDexFile(DexFile dexFile, String packageName, String sourceTag) {
        if (!BlackBoxCore.get().isDexDumpEnabled()) {
            return;
        }
        if (dexFile == null || packageName == null || BlackBoxCore.getContext() == null) {
            return;
        }
        File outputDir = new File(BlackBoxCore.getContext().getFilesDir(), packageName);
        FileUtils.mkdirs(outputDir);

        Object fileName = getFieldValue(dexFile, "mFileName");
        if (fileName instanceof String) {
            dumpDexPathCandidates((String) fileName, outputDir, packageName);
        }
        dumpDexCookies(DexFileCompat.getCookies(dexFile), outputDir);
    }

    public static void dumpDexByteBuffers(ByteBuffer[] buffers, String packageName, String sourceTag) {
        if (!BlackBoxCore.get().isDexDumpEnabled()) {
            return;
        }
        if (buffers == null || packageName == null || BlackBoxCore.getContext() == null) {
            return;
        }
        File outputDir = new File(BlackBoxCore.getContext().getFilesDir(), packageName);
        FileUtils.mkdirs(outputDir);

        String safeTag = sanitizeDumpName(sourceTag == null ? "memory" : sourceTag);
        int dumpedBuffers = 0;
        long dumpedBytes = 0;
        for (int i = 0; i < buffers.length; i++) {
            if (dumpedBuffers >= MAX_MEMORY_DEX_BUFFERS_PER_CALL) {
                Slog.w(TAG, "dumpDex memory skipped remaining buffers source=" + sourceTag
                        + " limit=" + MAX_MEMORY_DEX_BUFFERS_PER_CALL);
                break;
            }
            ByteBuffer buffer = buffers[i];
            if (buffer == null) {
                continue;
            }
            try {
                ByteBuffer duplicate = buffer.duplicate();
                int size = duplicate.remaining();
                if (size <= 0) {
                    continue;
                }
                if (size > MAX_MEMORY_DEX_BUFFER_BYTES) {
                    Slog.w(TAG, "dumpDex memory skipped oversized buffer source=" + sourceTag
                            + " index=" + i + " bytes=" + size
                            + " limit=" + MAX_MEMORY_DEX_BUFFER_BYTES);
                    continue;
                }
                if (dumpedBytes + size > MAX_MEMORY_DEX_BYTES_PER_CALL) {
                    Slog.w(TAG, "dumpDex memory skipped remaining buffers source=" + sourceTag
                            + " totalBytes=" + dumpedBytes + " nextBytes=" + size
                            + " limit=" + MAX_MEMORY_DEX_BYTES_PER_CALL);
                    break;
                }
                byte[] bytes = new byte[size];
                duplicate.get(bytes);
                String digest = sha1(bytes);
                String key = "memory:" + digest;
                if (!DUMPED_DEX_KEYS.add(key)) {
                    continue;
                }
                String extension = detectDexDumpExtension(bytes);
                String name = safeTag + "_" + i + "_" + digest.substring(0, 12) + extension;
                File out = new File(outputDir, buildDexDumpName(DEX_DUMP_SEQUENCE.getAndIncrement(), name));
                FileUtils.writeToFile(new ByteArrayInputStream(bytes), out);
                dumpedBytes += size;
                dumpedBuffers++;
                Slog.i(TAG, "dumpDex memory source=" + sourceTag + " index=" + i
                        + " bytes=" + size + " out=" + out.getAbsolutePath());
            } catch (Throwable e) {
                Slog.e(TAG, "dumpDex memory failed: " + sourceTag + "[" + i + "]", e);
            }
        }
    }

    private static Set<String> collectDexSourcePaths(ClassLoader classLoader) {
        Set<String> paths = new LinkedHashSet<>();
        Object pathList = getFieldValue(classLoader, "pathList");
        Object[] dexElements = (Object[]) getFieldValue(pathList, "dexElements");
        if (dexElements == null) {
            return paths;
        }

        for (Object dexElement : dexElements) {
            addPathValue(paths, getFieldValue(dexElement, "path"));
            addPathValue(paths, getFieldValue(dexElement, "file"));

            Object dexFile = getFieldValue(dexElement, "dexFile");
            Object mFileName = getFieldValue(dexFile, "mFileName");
            addPathValue(paths, mFileName);
        }
        return paths;
    }

    private static void addPathValue(Set<String> paths, Object value) {
        if (value instanceof File) {
            paths.add(((File) value).getAbsolutePath());
        } else if (value instanceof String) {
            paths.add((String) value);
        }
    }

    private static void dumpDexPathCandidates(String sourcePath, File outputDir, String packageName) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(sourcePath);
        try {
            String redirected = IOCore.get().redirectPath(sourcePath);
            if (redirected != null) {
                candidates.add(redirected);
            }
        } catch (Throwable ignored) {
        }
        for (String candidate : candidates) {
            if (!shouldDumpDexPath(candidate, packageName)) {
                continue;
            }
            dumpDexContainer(new File(candidate), outputDir, DEX_DUMP_SEQUENCE.getAndIncrement());
        }
    }

    private static boolean shouldDumpDexPath(String path, String packageName) {
        if (path == null || path.length() == 0 || packageName == null || packageName.length() == 0) {
            return false;
        }
        if (isFrameworkDexPath(path)) {
            return false;
        }
        return path.contains(packageName)
                || path.contains("/blackbox/data/user/")
                || path.contains("/blackbox/data/data/")
                || path.contains("/data/data/" + packageName)
                || path.contains("/data/user/0/" + packageName);
    }

    private static boolean isFrameworkDexPath(String path) {
        return path.startsWith("/system/")
                || path.startsWith("/apex/")
                || path.startsWith("/product/")
                || path.startsWith("/vendor/")
                || path.startsWith("/odm/")
                || path.startsWith("/data/dalvik-cache/");
    }

    private static Object getFieldValue(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        for (Class<?> clazz = target.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable e) {
                Slog.e(TAG, "dumpDex read field failed: " + fieldName, e);
                return null;
            }
        }
        return null;
    }

    private static void dumpDexContainer(File source, File outputDir, int sourceIndex) {
        if (source == null || !source.isFile()) {
            return;
        }
        String sourceKey = "file:" + source.getAbsolutePath()
                + ":" + source.length()
                + ":" + source.lastModified();
        if (!DUMPED_DEX_KEYS.add(sourceKey)) {
            return;
        }

        String sourceName = source.getName();
        if (sourceName.endsWith(".dex")) {
            try {
                File out = new File(outputDir, buildDexDumpName(sourceIndex, sourceName));
                FileUtils.copyFile(source, out);
                Slog.i(TAG, "dumpDex file source=" + source.getAbsolutePath()
                        + " out=" + out.getAbsolutePath());
            } catch (Throwable e) {
                Slog.e(TAG, "dumpDex copy dex failed: " + source, e);
            }
            return;
        }

        try (ZipFile zipFile = new ZipFile(source)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (entry.isDirectory()
                        || !("classes.dex".equals(entryName) || entryName.matches("classes[0-9]+\\.dex"))) {
                    continue;
                }
                File out = new File(outputDir, buildDexDumpName(sourceIndex, sourceName + "_" + entryName));
                try (InputStream inputStream = zipFile.getInputStream(entry)) {
                    FileUtils.writeToFile(inputStream, out);
                    Slog.i(TAG, "dumpDex zip source=" + source.getAbsolutePath()
                            + " entry=" + entryName
                            + " out=" + out.getAbsolutePath());
                }
            }
        } catch (Throwable e) {
            Slog.e(TAG, "dumpDex read container failed: " + source, e);
        }
    }

    private static void dumpDexCookies(List<Long> cookies, File outputDir) {
        if (cookies == null || outputDir == null) {
            return;
        }
        for (Long cookie : cookies) {
            dumpDexCookie(cookie, outputDir);
        }
    }

    private static void dumpDexCookie(Long cookie, File outputDir) {
        if (cookie == null || cookie == 0 || outputDir == null) {
            return;
        }
        String key = "cookie:" + Long.toHexString(cookie);
        if (DUMPED_DEX_KEYS.contains(key) || shouldSkipDexCookieAfterFailures(key)) {
            return;
        }
        try {
            if (dumpDexCookieNative(cookie, outputDir.getAbsolutePath())) {
                DUMPED_DEX_KEYS.add(key);
                DEX_COOKIE_FAILURE_COUNTS.remove(key);
                Slog.i(TAG, "dumpDex cookie=0x" + Long.toHexString(cookie)
                        + " out=" + outputDir.getAbsolutePath());
            } else {
                recordDexCookieFailure(key);
            }
        } catch (Throwable e) {
            recordDexCookieFailure(key);
            Slog.e(TAG, "dumpDex cookie failed: 0x" + Long.toHexString(cookie), e);
        }
    }

    private static boolean shouldSkipDexCookieAfterFailures(String key) {
        AtomicInteger failures = DEX_COOKIE_FAILURE_COUNTS.get(key);
        return failures != null && failures.get() >= DEX_COOKIE_MAX_FAILED_ATTEMPTS;
    }

    private static void recordDexCookieFailure(String key) {
        int failures = DEX_COOKIE_FAILURE_COUNTS
                .computeIfAbsent(key, ignored -> new AtomicInteger())
                .incrementAndGet();
        if (failures >= DEX_COOKIE_MAX_FAILED_ATTEMPTS) {
            Slog.w(TAG, "dumpDex cookie retry limit reached key=" + key
                    + " attempts=" + failures);
        }
    }

    private static String buildDexDumpName(int sourceIndex, String name) {
        return sourceIndex + "_" + name.replace('/', '_').replace('\\', '_');
    }

    private static String sanitizeDumpName(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    private static String detectDexDumpExtension(byte[] bytes) {
        if (bytes.length >= 4
                && bytes[0] == 'd'
                && bytes[1] == 'e'
                && bytes[2] == 'x'
                && bytes[3] == '\n') {
            return ".dex";
        }
        if (bytes.length >= 4
                && bytes[0] == 'P'
                && bytes[1] == 'K'
                && bytes[2] == 3
                && bytes[3] == 4) {
            return ".zip";
        }
        return ".bin";
    }

    private static String sha1(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] value = digest.digest(bytes);
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte b : value) {
            String hex = Integer.toHexString(b & 0xff);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
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
