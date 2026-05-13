package top.niunaijun.blackbox.core;

import java.util.concurrent.atomic.AtomicBoolean;

final class SeccompInstallGate {
    private static final String ABI_ARM64 = "arm64-v8a";
    private static final String ABI_ARM32 = "armeabi-v7a";

    enum InstallMode {
        TSYNC_THEN_PRCTL,
        UNSUPPORTED
    }

    private final AtomicBoolean installed = new AtomicBoolean();

    boolean tryInstall(String[] supportedAbis) {
        if (installMode(supportedAbis) == InstallMode.UNSUPPORTED) {
            return false;
        }
        return installed.compareAndSet(false, true);
    }

    static InstallMode installMode(String[] supportedAbis) {
        return isSupportedAbi(supportedAbis) ? InstallMode.TSYNC_THEN_PRCTL : InstallMode.UNSUPPORTED;
    }

    static boolean isSupportedAbi(String[] supportedAbis) {
        if (supportedAbis == null) {
            return false;
        }
        for (String abi : supportedAbis) {
            if (ABI_ARM64.equals(abi) || ABI_ARM32.equals(abi)) {
                return true;
            }
        }
        return false;
    }
}
