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
        assertTrue("BlackReflection runtime must not be optimized away because it interprets generated mirror annotations dynamically",
                consumerRules.contains("-keep class top.niunaijun.blackreflection.BlackReflection { *; }")
                        && consumerRules.contains("-keep class top.niunaijun.blackreflection.BlackReflection$* { *; }"));
        assertTrue("BlackReflection annotation types are part of the generated mirror runtime contract",
                consumerRules.contains("-keep class top.niunaijun.blackreflection.annotation.** { *; }"));
        assertTrue("BlackReflection utility methods are called by generated BR* classes and by the invocation handler",
                consumerRules.contains("-keep class top.niunaijun.blackreflection.utils.** { *; }"));
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
