package top.niunaijun.blackbox.binder;

public final class VirtualIdentity {
    public static final VirtualIdentity EMPTY = new VirtualIdentity(null, null, null, -1, -1, -1);

    private final String hostProcess;
    private final String virtualPackage;
    private final String virtualProcess;
    private final int virtualUid;
    private final int userId;
    private final int virtualPid;

    public VirtualIdentity(String hostProcess, String virtualPackage, String virtualProcess,
                           int virtualUid, int userId, int virtualPid) {
        this.hostProcess = hostProcess;
        this.virtualPackage = virtualPackage;
        this.virtualProcess = virtualProcess;
        this.virtualUid = virtualUid;
        this.userId = userId;
        this.virtualPid = virtualPid;
    }

    public String getHostProcess() {
        return hostProcess;
    }

    public String getVirtualPackage() {
        return virtualPackage;
    }

    public String getVirtualProcess() {
        return virtualProcess;
    }

    public int getVirtualUid() {
        return virtualUid;
    }

    public int getUserId() {
        return userId;
    }

    public int getVirtualPid() {
        return virtualPid;
    }
}
