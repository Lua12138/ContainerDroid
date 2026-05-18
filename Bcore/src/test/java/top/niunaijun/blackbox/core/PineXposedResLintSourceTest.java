package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class PineXposedResLintSourceTest {

    @Test
    public void ioUtilsDoesNotDirectlyReferenceApi21ErrnoExceptionOnMinSdk19() throws Exception {
        String source = readSource(
                "pine-xposed-res/src/main/java/top/canyie/dreamland/utils/IOUtils.java",
                "Bcore/pine-xposed-res/src/main/java/top/canyie/dreamland/utils/IOUtils.java");

        assertFalse("pine-xposed-res minSdk is 19, so IOUtils must not directly reference API 21 android.system.ErrnoException",
                source.contains("import android.system.ErrnoException")
                        || source.contains("instanceof ErrnoException")
                        || source.contains("(ErrnoException)"));
        assertTrue("IOUtils should still extract errno values from nested IOException causes",
                source.contains("getErrno")
                        && source.contains("errno")
                        && source.contains("getField"));
    }
}
