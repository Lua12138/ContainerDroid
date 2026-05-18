package top.niunaijun.android_mirror;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class BlackReflectionProguardRulesTest {

    @Test
    public void consumerRulesKeepGeneratedBlackReflectionInterfaces() throws Exception {
        String consumerRules = readSource("android-mirror/consumer-rules.pro");

        assertTrue("BlackReflection relies on generated black.* method names at runtime",
                consumerRules.contains("-keep class black.** { *; }"));
        assertTrue("BlackReflection reads runtime class and parameter annotations",
                consumerRules.contains("-keepattributes")
                        && consumerRules.contains("*Annotation*"));
    }

    private static String readSource(String rootRelativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(rootRelativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(rootRelativePath + " not found from " + current);
    }
}
