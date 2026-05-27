package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeHostGuardIntegrationSourceTest {
    @Test
    public void hostGuardIsBuiltIntoBcoreNativeLibrary() throws Exception {
        String bcoreBuildGradle = SourceAssertions.readSource("Bcore/build.gradle");
        String bcoreCmake = SourceAssertions.readSource("Bcore/src/main/cpp/CMakeLists.txt");
        String binderBuildGradle = SourceAssertions.readSource("Bcore/black-binder/build.gradle");

        assertTrue(bcoreBuildGradle.contains("blackboxGuardSignatureSha256"));
        assertTrue(bcoreBuildGradle.contains("blackboxGuardTracerIntervalSeconds"));
        assertTrue(bcoreBuildGradle.contains("blackboxGuardDiagnosticEnabled"));
        assertTrue(bcoreBuildGradle.contains("BLACKBOX_GUARD_SIGNATURE_SHA256"));
        assertTrue(bcoreBuildGradle.contains("BLACKBOX_GUARD_TRACER_INTERVAL_SECONDS"));
        assertTrue(bcoreBuildGradle.contains("BLACKBOX_GUARD_DIAGNOSTIC_ENABLED"));

        assertTrue(bcoreCmake.contains("HostGuard.cpp"));
        assertTrue(bcoreCmake.contains("target_compile_definitions(blackbox PRIVATE"));
        assertTrue(bcoreCmake.contains("BLACKBOX_GUARD_SIGNATURE_SHA256="));
        assertTrue(bcoreCmake.contains("BLACKBOX_GUARD_TRACER_INTERVAL_SECONDS="));
        assertTrue(bcoreCmake.contains("BLACKBOX_GUARD_DIAGNOSTIC_ENABLED="));
        assertFalse(bcoreCmake.contains("add_library(bbg SHARED"));
        assertFalse(bcoreCmake.contains("libbbg.so"));

        assertFalse(binderBuildGradle.contains("externalNativeBuild"));
        assertFalse(binderBuildGradle.contains("BLACKBOX_GUARD"));
        assertFalse(binderBuildGradle.contains("targets 'bbg'"));
    }

    @Test
    public void hostGuardRunsSilentlyFromBoxCoreJniOnLoad() throws Exception {
        String boxCore = SourceAssertions.readSource("Bcore/src/main/cpp/BoxCore.cpp");
        String blackBoxCore = SourceAssertions.readSource("Bcore/src/main/java/top/niunaijun/blackbox/BlackBoxCore.java");
        String binderConsumerRules = SourceAssertions.readSource("Bcore/black-binder/consumer-rules.pro");

        assertTrue(boxCore.contains("#include \"HostGuard.h\""));
        assertTrue(boxCore.contains("blackbox::hostguard::installHostGuard();"));
        assertTrue(boxCore.indexOf("blackbox::hostguard::installHostGuard();")
                < boxCore.indexOf("registerMethod(env);"));
        assertFalse(boxCore.contains("RegisterNatives(guardClass"));
        assertFalse(boxCore.contains("BlackBoxHostGuard"));

        assertFalse(blackBoxCore.contains("BlackBoxHostGuard"));
        assertFalse(blackBoxCore.contains("System.loadLibrary(\"bbg\")"));
        assertFalse(binderConsumerRules.contains("BlackBoxHostGuard"));
    }

    @Test
    public void hostGuardUsesMultipleProcessNameSources() throws Exception {
        String hostGuard = SourceAssertions.readSource("Bcore/src/main/cpp/HostGuard.cpp");

        assertTrue(hostGuard.contains("readProcSelfCmdline"));
        assertTrue(hostGuard.contains("readProcPidCmdline"));
        assertTrue(hostGuard.contains("readLibcProgramInvocationName"));
        assertTrue(hostGuard.contains("rawGetPid()"));
        assertTrue(hostGuard.contains("/proc/self/cmdline"));
        assertTrue(hostGuard.contains("/proc/"));
        assertTrue(hostGuard.contains("/cmdline"));
    }
}
