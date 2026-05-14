package top.niunaijun.blackbox.core;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

final class SeccompInstallGate {
    private static final File ENABLE_MARKER = new File("/data/local/tmp/blackbox_enable_seccomp");
    private static final File DISABLE_MARKER = new File("/data/local/tmp/blackbox_disable_seccomp");
    private static final String ABI_ARM64 = "arm64-v8a";
    private static final String ABI_ARM32 = "armeabi-v7a";

    enum InstallMode {
        TSYNC_THEN_PRCTL,
        UNSUPPORTED
    }

    private final AtomicBoolean installed = new AtomicBoolean();

    boolean tryInstall(String[] supportedAbis) {
        return tryInstall(supportedAbis,
                isEnabledByMarker(ENABLE_MARKER),
                isDisabledByMarker(DISABLE_MARKER));
    }

    boolean tryInstall(String[] supportedAbis, boolean enabledByMarker, boolean disabledByMarker) {
        if (!enabledByMarker || disabledByMarker) {
            return false;
        }
        if (installMode(supportedAbis) == InstallMode.UNSUPPORTED) {
            return false;
        }
        return installed.compareAndSet(false, true);
    }

    static boolean isEnabledByMarker(File marker) {
        return marker != null && marker.isFile();
    }

    static boolean isDisabledByMarker(File marker) {
        return marker != null && marker.isFile();
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
