package top.niunaijun.blackbox.binder;

public final class ProxyEvent implements JsonSerializable {
    private final long timestampNs;
    private final VirtualIdentity identity;
    private final String service;
    private final String interfaceDescriptor;
    private final String method;
    private final String proxyClass;
    private final String argsSummary;
    private final String resultSummary;
    private final String result;
    private final boolean forwardedHost;
    private final boolean rewritten;
    private final boolean blocked;

    private ProxyEvent(long timestampNs, VirtualIdentity identity, String service,
                       String interfaceDescriptor, String method, String proxyClass,
                       String argsSummary, String resultSummary, String result,
                       boolean forwardedHost, boolean rewritten, boolean blocked) {
        this.timestampNs = timestampNs;
        this.identity = identity == null ? VirtualIdentity.EMPTY : identity;
        this.service = service;
        this.interfaceDescriptor = interfaceDescriptor;
        this.method = method;
        this.proxyClass = proxyClass;
        this.argsSummary = argsSummary;
        this.resultSummary = resultSummary;
        this.result = result;
        this.forwardedHost = forwardedHost;
        this.rewritten = rewritten;
        this.blocked = blocked;
    }

    public static ProxyEvent create(long timestampNs, VirtualIdentity identity, String service,
                                    String interfaceDescriptor, String method, String proxyClass,
                                    String argsSummary, String resultSummary, String result) {
        boolean forwardedHost = "forwarded".equals(result);
        boolean blocked = "blocked".equals(result);
        boolean rewritten = "handled".equals(result);
        return create(timestampNs, identity, service, interfaceDescriptor, method, proxyClass,
                argsSummary, resultSummary, result, forwardedHost, rewritten, blocked);
    }

    public static ProxyEvent create(long timestampNs, VirtualIdentity identity, String service,
                                    String interfaceDescriptor, String method, String proxyClass,
                                    String argsSummary, String resultSummary, String result,
                                    boolean forwardedHost, boolean rewritten, boolean blocked) {
        return new ProxyEvent(timestampNs, identity, service, interfaceDescriptor, method,
                proxyClass, argsSummary, resultSummary, result, forwardedHost, rewritten, blocked);
    }

    @Override
    public String toJson() {
        StringBuilder builder = new StringBuilder(320);
        builder.append('{');
        JsonUtils.appendString(builder, "type", "blackbox_proxy_call");
        JsonUtils.appendLong(builder, "ts_ns", timestampNs);
        JsonUtils.appendString(builder, "virtual_package", identity.getVirtualPackage());
        JsonUtils.appendString(builder, "virtual_process", identity.getVirtualProcess());
        JsonUtils.appendString(builder, "service", service);
        JsonUtils.appendString(builder, "interface", interfaceDescriptor);
        JsonUtils.appendString(builder, "method", method);
        JsonUtils.appendString(builder, "proxy_class", proxyClass);
        JsonUtils.appendString(builder, "args_summary", argsSummary);
        JsonUtils.appendString(builder, "result_summary", resultSummary);
        JsonUtils.appendString(builder, "result", result);
        JsonUtils.appendBoolean(builder, "forwarded_host", forwardedHost);
        JsonUtils.appendBoolean(builder, "rewritten", rewritten);
        JsonUtils.appendBoolean(builder, "blocked", blocked);
        builder.append('}');
        return builder.toString();
    }
}
