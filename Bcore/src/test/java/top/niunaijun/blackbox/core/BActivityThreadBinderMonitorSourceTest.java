package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class BActivityThreadBinderMonitorSourceTest {

    @Test
    public void nativeBinderMonitorHonorsEnabledConfigGate() throws Exception {
        String source = readSource(
                "src/main/java/top/niunaijun/blackbox/app/BActivityThread.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java");

        assertTrue(source.contains("binderMonitorConfig.isEnabled() && binderMonitorConfig.isRecordNative()"));
        assertTrue(source.contains("binderMonitorConfig.isEnabled() && binderMonitorConfig.isRecordIoctl()"));
    }
}
