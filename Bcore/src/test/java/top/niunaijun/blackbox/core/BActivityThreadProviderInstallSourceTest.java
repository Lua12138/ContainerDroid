package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

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
}
