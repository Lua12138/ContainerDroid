package top.niunaijun.blackbox.binder;

import java.util.List;

public final class CrashContext implements JsonSerializable {
    private final long timestampNs;
    private final VirtualIdentity identity;
    private final String reason;
    private final List<JsonSerializable> lastBinderEvents;

    public CrashContext(long timestampNs, VirtualIdentity identity, String reason,
                        List<JsonSerializable> lastBinderEvents) {
        this.timestampNs = timestampNs;
        this.identity = identity == null ? VirtualIdentity.EMPTY : identity;
        this.reason = reason;
        this.lastBinderEvents = lastBinderEvents;
    }

    @Override
    public String toJson() {
        StringBuilder builder = new StringBuilder(512);
        builder.append('{');
        JsonUtils.appendString(builder, "type", "crash_context");
        JsonUtils.appendLong(builder, "ts_ns", timestampNs);
        JsonUtils.appendString(builder, "virtual_package", identity.getVirtualPackage());
        JsonUtils.appendString(builder, "virtual_process", identity.getVirtualProcess());
        JsonUtils.appendString(builder, "reason", reason);
        JsonUtils.appendName(builder, "last_binder_events");
        builder.append('[');
        if (lastBinderEvents != null) {
            for (int i = 0; i < lastBinderEvents.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(lastBinderEvents.get(i).toJson());
            }
        }
        builder.append(']');
        builder.append('}');
        return builder.toString();
    }
}
