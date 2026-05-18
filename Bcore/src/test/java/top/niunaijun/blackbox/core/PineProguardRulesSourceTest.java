package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class PineProguardRulesSourceTest {

    @Test
    public void pineConsumerRulesKeepJniRegisteredClassesAndMembers() throws Exception {
        String consumerRules = readSource("Bcore/pine-core/consumer-rules.pro");

        assertTrue("Pine native JNI_OnLoad finds top/canyie/pine/Pine by name",
                consumerRules.contains("-keep class top.canyie.pine.Pine { *; }"));
        assertTrue("Pine native JNI_OnLoad finds top/canyie/pine/Ruler by name",
                consumerRules.contains("-keep class top.canyie.pine.Ruler { *; }"));
        assertTrue("Pine native init finds the Ruler inner interface by binary class name",
                consumerRules.contains("-keep class top.canyie.pine.Ruler$* { *; }"));
        assertTrue("Pine entry bridges are loaded by string class names from Pine.java",
                consumerRules.contains("-keep class top.canyie.pine.entry.** { *; }"));
        assertTrue("Pine utilities and callbacks are reflectively/JNI adjacent and should keep stable member names",
                consumerRules.contains("-keep class top.canyie.pine.callback.** { *; }")
                        && consumerRules.contains("-keep class top.canyie.pine.utils.** { *; }"));
    }
}
