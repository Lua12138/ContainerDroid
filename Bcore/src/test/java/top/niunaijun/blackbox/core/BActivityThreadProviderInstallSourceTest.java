package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BActivityThreadProviderInstallSourceTest {

    @Test
    public void providerInstallFailuresAreLoggedWithProviderIdentity() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java");

        assertTrue(source.contains("install provider failed"));
        assertTrue(source.contains("providerInfo.name"));
        assertTrue(source.contains("providerInfo.authority"));
        assertTrue(source.contains("providerInfo.processName"));
        assertTrue(source.contains("Slog.e(TAG, \"install provider failed"));
        String installProviders = source.substring(
                source.indexOf("private void installProviders"),
                source.indexOf("public Object getPackageInfo()"));
        assertFalse("provider install failures must not be swallowed silently",
                installProviders.contains("catch (Throwable ignored)"));
    }

    @Test
    public void providerInstallUsesAndroidElevenInstallProviderPrototype() throws Exception {
        String source = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java");

        assertTrue(source.contains("findInstallProviderMethod(mainThread.getClass())"));
        assertTrue(source.contains("installProvider.getParameterTypes().length == 6"));
        assertTrue(source.contains("Context.class.isAssignableFrom(parameterTypes[0])"));
        assertTrue(source.contains("ProviderInfo.class.isAssignableFrom(parameterTypes[2])"));
        assertFalse("method lookup must not depend on declared-method order",
                source.contains("Reflector.findMethodByFirstName(mainThread.getClass(), \"installProvider\")"));
    }

    private static String readSource(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(relativePath + " not found from " + current);
    }
}
