package top.niunaijun.blackbox.binder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BinderMethodMapping {
    private static final String TRANSACTION_PREFIX = "TRANSACTION_";
    private static final String[] DEFAULT_STUBS = new String[]{
            "android.app.IActivityManager$Stub",
            "android.app.IActivityTaskManager$Stub",
            "android.content.pm.IPackageManager$Stub",
            "android.content.IContentService$Stub",
            "android.accounts.IAccountManager$Stub",
            "android.location.ILocationManager$Stub",
            "com.android.internal.app.IAppOpsService$Stub",
            "android.app.INotificationManager$Stub",
            "android.content.IClipboard$Stub",
            "android.os.IUserManager$Stub",
            "android.app.admin.IDevicePolicyManager$Stub",
            "android.os.IServiceManager$Stub",
            "android.os.storage.IStorageManager$Stub"
    };

    private final Map<String, Map<Integer, String>> mappings = new HashMap<>();

    public static BinderMethodMapping createDefault() {
        BinderMethodMapping mapping = new BinderMethodMapping();
        mapping.registerDefaultStubs();
        mapping.register("android.app.IActivityManager", 3, "startActivity");
        mapping.register("android.content.pm.IPackageManager", 1, "checkPermission");
        mapping.register("android.content.pm.IPackageManager", 3, "getPackageInfo");
        mapping.register("android.os.IServiceManager", 1, "getService");
        mapping.register("android.os.IServiceManager", 2, "checkService");
        return mapping;
    }

    public synchronized void register(String descriptor, int code, String method) {
        if (descriptor == null || method == null) {
            return;
        }
        Map<Integer, String> byCode = mappings.get(descriptor);
        if (byCode == null) {
            byCode = new HashMap<>();
            mappings.put(descriptor, byCode);
        }
        byCode.put(code, method);
    }

    public void registerDefaultStubs() {
        ClassLoader bootClassLoader = ClassLoader.getSystemClassLoader();
        for (String stubName : DEFAULT_STUBS) {
            try {
                registerStub(Class.forName(stubName, false, bootClassLoader));
            } catch (Throwable ignored) {
            }
        }
    }

    public void registerStub(Class<?> stubClass) {
        if (stubClass == null) {
            return;
        }
        String descriptor = readDescriptor(stubClass);
        if (descriptor == null) {
            return;
        }
        Field[] fields = stubClass.getDeclaredFields();
        for (Field field : fields) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || field.getType() != int.class) {
                continue;
            }
            String name = field.getName();
            if (!name.startsWith(TRANSACTION_PREFIX)) {
                continue;
            }
            try {
                field.setAccessible(true);
                register(descriptor, field.getInt(null), name.substring(TRANSACTION_PREFIX.length()));
            } catch (Throwable ignored) {
            }
        }
    }

    public void registerJson(String json) {
        if (json == null) {
            return;
        }
        Matcher descriptorMatcher = Pattern.compile(
                "\"((?:\\\\.|[^\"])*)\"\\s*:\\s*\\{(.*?)\\}",
                Pattern.DOTALL).matcher(json);
        while (descriptorMatcher.find()) {
            String descriptor = unescape(descriptorMatcher.group(1));
            String body = descriptorMatcher.group(2);
            Matcher codeMatcher = Pattern.compile(
                    "\"(-?\\d+)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(body);
            while (codeMatcher.find()) {
                try {
                    register(descriptor, Integer.parseInt(codeMatcher.group(1)),
                            unescape(codeMatcher.group(2)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    public void registerJsonFile(File file) throws IOException {
        if (file == null || !file.isFile()) {
            return;
        }
        BufferedReader reader = new BufferedReader(new FileReader(file));
        StringBuilder builder = new StringBuilder((int) Math.min(file.length(), 1024 * 1024));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        } finally {
            reader.close();
        }
        registerJson(builder.toString());
    }

    public synchronized String resolve(String descriptor, int code) {
        if (descriptor == null) {
            return "unknown";
        }
        Map<Integer, String> byCode = mappings.get(descriptor);
        if (byCode == null) {
            return "unknown";
        }
        String method = byCode.get(code);
        return method == null ? "unknown" : method;
    }

    public synchronized Map<String, Map<Integer, String>> snapshot() {
        Map<String, Map<Integer, String>> copy = new HashMap<>();
        for (Map.Entry<String, Map<Integer, String>> entry : mappings.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(new HashMap<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String readDescriptor(Class<?> stubClass) {
        try {
            Field descriptor = stubClass.getDeclaredField("DESCRIPTOR");
            if (descriptor.getType() == String.class && Modifier.isStatic(descriptor.getModifiers())) {
                descriptor.setAccessible(true);
                Object value = descriptor.get(null);
                if (value instanceof String) {
                    return (String) value;
                }
            }
        } catch (Throwable ignored) {
        }
        String name = stubClass.getName();
        if (name.endsWith("$Stub")) {
            return name.substring(0, name.length() - "$Stub".length());
        }
        return null;
    }

    private static String unescape(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        boolean escaping = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!escaping) {
                if (c == '\\') {
                    escaping = true;
                } else {
                    builder.append(c);
                }
                continue;
            }
            switch (c) {
                case '"':
                case '\\':
                case '/':
                    builder.append(c);
                    break;
                case 'b':
                    builder.append('\b');
                    break;
                case 'f':
                    builder.append('\f');
                    break;
                case 'n':
                    builder.append('\n');
                    break;
                case 'r':
                    builder.append('\r');
                    break;
                case 't':
                    builder.append('\t');
                    break;
                default:
                    builder.append(c);
                    break;
            }
            escaping = false;
        }
        if (escaping) {
            builder.append('\\');
        }
        return builder.toString();
    }
}
