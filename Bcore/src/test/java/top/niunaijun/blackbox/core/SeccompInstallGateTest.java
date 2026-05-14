package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SeccompInstallGateTest {

    @Test
    public void rejectsInstallWhenDiagnosticEnableMarkerIsAbsent() {
        SeccompInstallGate gate = new SeccompInstallGate();

        assertFalse(gate.tryInstall(new String[]{"arm64-v8a"}, false, false));
    }

    @Test
    public void allowsFirstInstallOnSupportedAbiWhenDiagnosticEnableMarkerIsPresent() {
        SeccompInstallGate gate = new SeccompInstallGate();

        assertTrue(gate.tryInstall(new String[]{"arm64-v8a"}, true, false));
    }

    @Test
    public void rejectsSecondInstallEvenOnSupportedAbi() {
        SeccompInstallGate gate = new SeccompInstallGate();

        assertTrue(gate.tryInstall(new String[]{"armeabi-v7a"}, true, false));
        assertFalse(gate.tryInstall(new String[]{"armeabi-v7a"}, true, false));
    }

    @Test
    public void rejectsUnsupportedAbi() {
        SeccompInstallGate gate = new SeccompInstallGate();

        assertFalse(gate.tryInstall(new String[]{"x86"}, true, false));
    }

    @Test
    public void rejectsInstallWhenDiagnosticDisableMarkerIsPresent() {
        SeccompInstallGate gate = new SeccompInstallGate();

        assertFalse(gate.tryInstall(new String[]{"armeabi-v7a"}, true, true));
    }

    @Test
    public void usesTsyncInstallModeOnSupportedAbi() {
        assertTrue(SeccompInstallGate.installMode(new String[]{"arm64-v8a"})
                == SeccompInstallGate.InstallMode.TSYNC_THEN_PRCTL);
        assertTrue(SeccompInstallGate.installMode(new String[]{"armeabi-v7a"})
                == SeccompInstallGate.InstallMode.TSYNC_THEN_PRCTL);
    }

    @Test
    public void reportsUnsupportedInstallModeOnUnknownAbi() {
        assertTrue(SeccompInstallGate.installMode(new String[]{"x86"})
                == SeccompInstallGate.InstallMode.UNSUPPORTED);
        assertTrue(SeccompInstallGate.installMode(null)
                == SeccompInstallGate.InstallMode.UNSUPPORTED);
    }
}
