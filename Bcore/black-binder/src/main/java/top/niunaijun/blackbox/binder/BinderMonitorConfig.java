package top.niunaijun.blackbox.binder;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BinderMonitorConfig {
    private static final Pattern STRING_PATTERN = Pattern.compile("\"((?:\\\\.|[^\"])*)\"");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+");

    private final boolean enabled;
    private final Set<String> packages;
    private final boolean recordStack;
    private final boolean recordProxy;
    private final boolean recordNative;
    private final boolean recordIoctl;
    private final Set<String> watchDescriptors;
    private final Set<String> processes;
    private final Set<String> watchMethods;
    private final Set<Integer> watchCodes;
    private final Set<Integer> watchFlags;
    private final Set<Integer> watchThreads;
    private final int maxRingEvents;
    private final String output;
    private final boolean logcat;

    public BinderMonitorConfig(boolean enabled, Set<String> packages, boolean recordStack,
                               boolean recordProxy, boolean recordNative, boolean recordIoctl,
                               Set<String> watchDescriptors, int maxRingEvents, String output,
                               boolean logcat) {
        this(enabled, packages, recordStack, recordProxy, recordNative, recordIoctl,
                watchDescriptors, Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<Integer>emptySet(), Collections.<Integer>emptySet(),
                Collections.<Integer>emptySet(), maxRingEvents, output, logcat);
    }

    BinderMonitorConfig(boolean enabled, Set<String> packages, boolean recordStack,
                        boolean recordProxy, boolean recordNative, boolean recordIoctl,
                        Set<String> watchDescriptors, Set<String> processes,
                        Set<String> watchMethods, Set<Integer> watchCodes,
                        Set<Integer> watchFlags, Set<Integer> watchThreads,
                        int maxRingEvents, String output, boolean logcat) {
        this.enabled = sanitizeEnabled(enabled);
        this.packages = immutableCopy(packages);
        this.recordStack = recordStack;
        this.recordProxy = recordProxy;
        this.recordNative = recordNative;
        this.recordIoctl = recordIoctl;
        this.watchDescriptors = immutableCopy(watchDescriptors);
        this.processes = immutableCopy(processes);
        this.watchMethods = immutableCopy(watchMethods);
        this.watchCodes = immutableCopy(watchCodes);
        this.watchFlags = immutableCopy(watchFlags);
        this.watchThreads = immutableCopy(watchThreads);
        this.maxRingEvents = Math.max(1, maxRingEvents);
        this.output = output == null || output.length() == 0 ? "jsonl" : output;
        this.logcat = sanitizeLogcat(logcat);
    }

    public static BinderMonitorConfig defaultConfig() {
        return new BinderMonitorConfig(
                BuildConfig.DEBUG,
                Collections.<String>emptySet(),
                false,
                true,
                false,
                false,
                Collections.<String>emptySet(),
                2048,
                "jsonl",
                BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED);
    }

    public static BinderMonitorConfig fromJson(String json) {
        return fromJson(json, defaultConfig());
    }

    public static BinderMonitorConfig load(Context context) {
        BinderMonitorConfig config = defaultConfig();
        try {
            config = fromJsonFile(new File("/data/local/tmp/binder_monitor_config.json"), config);
        } catch (IOException ignored) {
        }
        if (context != null) {
            try {
                config = fromJsonFile(new File(context.getFilesDir(), "binder_monitor/config.json"), config);
            } catch (IOException ignored) {
            }
        }
        return config;
    }

    static BinderMonitorConfig fromJsonFile(File file, BinderMonitorConfig fallback) throws IOException {
        BinderMonitorConfig defaults = fallback == null ? defaultConfig() : fallback;
        if (file == null || !file.isFile()) {
            return defaults;
        }
        StringBuilder builder = new StringBuilder((int) Math.min(file.length(), 1024 * 1024));
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return fromJson(builder.toString(), defaults);
    }

    private static BinderMonitorConfig fromJson(String json, BinderMonitorConfig defaults) {
        defaults = defaults == null ? defaultConfig() : defaults;
        if (json == null) {
            return defaults;
        }
        return new BinderMonitorConfig(
                readBoolean(json, "enabled", defaults.enabled),
                readStringSet(json, "packages", defaults.packages),
                readBoolean(json, "record_stack", defaults.recordStack),
                readBoolean(json, "record_proxy", defaults.recordProxy),
                readBoolean(json, "record_native", defaults.recordNative),
                readBoolean(json, "record_ioctl", defaults.recordIoctl),
                readStringSet(json, "watch_descriptors", defaults.watchDescriptors),
                readStringSet(json, "processes", defaults.processes),
                readStringSet(json, "watch_methods", defaults.watchMethods),
                readIntegerSet(json, "watch_codes", defaults.watchCodes),
                readIntegerSet(json, "watch_flags", defaults.watchFlags),
                readIntegerSet(json, "watch_threads", defaults.watchThreads),
                readInt(json, "max_ring_events", defaults.maxRingEvents),
                readString(json, "output", defaults.output),
                readBoolean(json, "logcat", defaults.logcat));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean shouldRecordPackage(String packageName) {
        return packages.isEmpty() || packages.contains(packageName);
    }

    public boolean shouldRecordDescriptor(String descriptor) {
        return watchDescriptors.isEmpty() || watchDescriptors.contains(descriptor);
    }

    public boolean shouldRecordProcess(String processName) {
        return processes.isEmpty() || processes.contains(processName);
    }

    public boolean shouldRecordMethod(String method) {
        return watchMethods.isEmpty() || watchMethods.contains(method);
    }

    public boolean shouldRecordCode(int code) {
        return watchCodes.isEmpty() || watchCodes.contains(code);
    }

    public boolean shouldRecordFlags(int flags) {
        return watchFlags.isEmpty() || watchFlags.contains(flags);
    }

    public boolean shouldRecordThread(int tid) {
        return watchThreads.isEmpty() || watchThreads.contains(tid);
    }

    public boolean isRecordStack() {
        return recordStack;
    }

    public boolean isRecordProxy() {
        return recordProxy;
    }

    public boolean isRecordNative() {
        return recordNative;
    }

    public boolean isRecordIoctl() {
        return recordIoctl;
    }

    public int getMaxRingEvents() {
        return maxRingEvents;
    }

    public String getOutput() {
        return output;
    }

    public boolean isLogcat() {
        return logcat;
    }

    public BinderMonitorConfig withEnabled(boolean enabled) {
        return new BinderMonitorConfig(
                enabled,
                packages,
                recordStack,
                recordProxy,
                recordNative,
                recordIoctl,
                watchDescriptors,
                processes,
                watchMethods,
                watchCodes,
                watchFlags,
                watchThreads,
                maxRingEvents,
                output,
                logcat);
    }

    public BinderMonitorConfig withLogcat(boolean logcat) {
        return new BinderMonitorConfig(
                enabled,
                packages,
                recordStack,
                recordProxy,
                recordNative,
                recordIoctl,
                watchDescriptors,
                processes,
                watchMethods,
                watchCodes,
                watchFlags,
                watchThreads,
                maxRingEvents,
                output,
                logcat);
    }

    public Set<String> getPackages() {
        return packages;
    }

    public Set<String> getWatchDescriptors() {
        return watchDescriptors;
    }

    public Set<String> getProcesses() {
        return processes;
    }

    public Set<String> getWatchMethods() {
        return watchMethods;
    }

    public Set<Integer> getWatchCodes() {
        return watchCodes;
    }

    public Set<Integer> getWatchFlags() {
        return watchFlags;
    }

    public Set<Integer> getWatchThreads() {
        return watchThreads;
    }

    private static <T> Set<T> immutableCopy(Set<T> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(values));
    }

    private static boolean sanitizeLogcat(boolean requested) {
        return BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && requested;
    }

    private static boolean sanitizeEnabled(boolean requested) {
        return BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && requested;
    }

    private static boolean readBoolean(String json, String key, boolean defaultValue) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(json);
        return matcher.find() ? Boolean.parseBoolean(matcher.group(1)) : defaultValue;
    }

    private static int readInt(String json, String key, int defaultValue) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!matcher.find()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String readString(String json, String key, String defaultValue) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(json);
        return matcher.find() ? unescape(matcher.group(1)) : defaultValue;
    }

    private static Set<String> readStringSet(String json, String key, Set<String> defaultValue) {
        String arrayBody = readArrayBody(json, key);
        if (arrayBody == null) {
            return defaultValue;
        }
        Set<String> values = new HashSet<>();
        Matcher stringMatcher = STRING_PATTERN.matcher(arrayBody);
        while (stringMatcher.find()) {
            values.add(unescape(stringMatcher.group(1)));
        }
        return values;
    }

    private static Set<Integer> readIntegerSet(String json, String key, Set<Integer> defaultValue) {
        String arrayBody = readArrayBody(json, key);
        if (arrayBody == null) {
            return defaultValue;
        }
        Set<Integer> values = new HashSet<>();
        Matcher numberMatcher = NUMBER_PATTERN.matcher(arrayBody);
        while (numberMatcher.find()) {
            try {
                values.add(Integer.parseInt(numberMatcher.group()));
            } catch (NumberFormatException ignored) {
            }
        }
        return values;
    }

    private static String readArrayBody(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(json);
        return matcher.find() ? matcher.group(1) : null;
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
