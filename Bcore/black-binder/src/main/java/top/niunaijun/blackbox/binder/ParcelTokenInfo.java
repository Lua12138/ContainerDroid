package top.niunaijun.blackbox.binder;

public final class ParcelTokenInfo {
    private final String descriptor;
    private final int tid;
    private final long timestampNs;

    public ParcelTokenInfo(String descriptor, int tid, long timestampNs) {
        this.descriptor = descriptor;
        this.tid = tid;
        this.timestampNs = timestampNs;
    }

    public String getDescriptor() {
        return descriptor;
    }

    public int getTid() {
        return tid;
    }

    public long getTimestampNs() {
        return timestampNs;
    }
}
