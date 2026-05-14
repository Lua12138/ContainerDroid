package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.net.wifi.WifiInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;

import black.android.net.wifi.BRIWifiManagerStub;
import black.android.net.wifi.BRWifiInfo;
import black.android.net.wifi.BRWifiSsid;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.compat.SystemPropertiesCompat;

/**
 * Created by Milk on 4/12/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class IWifiManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IWifiManagerProxy";
    private static final String DEFAULT_BLACKBOX_WIFI_MAC = "ac:62:5a:82:65:c4";
    private static final String[] WIFI_IDENTITY_INTERFACES = new String[]{
            "wlan0", "eth0", "ap0"
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

    public IWifiManagerProxy() {
        super(BRServiceManager.get().getService(Context.WIFI_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIWifiManagerStub.get().asInterface(BRServiceManager.get().getService(Context.WIFI_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.WIFI_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getConnectionInfo")
    public static class GetConnectionInfo extends MethodHook {
        /*
        * It doesn't have public method to set BSSID and SSID fields in WifiInfo class,
        * So the reflection framework invocation appeared.
        * commented by BlackBoxing at 2022/03/08
        * */
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            WifiInfo wifiInfo = (WifiInfo) method.invoke(who, args);
            String macAddress = getWifiMacAddress();
            BRWifiInfo.get(wifiInfo)._set_mBSSID(macAddress);
            BRWifiInfo.get(wifiInfo)._set_mMacAddress(macAddress);
            BRWifiInfo.get(wifiInfo)._set_mWifiSsid(BRWifiSsid.get().createFromAsciiEncoded("BlackBox_Wifi"));
            return wifiInfo;
        }

        private static String getWifiMacAddress() {
            for (String property : PROPERTY_WIFI_INTERFACE_KEYS) {
                String macAddress = readSysfsMacAddress(SystemPropertiesCompat.get(property));
                if (macAddress != null) {
                    return macAddress;
                }
            }
            for (String networkInterface : WIFI_IDENTITY_INTERFACES) {
                String macAddress = readSysfsMacAddress(networkInterface);
                if (macAddress != null) {
                    return macAddress;
                }
            }
            String systemPropertyMacAddress = readSystemPropertyMacAddress();
            if (systemPropertyMacAddress != null) {
                return systemPropertyMacAddress;
            }
            return DEFAULT_BLACKBOX_WIFI_MAC;
        }

        private static String readSysfsMacAddress(String networkInterface) {
            if (networkInterface == null || networkInterface.length() == 0) {
                return null;
            }
            File addressFile = new File("/sys/class/net/" + networkInterface + "/address");
            if (!addressFile.isFile()) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(addressFile))) {
                String value = reader.readLine();
                if (isUsableMacAddress(value)) {
                    return value.trim().toLowerCase();
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        private static String readSystemPropertyMacAddress() {
            for (String property : PROPERTY_WIFI_MAC_KEYS) {
                String macAddress = normalizeMacAddress(SystemPropertiesCompat.get(property));
                if (isUsableMacAddress(macAddress)) {
                    return macAddress;
                }
            }
            return null;
        }

        private static boolean isUsableMacAddress(String value) {
            return value != null
                    && value.trim().matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")
                    && !"02:00:00:00:00:00".equals(value.trim())
                    && !"00:00:00:00:00:00".equals(value.trim());
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

        public static String intIP2StringIP(int ip) {
            return (ip & 0xFF) + "." +
                    ((ip >> 8) & 0xFF) + "." +
                    ((ip >> 16) & 0xFF) + "." +
                    (ip >> 24 & 0xFF);
        }

        public static int ip2Int(String ipString) {
            // 取 ip 的各段
            String[] ipSlices = ipString.split("\\.");
            int rs = 0;
            for (int i = 0; i < ipSlices.length; i++) {
                // 将 ip 的每一段解析为 int，并根据位置左移 8 位
                int intSlice = Integer.parseInt(ipSlices[i]) << 8 * i;
                // 或运算
                rs = rs | intSlice;
            }
            return rs;
        }
    }
}
