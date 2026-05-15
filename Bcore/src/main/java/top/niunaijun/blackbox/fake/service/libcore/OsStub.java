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
    private static final String[] PROPERTY_WIFI_INTERFACE_KEYS = new String[]{
            "wifi.interface",
            "ro.vendor.wifi.sap.interface",
            "wifi.concurrent.interface"
    };
    private static final String[] NETWORK_INTERFACE_CANDIDATES = new String[]{
            "dummy0", "wlan0", "lo", "ap0", "eth0"
    };
    private static final String[] CORE_NETWORK_INTERFACE_CANDIDATES = new String[]{
            "dummy0", "wlan0", "lo"
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
                        record.hardwareAddress == null ? null : record.hardwareAddress.clone());
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
            if (!shouldExposeSyntheticInterface(name)) {
                continue;
            }
            int index = readSysfsInterfaceIndex(name);
            if (index <= 0) {
                index = fallbackIndex++;
            } else {
                fallbackIndex = Math.max(fallbackIndex, index + 1);
            }
            interfaces.add(new InterfaceRecord(name, index, appVisibleHardwareAddress(name)));
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
                && name.matches("[A-Za-z0-9_.:-]+");
    }

    private static boolean isExistingNetworkInterface(String name) {
        return name != null && new File("/sys/class/net/" + name).exists();
    }

    private static boolean shouldExposeSyntheticInterface(String name) {
        if (isExistingNetworkInterface(name)) {
            return true;
        }
        for (String candidate : CORE_NETWORK_INTERFACE_CANDIDATES) {
            if (candidate.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * NetworkInterface.getHardwareAddress() follows Android R+ privacy rules:
     * non-system apps normally see null/empty MAC data. Supplying an empty
     * StructIfaddrs.hwaddr keeps OEM NetworkInterface code from dropping the
     * interface while still exposing no hardware address bytes to app code.
     */
    private static byte[] appVisibleHardwareAddress(String name) {
        return new byte[0];
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

    private static class InterfaceRecord {
        final String name;
        final int index;
        final byte[] hardwareAddress;

        InterfaceRecord(String name, int index, byte[] hardwareAddress) {
            this.name = name;
            this.index = index;
            this.hardwareAddress = hardwareAddress == null ? null : hardwareAddress.clone();
        }
    }

}
