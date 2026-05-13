package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SeccompInstallGateTest {

    @Test
    public void allowsFirstInstallOnSupportedAbi() {
        SeccompInstallGate gate = new SeccompInstallGate();

        assertTrue(gate.tryInstall(new String[]{"arm64-v8a"}));
    }

    @Test
    public void rejectsSecondInstallEvenOnSupportedAbi() {
        SeccompInstallGate gate = new SeccompInstallGate();

        assertTrue(gate.tryInstall(new String[]{"armeabi-v7a"}));
        assertFalse(gate.tryInstall(new String[]{"armeabi-v7a"}));
    }

    @Test
    public void rejectsUnsupportedAbi() {
        SeccompInstallGate gate = new SeccompInstallGate();

        assertFalse(gate.tryInstall(new String[]{"x86"}));
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
