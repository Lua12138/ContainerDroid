package top.niunaijun.blackbox.fake.service.libcore;

import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import black.libcore.io.BRLibcore;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.IOCore;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.compat.SystemPropertiesCompat;

/**
 * Created by Milk on 4/9/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class OsStub extends ClassInvocationStub {
    public static final String TAG = "OsStub";
    private static final byte[] DEFAULT_ANDROID_MAC_BYTES = new byte[]{
            0x02, 0x00, 0x00, 0x00, 0x00, 0x00
    };
    private static final String[] PROPERTY_WIFI_INTERFACE_KEYS = new String[]{
            "wifi.interface",
            "ro.vendor.wifi.sap.interface",
            "wifi.concurrent.interface"
    };
    private static final String[] PROPERTY_WIFI_MAC_KEYS = new String[]{
            "ro.ril.oem.wifimac",
            "ro.boot.wifimacaddr",
            "persist.vendor.wifi.mac"
    };
    private static final String[] NETWORK_INTERFACE_CANDIDATES = new String[]{
            "wlan0", "ap0", "eth0"
    };
    private Object mBase;

    public OsStub() {
        mBase = BRLibcore.get().os();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        BRLibcore.get()._set_os(proxyInvocation);
    }

    @Override
    protected void onBindMethod() {
    }

    @Override
    public boolean isBadEnv() {
        return BRLibcore.get().os() != getProxyInvocation();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null)
                    continue;
                if (args[i] instanceof String && ((String) args[i]).startsWith("/")) {
                    String orig = (String) args[i];
                    args[i] = IOCore.get().redirectPath(orig);
//                    if (!ObjectsCompat.equals(orig, args[i])) {
//                        Log.d(TAG, "redirectPath: " + orig + "  => " + args[i]);
//                    }
                }
            }
        }
        return super.invoke(proxy, method, args);
    }

    @ProxyMethod("getuid")
    public static class getuid extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int callUid = (int) method.invoke(who, args);
            return getFakeOwnerId(callUid);
        }
    }

    @ProxyMethod("stat")
    public static class stat extends StatHook {
    }

    @ProxyMethod("lstat")
    public static class lstat extends StatHook {
    }

    @ProxyMethod("fstat")
    public static class fstat extends StatHook {
    }

    @ProxyMethod("getifaddrs")
    public static class getifaddrs extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return buildSyntheticIfaddrsArray(method.getReturnType());
        }
    }

    @ProxyMethod("if_nametoindex")
    public static class if_nametoindex extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            if (args == null || args.length == 0 || !(args[0] instanceof String)) {
                return 0;
            }
            return findSyntheticInterfaceIndex((String) args[0]);
        }
    }

    @ProxyMethod("if_indextoname")
    public static class if_indextoname extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            if (args == null || args.length == 0 || !(args[0] instanceof Integer)) {
                return null;
            }
            return findSyntheticInterfaceName((Integer) args[0]);
        }
    }

    private abstract static class StatHook extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object invoke = null;
            try {
                invoke = method.invoke(who, args);
            } catch (Throwable e) {
                throw e.getCause();
            }
            sanitizeStructStat(invoke);
            return invoke;
        }
    }

    private static void sanitizeStructStat(Object stat) throws Exception {
        Reflector reflector = Reflector.with(stat);
        int uid = (Integer) reflector.field("st_uid").get();
        int gid = (Integer) Reflector.with(stat).field("st_gid").get();
        reflector.field("st_uid").set(getFakeOwnerId(uid));
        Reflector.with(stat).field("st_gid").set(getFakeOwnerId(gid));
    }

    private static int getFakeOwnerId(int ownerId) {
        if (ownerId > 0 && ownerId <= Process.FIRST_APPLICATION_UID)
            return ownerId;
//            Log.d(TAG, "getuid: " + BActivityThread.getAppPackageName() + ", " + BActivityThread.getAppUid());
        if (BActivityThread.isThreadInit() && BActivityThread.currentActivityThread().isInit()) {
            if (ownerId > Process.FIRST_APPLICATION_UID || ownerId == BlackBoxCore.getHostUid()) {
                return BActivityThread.getBAppId();
            }
            return ownerId;
        } else if (ownerId > Process.FIRST_APPLICATION_UID || ownerId == BlackBoxCore.getHostUid()) {
            return BlackBoxCore.getHostUid();
        } else {
            return ownerId;
        }
    }

    private static Object buildSyntheticIfaddrsArray(Class<?> returnType) {
        List<InterfaceRecord> interfaces = buildSyntheticInterfaces();
        try {
            Class<?> ifaddrsClass = returnType != null && returnType.isArray()
                    ? returnType.getComponentType()
                    : Class.forName("android.system.StructIfaddrs");
            Object array = Array.newInstance(ifaddrsClass, interfaces.size());
            Constructor<?> constructor = ifaddrsClass.getDeclaredConstructor(String.class, int.class, InetAddress.class, InetAddress.class, InetAddress.class, byte[].class);
            constructor.setAccessible(true);
            for (int i = 0; i < interfaces.size(); i++) {
                InterfaceRecord record = interfaces.get(i);
                Object ifaddrs = constructor.newInstance(
                        record.name,
                        0,
                        null,
                        null,
                        null,
                        record.hardwareAddress.clone());
                Array.set(array, i, ifaddrs);
            }
            return array;
        } catch (Throwable e) {
            Slog.d(TAG, "build synthetic StructIfaddrs failed: " + e);
            try {
                Class<?> ifaddrsClass = Class.forName("android.system.StructIfaddrs");
                return Array.newInstance(ifaddrsClass, 0);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static List<InterfaceRecord> buildSyntheticInterfaces() {
        List<InterfaceRecord> interfaces = new ArrayList<>();
        int fallbackIndex = 1;
        for (String name : collectSyntheticInterfaceNames()) {
            byte[] hardwareAddress = readHardwareAddress(name);
            if (hardwareAddress == null) {
                continue;
            }
            int index = readSysfsInterfaceIndex(name);
            if (index <= 0) {
                index = fallbackIndex++;
            } else {
                fallbackIndex = Math.max(fallbackIndex, index + 1);
            }
            interfaces.add(new InterfaceRecord(name, index, hardwareAddress));
        }
        return interfaces;
    }

    private static int findSyntheticInterfaceIndex(String name) {
        for (InterfaceRecord record : buildSyntheticInterfaces()) {
            if (record.name.equals(name)) {
                return record.index;
            }
        }
        return 0;
    }

    private static String findSyntheticInterfaceName(int index) {
        if (index <= 0) {
            return null;
        }
        for (InterfaceRecord record : buildSyntheticInterfaces()) {
            if (record.index == index) {
                return record.name;
            }
        }
        return null;
    }

    private static List<String> collectSyntheticInterfaceNames() {
        Map<String, Boolean> names = new LinkedHashMap<>();
        for (String property : PROPERTY_WIFI_INTERFACE_KEYS) {
            addSyntheticInterfaceName(names, SystemPropertiesCompat.get(property));
        }
        for (String name : NETWORK_INTERFACE_CANDIDATES) {
            addSyntheticInterfaceName(names, name);
        }
        return new ArrayList<>(names.keySet());
    }

    private static void addSyntheticInterfaceName(Map<String, Boolean> names, String name) {
        if (isValidSyntheticInterfaceName(name)) {
            names.put(name, Boolean.TRUE);
        }
    }

    private static boolean isValidSyntheticInterfaceName(String name) {
        return name != null
                && name.length() > 0
                && name.length() < 64
                && !name.equals("lo")
                && name.matches("[A-Za-z0-9_.:-]+");
    }

    private static byte[] readHardwareAddress(String name) {
        byte[] sysfsAddress = readSysfsHardwareAddress(name);
        if (sysfsAddress != null) {
            return sysfsAddress;
        }
        return readSystemPropertyMacAddress(name);
    }

    private static byte[] readSysfsHardwareAddress(String name) {
        File addressFile = new File("/sys/class/net/" + name + "/address");
        if (!addressFile.isFile()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(addressFile))) {
            return parseMac(reader.readLine());
        } catch (Throwable e) {
            Slog.d(TAG, "read hardware address failed for " + name + ": " + e);
            return null;
        }
    }

    private static byte[] readSystemPropertyMacAddress(String name) {
        if (!isWifiIdentityInterface(name)) {
            return null;
        }
        for (String property : PROPERTY_WIFI_MAC_KEYS) {
            byte[] mac = parseMac(SystemPropertiesCompat.get(property));
            if (mac != null) {
                return mac;
            }
        }
        return null;
    }

    private static boolean isWifiIdentityInterface(String name) {
        if ("wlan0".equals(name) || "ap0".equals(name)) {
            return true;
        }
        for (String property : PROPERTY_WIFI_INTERFACE_KEYS) {
            String propertyInterface = SystemPropertiesCompat.get(property);
            if (name != null && name.equals(propertyInterface)) {
                return true;
            }
        }
        return false;
    }

    private static int readSysfsInterfaceIndex(String name) {
        File ifindexFile = new File("/sys/class/net/" + name + "/ifindex");
        if (!ifindexFile.isFile()) {
            return -1;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(ifindexFile))) {
            return Integer.parseInt(reader.readLine().trim());
        } catch (Throwable e) {
            Slog.d(TAG, "read ifindex failed for " + name + ": " + e);
            return -1;
        }
    }

    private static byte[] parseMac(String value) {
        String normalized = normalizeMacAddress(value);
        if (normalized == null) {
            return null;
        }
        String[] parts = normalized.split(":");
        if (parts.length != 6) {
            return null;
        }
        byte[] mac = new byte[6];
        try {
            for (int i = 0; i < parts.length; i++) {
                mac[i] = (byte) Integer.parseInt(parts[i], 16);
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        if (Arrays.equals(mac, DEFAULT_ANDROID_MAC_BYTES)) {
            return null;
        }
        return mac;
    }

    private static String normalizeMacAddress(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) {
            return trimmed.toLowerCase();
        }
        if (trimmed.matches("(?i)[0-9a-f]{12}")) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < trimmed.length(); i += 2) {
                if (i > 0) {
                    builder.append(':');
                }
                builder.append(trimmed.substring(i, i + 2).toLowerCase());
            }
            return builder.toString();
        }
        return null;
    }

    private static class InterfaceRecord {
        final String name;
        final int index;
        final byte[] hardwareAddress;

        InterfaceRecord(String name, int index, byte[] hardwareAddress) {
            this.name = name;
            this.index = index;
            this.hardwareAddress = hardwareAddress.clone();
        }
    }

}
