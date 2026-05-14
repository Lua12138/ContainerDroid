package top.niunaijun.blackbox.fake.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.net.NetworkInterface;
import java.util.Arrays;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;
import top.niunaijun.blackbox.binder.BlackBoxBinderMonitor;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.SystemPropertiesCompat;

public class NetworkInterfaceMacProxy implements IInjectHook {
    private static final String TAG = "NetworkInterfaceMacProxy";
    private static final String NETWORK_SERVICE = "network";
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

    @Override
    public void injectHook() {
        try {
            Method getHardwareAddress = NetworkInterface.class.getDeclaredMethod("getHardwareAddress");
            getHardwareAddress.setAccessible(true);
            Pine.hook(getHardwareAddress, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    maybeProvideHardwareAddress(callFrame);
                }
            });
        } catch (Throwable e) {
            Slog.d(TAG, "hook NetworkInterface.getHardwareAddress failed: " + e);
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    private static void maybeProvideHardwareAddress(Pine.CallFrame callFrame) {
        if (!(callFrame.thisObject instanceof NetworkInterface)) {
            return;
        }
        NetworkInterface networkInterface = (NetworkInterface) callFrame.thisObject;
        String name = networkInterface.getName();
        if (!isPhysicalNetworkInterface(name)) {
            return;
        }
        byte[] realHardwareAddress = readHardwareAddress(name);
        if (realHardwareAddress == null) {
            return;
        }
        callFrame.setResult(realHardwareAddress.clone());
        BlackBoxBinderMonitor.recordProxyCall(
                NETWORK_SERVICE,
                "java.net.NetworkInterface",
                "getHardwareAddress",
                NetworkInterfaceMacProxy.class.getSimpleName(),
                "interface=" + name,
                "mac=" + formatMac(realHardwareAddress),
                "handled",
                false,
                true,
                false);
    }

    private static boolean isPhysicalNetworkInterface(String name) {
        if ("wlan0".equals(name) || "eth0".equals(name) || "ap0".equals(name)) {
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

    private static boolean shouldReplaceHardwareAddress(byte[] hardwareAddress) {
        return hardwareAddress.length == 0 || Arrays.equals(hardwareAddress, DEFAULT_ANDROID_MAC_BYTES);
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

    private static String formatMac(byte[] mac) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) {
                builder.append(':');
            }
            builder.append(String.format("%02x", mac[i] & 0xff));
        }
        return builder.toString();
    }
}
