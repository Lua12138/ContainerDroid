package top.niunaijun.blackbox.binder;

import android.content.Context;
import android.os.Build;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public final class BlackBoxBinderMonitor {
    private static final String TAG = "BlackBoxBinderMonitor";
    private static final String SOURCE_TRANSACT = "Pine.BinderProxy.transact";
    private static final String SOURCE_NATIVE_TRANSACT = "Native.BpBinder.transact";
    private static final String SOURCE_IOCTL = "Native.ioctl.BINDER_WRITE_READ";
    private static final long NATIVE_DEDUP_WINDOW_NS = 5_000_000L;
    private static final int CRASH_CONTEXT_MAX_EVENTS = 100;
    private static final Object LOCK = new Object();

    private static BinderMonitorConfig config = BinderMonitorConfig.defaultConfig();
    private static VirtualIdentity identity = VirtualIdentity.EMPTY;
    private static BinderMethodMapping methodMapping = BinderMethodMapping.createDefault();
    private static ParcelTokenCache tokenCache = new ParcelTokenCache();
    private static Map<Object, String> binderDescriptors =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());
    private static EventRingBuffer<JsonSerializable> ringBuffer =
            new EventRingBuffer<>(config.getMaxRingEvents());
    private static EventSink eventSink = NoopEventSink.INSTANCE;
    private static ThreadLocal<RecentBinderTransact> recentJavaTransact = new ThreadLocal<>();
    private static volatile BinderTransactInterceptor transactInterceptor;
    private static File outputDirectory;
    private static volatile boolean hooksInstalled;

    private BlackBoxBinderMonitor() {
    }

    public static void init(Context context, BinderMonitorConfig newConfig, VirtualIdentity newIdentity) {
        synchronized (LOCK) {
            config = newConfig == null ? BinderMonitorConfig.defaultConfig() : newConfig;
            identity = newIdentity == null ? VirtualIdentity.EMPTY : newIdentity;
            methodMapping = BinderMethodMapping.createDefault();
            loadMethodMaps(context, methodMapping);
            ringBuffer = new EventRingBuffer<>(config.getMaxRingEvents());
            recentJavaTransact = new ThreadLocal<>();
            outputDirectory = context == null ? null : new File(context.getFilesDir(), "binder_monitor");

            eventSink.close();
            eventSink = createSink(context, config);

            if (BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && shouldLogcat()) {
                logInfo("Binder monitor init:"
                        + " enabled=" + config.isEnabled()
                        + " recordProxy=" + config.isRecordProxy()
                        + " recordNative=" + config.isRecordNative()
                        + " recordIoctl=" + config.isRecordIoctl()
                        + " recordStack=" + config.isRecordStack()
                        + " logcat=" + config.isLogcat()
                        + " output=" + config.getOutput()
                        + " packages=" + config.getPackages()
                        + " watchDescriptors=" + config.getWatchDescriptors()
                        + " virtualPackage=" + identity.getVirtualPackage()
                        + " virtualProcess=" + identity.getVirtualProcess());
            }
            if (!config.isEnabled()) {
                if (BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && shouldLogcat()) {
                    logInfo("Binder monitor disabled by config; hooks not installed");
                }
                return;
            }
            installHooksLocked();
        }
    }

    public static void updateIdentity(VirtualIdentity newIdentity) {
        if (newIdentity != null) {
            identity = newIdentity;
        }
    }

    public static void setEnabled(boolean enabled) {
        BinderMonitorConfig old = config;
        config = new BinderMonitorConfig(
                enabled,
                old.getPackages(),
                old.isRecordStack(),
                old.isRecordProxy(),
                old.isRecordNative(),
                old.isRecordIoctl(),
                old.getWatchDescriptors(),
                old.getProcesses(),
                old.getWatchMethods(),
                old.getWatchCodes(),
                old.getWatchFlags(),
                old.getWatchThreads(),
                old.getMaxRingEvents(),
                old.getOutput(),
                old.isLogcat());
    }

    public static boolean isEnabled() {
        return config.isEnabled();
    }

    public static void setTransactInterceptor(BinderTransactInterceptor interceptor) {
        transactInterceptor = interceptor;
    }

    public static List<String> snapshotEvents() {
        List<JsonSerializable> events = ringBuffer.snapshot();
        List<String> jsonEvents = new ArrayList<>(events.size());
        for (JsonSerializable event : events) {
            jsonEvents.add(event.toJson());
        }
        return jsonEvents;
    }

    public static void recordProxyCall(String serviceName, String interfaceDescriptor,
                                       String methodName, String proxyClass, String argsSummary,
                                       String resultSummary, String result) {
        recordProxyCall(serviceName, interfaceDescriptor, methodName, proxyClass, argsSummary,
                resultSummary, result, false, false, false, false);
    }

    public static void recordProxyCall(String serviceName, String interfaceDescriptor,
                                       String methodName, String proxyClass, String argsSummary,
                                       String resultSummary, String result,
                                       boolean forwardedHost, boolean rewritten, boolean blocked) {
        recordProxyCall(serviceName, interfaceDescriptor, methodName, proxyClass, argsSummary,
                resultSummary, result, true, forwardedHost, rewritten, blocked);
    }

    private static void recordProxyCall(String serviceName, String interfaceDescriptor,
                                        String methodName, String proxyClass, String argsSummary,
                                        String resultSummary, String result,
                                        boolean explicitDecision, boolean forwardedHost,
                                        boolean rewritten, boolean blocked) {
        BinderMonitorConfig localConfig = config;
        VirtualIdentity localIdentity = identity;
        if (serviceName == null && interfaceDescriptor == null) {
            return;
        }
        if (!localConfig.isEnabled()
                || !localConfig.isRecordProxy()
                || !localConfig.shouldRecordPackage(localIdentity.getVirtualPackage())
                || !localConfig.shouldRecordProcess(localIdentity.getVirtualProcess())
                || !localConfig.shouldRecordDescriptor(interfaceDescriptor)
                || !localConfig.shouldRecordMethod(methodName)) {
            return;
        }
        emit(ProxyEvent.create(
                now(),
                localIdentity,
                serviceName,
                interfaceDescriptor,
                methodName,
                proxyClass,
                argsSummary,
                resultSummary,
                result,
                explicitDecision ? forwardedHost : "forwarded".equals(result),
                explicitDecision ? rewritten : "handled".equals(result),
                explicitDecision ? blocked : "blocked".equals(result)));
    }

    public static void recordNativeBinderTransact(String descriptor, int code, int flags,
                                                  int dataSize, boolean replyExpected,
                                                  String source) {
        BinderMonitorConfig localConfig = config;
        if (!localConfig.isEnabled() || !localConfig.isRecordNative()) {
            return;
        }
        recordExternalBinderTransact(descriptor, code, flags, dataSize, replyExpected,
                source == null ? SOURCE_NATIVE_TRANSACT : source, -1, null, localConfig, identity);
    }

    public static void recordIoctlBinderTransaction(String descriptor, int code, int flags,
                                                    int dataSize, boolean replyExpected) {
        recordIoctlBinderTransaction(descriptor, code, flags, dataSize, replyExpected, -1, null);
    }

    public static void recordIoctlBinderTransaction(String descriptor, int code, int flags,
                                                    int dataSize, boolean replyExpected,
                                                    int handle, String driverCommand) {
        BinderMonitorConfig localConfig = config;
        if (!localConfig.isEnabled() || !localConfig.isRecordIoctl()) {
            return;
        }
        recordExternalBinderTransact(descriptor, code, flags, dataSize, replyExpected,
                SOURCE_IOCTL, handle, driverCommand, localConfig, identity);
    }

    public static void writeCrashContext(Throwable throwable) {
        String reason = throwable == null ? "unknown" : throwable.getClass().getName() + ": " + throwable.getMessage();
        CrashContext crashContext = new CrashContext(now(), identity, reason,
                tail(ringBuffer.snapshot(), CRASH_CONTEXT_MAX_EVENTS));
        String json = crashContext.toJson();
        emit(crashContext);
        writeCrashContextFile(json);
    }

    static ParcelTokenCache getTokenCacheForTesting() {
        return tokenCache;
    }

    static void initForTesting(BinderMonitorConfig newConfig, VirtualIdentity newIdentity) {
        synchronized (LOCK) {
            config = newConfig == null ? BinderMonitorConfig.defaultConfig() : newConfig;
            identity = newIdentity == null ? VirtualIdentity.EMPTY : newIdentity;
            methodMapping = BinderMethodMapping.createDefault();
            tokenCache = new ParcelTokenCache();
            binderDescriptors = Collections.synchronizedMap(new WeakHashMap<Object, String>());
            ringBuffer = new EventRingBuffer<>(config.getMaxRingEvents());
            recentJavaTransact = new ThreadLocal<>();
            transactInterceptor = null;
            outputDirectory = null;
            eventSink.close();
            eventSink = NoopEventSink.INSTANCE;
        }
    }

    static void recordJavaBinderTransactForTesting(String descriptor, int code, int flags,
                                                   int dataSize, boolean replyExpected) {
        recordJavaBinderTransact(descriptor, methodMapping.resolve(descriptor, code), code,
                flags, dataSize, replyExpected, null, null, identity);
    }

    private static EventSink createSink(Context context, BinderMonitorConfig config) {
        File output = null;
        if ("jsonl".equals(config.getOutput()) && outputDirectory != null) {
            output = new File(outputDirectory, "events.jsonl");
        }
        if (output == null && !config.isLogcat()) {
            return NoopEventSink.INSTANCE;
        }
        return new AsyncJsonlEventSink(output, config.isLogcat(), Math.max(4096, config.getMaxRingEvents()));
    }

    private static void loadMethodMaps(Context context, BinderMethodMapping mapping) {
        tryRegisterJsonFile(mapping, new File("/data/local/tmp/binder_method_map.json"));
        if (context != null) {
            tryRegisterJsonFile(mapping, new File(context.getFilesDir(), "binder_monitor/binder_method_map.json"));
        }
    }

    private static void tryRegisterJsonFile(BinderMethodMapping mapping, File file) {
        try {
            mapping.registerJsonFile(file);
        } catch (Throwable e) {
            if (BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && shouldLogcat()) {
                logWarn("load binder method map failed: " + file, e);
            }
        }
    }

    private static void installHooksLocked() {
        if (hooksInstalled) {
            if (BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && shouldLogcat()) {
                logInfo("Binder monitor hooks already installed");
            }
            return;
        }
        boolean writeInterfaceToken = hookWriteInterfaceToken();
        boolean parcelRecycle = hookParcelRecycle();
        boolean binderProxyTransact = hookBinderProxyTransact();
        boolean binderProxyGetInterfaceDescriptor = hookBinderProxyGetInterfaceDescriptor();
        hooksInstalled = writeInterfaceToken || parcelRecycle
                || binderProxyTransact || binderProxyGetInterfaceDescriptor;
        if (BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && shouldLogcat()) {
            logInfo("Binder monitor hook install summary:"
                    + " hooksInstalled=" + hooksInstalled
                    + " writeInterfaceToken=" + writeInterfaceToken
                    + " parcelRecycle=" + parcelRecycle
                    + " binderProxyTransact=" + binderProxyTransact
                    + " binderProxyGetInterfaceDescriptor=" + binderProxyGetInterfaceDescriptor);
        }
    }

    private static boolean hookWriteInterfaceToken() {
        try {
            Method method = Parcel.class.getDeclaredMethod("writeInterfaceToken", String.class);
            Pine.hook(method, new MethodHook() {
                @Override
                public void afterCall(Pine.CallFrame callFrame) {
                    if (!config.isEnabled() || callFrame.args.length == 0) {
                        return;
                    }
                    Object descriptor = callFrame.args[0];
                    if (descriptor instanceof String) {
                        tokenCache.put(callFrame.thisObject, (String) descriptor, Process.myTid(), now());
                    }
                }
            });
            return true;
        } catch (Throwable e) {
            if (BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && shouldLogcat()) {
                logWarn("hook Parcel.writeInterfaceToken failed", e);
            }
            return false;
        }
    }

    private static boolean hookParcelRecycle() {
        try {
            Method method = Parcel.class.getDeclaredMethod("recycle");
            Pine.hook(method, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    tokenCache.remove(callFrame.thisObject);
                }
            });
            return true;
        } catch (Throwable e) {
            if (BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && shouldLogcat()) {
                logWarn("hook Parcel.recycle failed", e);
            }
            return false;
        }
    }

    private static boolean hookBinderProxyTransact() {
        try {
            Class<?> binderProxy = Class.forName("android.os.BinderProxy");
            Method method = binderProxy.getDeclaredMethod("transact",
                    int.class, Parcel.class, Parcel.class, int.class);
            method.setAccessible(true);
            Pine.hook(method, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    if (onBinderProxyTransact(callFrame.thisObject, callFrame.args)) {
                        callFrame.setResult(Boolean.TRUE);
                    }
                }
            });
            return true;
        } catch (Throwable e) {
            if (BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && shouldLogcat()) {
                logWarn("hook BinderProxy.transact failed", e);
            }
            return false;
        }
    }

    private static boolean hookBinderProxyGetInterfaceDescriptor() {
        try {
            Class<?> binderProxy = Class.forName("android.os.BinderProxy");
            Method method = binderProxy.getDeclaredMethod("getInterfaceDescriptor");
            method.setAccessible(true);
            Pine.hook(method, new MethodHook() {
                @Override
                public void afterCall(Pine.CallFrame callFrame) {
                    Object result = callFrame.getResult();
                    if (result instanceof String && callFrame.thisObject != null) {
                        binderDescriptors.put(callFrame.thisObject, (String) result);
                    }
                }
            });
            return true;
        } catch (Throwable e) {
            if (BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && shouldLogcat()) {
                logWarn("hook BinderProxy.getInterfaceDescriptor failed", e);
            }
            return false;
        }
    }

    private static boolean onBinderProxyTransact(Object binderProxy, Object[] args) {
        BinderMonitorConfig localConfig = config;
        VirtualIdentity localIdentity = identity;
        if (!localConfig.isEnabled()
                || !localConfig.shouldRecordPackage(localIdentity.getVirtualPackage())
                || !localConfig.shouldRecordProcess(localIdentity.getVirtualProcess())
                || args == null
                || args.length < 4) {
            return false;
        }
        int code = asInt(args[0]);
        Parcel data = args[1] instanceof Parcel ? (Parcel) args[1] : null;
        Parcel reply = args[2] instanceof Parcel ? (Parcel) args[2] : null;
        int flags = asInt(args[3]);
        String descriptor = resolveDescriptor(binderProxy, data);
        if (!localConfig.shouldRecordDescriptor(descriptor)) {
            return false;
        }
        int hostTid = Process.myTid();
        if (!localConfig.shouldRecordCode(code)
                || !localConfig.shouldRecordFlags(flags)
                || !localConfig.shouldRecordThread(hostTid)) {
            return false;
        }
        int dataSize = safeDataSize(data);
        String method = methodMapping.resolve(descriptor, code);
        if (!localConfig.shouldRecordMethod(method)) {
            return false;
        }
        String argsSummary = BinderPayloadSummary.summarize(descriptor, method, data, Build.VERSION.SDK_INT);
        recordJavaBinderTransact(descriptor, method, code, flags, dataSize, reply != null,
                argsSummary, stackOrNull(localConfig, descriptor, method), localIdentity);
        return interceptBinderTransact(binderProxy, code, data, reply, flags, descriptor, method, argsSummary);
    }

    private static boolean interceptBinderTransact(Object binderProxy, int code, Parcel data,
                                                   Parcel reply, int flags, String descriptor,
                                                   String method, String argsSummary) {
        BinderTransactInterceptor interceptor = transactInterceptor;
        if (interceptor == null) {
            return false;
        }
        try {
            return interceptor.onTransact(binderProxy, code, data, reply, flags, descriptor, method,
                    argsSummary);
        } catch (Throwable e) {
            if (BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && shouldLogcat()) {
                logWarn("binder transact interceptor failed", e);
            }
            return false;
        }
    }

    private static void recordExternalBinderTransact(String descriptor, int code, int flags,
                                                     int dataSize, boolean replyExpected,
                                                     String source, int handle,
                                                     String driverCommand,
                                                     BinderMonitorConfig localConfig,
                                                     VirtualIdentity localIdentity) {
        if (!localConfig.shouldRecordPackage(localIdentity.getVirtualPackage())
                || !localConfig.shouldRecordProcess(localIdentity.getVirtualProcess())
                || !localConfig.shouldRecordDescriptor(descriptor)) {
            return;
        }
        int hostTid = Process.myTid();
        if (!localConfig.shouldRecordCode(code)
                || !localConfig.shouldRecordFlags(flags)
                || !localConfig.shouldRecordThread(hostTid)) {
            return;
        }
        String method = methodMapping.resolve(descriptor, code);
        if (!localConfig.shouldRecordMethod(method)) {
            return;
        }
        long timestampNs = now();
        if (isDuplicateOfRecentJavaTransact(timestampNs, hostTid, descriptor, code, flags, dataSize)) {
            return;
        }
        List<String> stack = stackOrNull(localConfig, descriptor, method);
        emit(BinderEvent.transact(
                timestampNs,
                Process.myPid(),
                hostTid,
                localIdentity,
                descriptor,
                method,
                code,
                flags,
                dataSize,
                replyExpected,
                source,
                handle,
                driverCommand,
                stack));
    }

    private static void recordJavaBinderTransact(String descriptor, String method, int code,
                                                 int flags, int dataSize,
                                                 boolean replyExpected, String argsSummary,
                                                 List<String> stack, VirtualIdentity localIdentity) {
        long timestampNs = now();
        int hostTid = Process.myTid();
        recentJavaTransact.set(new RecentBinderTransact(
                timestampNs,
                hostTid,
                descriptor,
                code,
                flags,
                dataSize));
        emit(BinderEvent.transact(
                timestampNs,
                Process.myPid(),
                hostTid,
                localIdentity,
                descriptor,
                method,
                code,
                flags,
                dataSize,
                replyExpected,
                SOURCE_TRANSACT,
                argsSummary,
                stack));
    }

    private static boolean isDuplicateOfRecentJavaTransact(long timestampNs, int hostTid,
                                                           String descriptor, int code,
                                                           int flags, int dataSize) {
        RecentBinderTransact recent = recentJavaTransact.get();
        if (recent == null
                || recent.hostTid != hostTid
                || recent.code != code
                || recent.flags != flags
                || timestampNs - recent.timestampNs > NATIVE_DEDUP_WINDOW_NS
                || !sameDescriptor(recent.descriptor, descriptor)) {
            return false;
        }
        return recent.dataSize < 0 || dataSize < 0 || recent.dataSize == dataSize;
    }

    private static boolean sameDescriptor(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private static String resolveDescriptor(Object binderProxy, Parcel data) {
        ParcelTokenInfo tokenInfo = tokenCache.get(data);
        if (tokenInfo != null) {
            return tokenInfo.getDescriptor();
        }
        if (binderProxy == null) {
            return null;
        }
        return binderDescriptors.get(binderProxy);
    }

    private static int safeDataSize(Parcel data) {
        if (data == null) {
            return -1;
        }
        try {
            return data.dataSize();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int asInt(Object value) {
        return value instanceof Integer ? (Integer) value : 0;
    }

    private static List<String> stackOrNull(BinderMonitorConfig localConfig, String descriptor, String method) {
        if (!localConfig.isRecordStack()) {
            return null;
        }
        boolean descriptorWatched = !localConfig.getWatchDescriptors().isEmpty()
                && localConfig.getWatchDescriptors().contains(descriptor);
        boolean methodWatched = !localConfig.getWatchMethods().isEmpty()
                && localConfig.getWatchMethods().contains(method);
        return descriptorWatched || methodWatched ? captureStack() : null;
    }

    private static List<String> captureStack() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        List<String> stack = new ArrayList<>(trace.length);
        for (StackTraceElement element : trace) {
            stack.add(element.toString());
        }
        return stack;
    }

    private static long now() {
        return SystemClock.elapsedRealtimeNanos();
    }

    private static void emit(JsonSerializable event) {
        if (event == null) {
            return;
        }
        ringBuffer.add(event);
        eventSink.offer(event);
    }

    private static List<JsonSerializable> tail(List<JsonSerializable> events, int maxEvents) {
        if (events == null || events.size() <= maxEvents) {
            return events;
        }
        return new ArrayList<>(events.subList(events.size() - maxEvents, events.size()));
    }

    private static void writeCrashContextFile(String json) {
        File directory = outputDirectory;
        if (json == null || directory == null) {
            return;
        }
        if (!directory.exists() && !directory.mkdirs()) {
            return;
        }
        File file = new File(directory, "crash_context_" + Process.myPid() + "_" + now() + ".json");
        FileWriter writer = null;
        try {
            writer = new FileWriter(file, false);
            writer.write(json);
            writer.write('\n');
        } catch (IOException e) {
            if (BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && shouldLogcat()) {
                logWarn("write crash context failed", e);
            }
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static boolean shouldLogcat() {
        BinderMonitorConfig localConfig = config;
        return BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED
                && localConfig != null
                && localConfig.isLogcat();
    }

    private static void logInfo(String message) {
        if (shouldLogcat()) {
            Log.i(TAG, message);
        }
    }

    private static void logWarn(String message, Throwable throwable) {
        if (shouldLogcat()) {
            Log.w(TAG, message, throwable);
        }
    }

    private static final class RecentBinderTransact {
        final long timestampNs;
        final int hostTid;
        final String descriptor;
        final int code;
        final int flags;
        final int dataSize;

        RecentBinderTransact(long timestampNs, int hostTid, String descriptor,
                             int code, int flags, int dataSize) {
            this.timestampNs = timestampNs;
            this.hostTid = hostTid;
            this.descriptor = descriptor;
            this.code = code;
            this.flags = flags;
            this.dataSize = dataSize;
        }
    }

    public interface BinderTransactInterceptor {
        boolean onTransact(Object binderProxy, int code, Parcel data, Parcel reply, int flags,
                           String descriptor, String method, String argsSummary) throws Throwable;
    }
}
