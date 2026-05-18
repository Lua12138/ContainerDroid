package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class BProcessManagerDeathDetectionSourceTest {

    @Test
    public void processDeathIsDetectedByAppThreadBinderDeathAndLoggedWithRecordIdentity() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/core/system/BProcessManagerService.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/core/system/BProcessManagerService.java");

        assertTrue("BProcessManager should watch the sandbox app-thread binder",
                source.contains("appThread.linkToDeath(new IBinder.DeathRecipient()"));
        assertTrue("binder death should be treated as process death",
                source.contains("binderDied()")
                        && source.contains("onProcessDie(app)"));
        assertTrue("death log should include enough ProcessRecord identity for correlation",
                source.contains("logAppDied(app, appThread)")
                        && source.contains("pid=")
                        && source.contains("bpid=")
                        && source.contains("buid=")
                        && source.contains("userId=")
                        && source.contains("binderAlive="));
        assertTrue("binder-death cleanup should log /proc liveness before killing so false-positive death can be diagnosed",
                source.contains("logProcessStateBeforeKill(record)")
                        && source.contains("readProcStatusSummary")
                        && source.contains("/proc/"));
        assertTrue("record.kill() should remain default, with only an explicit debug property able to skip it for diagnostics",
                source.contains("SKIP_KILL_ON_BINDER_DIED_PROPERTY")
                        && source.contains("debug.blackbox.skip_kill_on_binder_died")
                        && source.contains("isSkipKillOnBinderDiedEnabled()")
                        && source.contains("record.kill();"));
        assertTrue("process-death diagnostics must stay generic and not be target-package gated",
                !source.contains("com.bestv") && !source.contains("BestV"));
    }
}
