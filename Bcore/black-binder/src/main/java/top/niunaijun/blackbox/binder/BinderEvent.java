package top.niunaijun.blackbox.binder;

import java.util.List;

public final class BinderEvent implements JsonSerializable {
    private static final int FLAG_ONEWAY = 1;

    private final long timestampNs;
    private final int hostPid;
    private final int hostTid;
    private final VirtualIdentity identity;
    private final String descriptor;
    private final String method;
    private final int code;
    private final int flags;
    private final int dataSize;
    private final boolean replyExpected;
    private final String argsSummary;
    private final String source;
    private final int handle;
    private final String driverCommand;
    private final List<String> callStack;

    private BinderEvent(long timestampNs, int hostPid, int hostTid, VirtualIdentity identity,
                        String descriptor, String method, int code, int flags, int dataSize,
                        boolean replyExpected, String argsSummary, String source, int handle,
                        String driverCommand, List<String> callStack) {
        this.timestampNs = timestampNs;
        this.hostPid = hostPid;
        this.hostTid = hostTid;
        this.identity = identity == null ? VirtualIdentity.EMPTY : identity;
        this.descriptor = descriptor;
        this.method = method == null ? "unknown" : method;
        this.code = code;
        this.flags = flags;
        this.dataSize = dataSize;
        this.replyExpected = replyExpected;
        this.argsSummary = argsSummary;
        this.source = source;
        this.handle = handle;
        this.driverCommand = driverCommand;
        this.callStack = callStack;
    }

    public static BinderEvent transact(long timestampNs, int hostPid, int hostTid,
                                       VirtualIdentity identity, String descriptor, String method,
                                       int code, int flags, int dataSize, boolean replyExpected,
                                       String source, List<String> callStack) {
        return new BinderEvent(timestampNs, hostPid, hostTid, identity, descriptor, method, code,
                flags, dataSize, replyExpected, null, source, -1, null, callStack);
    }

    public static BinderEvent transact(long timestampNs, int hostPid, int hostTid,
                                       VirtualIdentity identity, String descriptor, String method,
                                       int code, int flags, int dataSize, boolean replyExpected,
                                       String source, String argsSummary, List<String> callStack) {
        return new BinderEvent(timestampNs, hostPid, hostTid, identity, descriptor, method, code,
                flags, dataSize, replyExpected, argsSummary, source, -1, null, callStack);
    }

    public static BinderEvent transact(long timestampNs, int hostPid, int hostTid,
                                       VirtualIdentity identity, String descriptor, String method,
                                       int code, int flags, int dataSize, boolean replyExpected,
                                       String source, int handle, String driverCommand,
                                       List<String> callStack) {
        return new BinderEvent(timestampNs, hostPid, hostTid, identity, descriptor, method, code,
                flags, dataSize, replyExpected, null, source, handle, driverCommand, callStack);
    }

    public String getDescriptor() {
        return descriptor;
    }

    public String getMethod() {
        return method;
    }

    public int getCode() {
        return code;
    }

    @Override
    public String toJson() {
        StringBuilder builder = new StringBuilder(384);
        builder.append('{');
        JsonUtils.appendString(builder, "type", "binder_transact");
        JsonUtils.appendLong(builder, "ts_ns", timestampNs);
        JsonUtils.appendInt(builder, "host_pid", hostPid);
        JsonUtils.appendInt(builder, "host_tid", hostTid);
        JsonUtils.appendString(builder, "host_process", identity.getHostProcess());
        JsonUtils.appendInt(builder, "virtual_uid", identity.getVirtualUid());
        JsonUtils.appendInt(builder, "virtual_pid", identity.getVirtualPid());
        JsonUtils.appendString(builder, "virtual_package", identity.getVirtualPackage());
        JsonUtils.appendString(builder, "virtual_process", identity.getVirtualProcess());
        JsonUtils.appendInt(builder, "user_id", identity.getUserId());
        JsonUtils.appendString(builder, "descriptor", descriptor);
        JsonUtils.appendString(builder, "method", method);
        JsonUtils.appendInt(builder, "code", code);
        JsonUtils.appendInt(builder, "flags", flags);
        JsonUtils.appendBoolean(builder, "oneway", (flags & FLAG_ONEWAY) != 0);
        JsonUtils.appendInt(builder, "data_size", dataSize);
        JsonUtils.appendBoolean(builder, "reply_expected", replyExpected);
        if (argsSummary != null) {
            JsonUtils.appendString(builder, "args_summary", argsSummary);
        }
        JsonUtils.appendString(builder, "source", source);
        if (handle >= 0) {
            JsonUtils.appendInt(builder, "handle", handle);
        }
        if (driverCommand != null) {
            JsonUtils.appendString(builder, "driver_command", driverCommand);
        }
        if (callStack != null && !callStack.isEmpty()) {
            JsonUtils.appendName(builder, "call_stack");
            builder.append('[');
            for (int i = 0; i < callStack.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append('"').append(JsonUtils.escape(callStack.get(i))).append('"');
            }
            builder.append(']');
        }
        builder.append('}');
        return builder.toString();
    }
}
