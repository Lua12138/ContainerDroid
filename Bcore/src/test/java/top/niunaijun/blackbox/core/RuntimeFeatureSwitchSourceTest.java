package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.containsNativeString;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class RuntimeFeatureSwitchSourceTest {

    @Test
    public void bcoreExposesCompileAndRuntimeSwitchesForDiagnosticsAndDexDump() throws Exception {
        String buildGradle = readSource("Bcore/build.gradle");
        String clientConfiguration = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/app/configuration/ClientConfiguration.java");
        String blackBoxCore = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/BlackBoxCore.java");
        String appBuildGradle = readSource("app/build.gradle");
        String blackBoxLoader = readSource(
                "app/src/main/java/top/niunaijun/blackboxa/view/main/BlackBoxLoader.kt");

        assertTrue("Bcore build should expose a compile-time diagnostic logcat switch",
                buildGradle.contains("BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED"));
        assertTrue("Bcore build should expose a compile-time dex dump switch",
                buildGradle.contains("BLACKBOX_DEX_DUMP_ENABLED"));
        assertTrue("Sandbox configuration should let host code disable diagnostic logcat at runtime",
                clientConfiguration.contains("boolean isEnableDiagnosticLogcat()"));
        assertTrue("Sandbox configuration should let host code disable dex dump at runtime",
                clientConfiguration.contains("boolean isEnableDexDump()"));
        assertTrue("Diagnostic logcat should be gated by compile-time and runtime switches",
                blackBoxCore.contains("BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED")
                        && blackBoxCore.contains("mClientConfiguration.isEnableDiagnosticLogcat()")
                        && blackBoxCore.contains("boolean isDiagnosticLogcatEnabled()"));
        assertTrue("Dex dump should be gated by compile-time and runtime switches",
                blackBoxCore.contains("BuildConfig.BLACKBOX_DEX_DUMP_ENABLED")
                        && blackBoxCore.contains("mClientConfiguration.isEnableDexDump()")
                        && blackBoxCore.contains("boolean isDexDumpEnabled()"));
        assertTrue("Pine debug logcat should use the unified diagnostic logcat switch",
                blackBoxCore.contains("PineConfig.debug = isDiagnosticLogcatEnabled()"));
        assertTrue("The sample app should expose per-flavor BuildConfig defaults that can be edited in app/build.gradle",
                appBuildGradle.contains("BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED")
                        && appBuildGradle.contains("BLACKBOX_DEX_DUMP_ENABLED")
                        && blackBoxLoader.contains("BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED")
                        && blackBoxLoader.contains("BuildConfig.BLACKBOX_DEX_DUMP_ENABLED"));
    }

    @Test
    public void dexDumpEntrypointsRespectTheRuntimeSwitch() throws Exception {
        String bActivityThread = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java");
        String dexDumpProxy = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/DexDumpProxy.java");
        String packageManagerProxy = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IPackageManagerProxy.java");
        String applicationAttachProxy = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/fake/service/ApplicationAttachSeccompProxy.java");
        String nativeCore = readSource(
                "Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java");

        assertTrue("BActivityThread should only install DexDumpProxy when dex dump is enabled",
                bActivityThread.contains("if (BlackBoxCore.get().isDexDumpEnabled())")
                        && bActivityThread.contains("new DexDumpProxy().injectHook()"));
        assertTrue("BActivityThread lifecycle classloader dumps should be guarded by the dex dump switch",
                bActivityThread.contains("scheduleClassLoaderDumpIfEnabled"));
        assertTrue("DexDumpProxy should expose a single enabled check for all async dump paths",
                dexDumpProxy.contains("private static boolean isDexDumpEnabled()")
                        && dexDumpProxy.contains("BlackBoxCore.get().isDexDumpEnabled()"));
        assertTrue("DexDumpProxy injectHook should no-op when dex dump is disabled",
                dexDumpProxy.contains("if (!isDexDumpEnabled())")
                        && dexDumpProxy.indexOf("if (!isDexDumpEnabled())")
                        < dexDumpProxy.indexOf("hookClassLoaderConstructors"));
        assertTrue("Package-manager notifyDexLoad dumping should be no-op when dex dump is disabled",
                packageManagerProxy.contains("BlackBoxCore.get().isDexDumpEnabled()")
                        && packageManagerProxy.indexOf("BlackBoxCore.get().isDexDumpEnabled()")
                        < packageManagerProxy.indexOf("NativeCore.dumpDexPath"));
        assertTrue("Application.attach classloader dumping should reuse the guarded DexDumpProxy scheduler",
                applicationAttachProxy.contains("DexDumpProxy.scheduleClassLoaderDump"));
        assertTrue("NativeCore dump methods should be hard-gated so compile-time disabled builds ignore direct calls",
                nativeCore.contains("if (!BlackBoxCore.get().isDexDumpEnabled())")
                        && nativeCore.indexOf("if (!BlackBoxCore.get().isDexDumpEnabled())")
                        < nativeCore.indexOf("File outputDir = new File(BlackBoxCore.getContext().getFilesDir(), packageName)"));
    }

    @Test
    public void pineLogcatUsesTheUnifiedDiagnosticSwitch() throws Exception {
        String pineLog = readSource("Bcore/pine-core/src/main/cpp/utils/log.h");
        String pineNative = readSource("Bcore/pine-core/src/main/cpp/pine.cpp");
        String pineJava = readSource(
                "Bcore/pine-core/src/main/java/top/canyie/pine/Pine.java");

        assertTrue("Native Pine logcat macros should be gated by PineConfig.debug",
                pineLog.contains("PineConfig::debug")
                        && pineLog.contains("PINE_LOG_IF_ENABLED")
                        && pineLog.contains("#define LOGI(...) PINE_LOG_IF_ENABLED"));
        assertTrue("Native Pine init should receive the Java diagnostic switch before emitting logs",
                pineNative.indexOf("PineConfig::debug = static_cast<bool>(debug);")
                        < pineNative.indexOf("LOGI(")
                        && containsNativeString(pineNative, "Pine native init..."));
        assertTrue("Java Pine non-debug warnings/errors should be gated by PineConfig.debug",
                pineJava.contains("BuildConfig.BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED && PineConfig.debug")
                        && pineJava.contains("Log.w(TAG, \"Android version too high, not tested now...\");")
                        && pineJava.contains("Log.w(TAG, \"Cannot compile the target method, force replacement mode.\");")
                        && pineJava.contains("Log.e(TAG, \"Unexpected exception occurred when calling \""));
    }

    @Test
    public void diagnosticLogcatDisabledBuildPrunesJavaAndNativeLogsAtBuildTime() throws Exception {
        String appBuildGradle = readSource("app/build.gradle");
        String disabledProguard = readSource("app/proguard-diagnostic-logcat-disabled.pro");
        String pineBuildGradle = readSource("Bcore/pine-core/build.gradle");
        String pineCmake = readSource("Bcore/pine-core/src/main/cpp/CMakeLists.txt");
        String pineLog = readSource("Bcore/pine-core/src/main/cpp/utils/log.h");
        String bcoreBuildGradle = readSource("Bcore/build.gradle");
        String bcoreCmake = readSource("Bcore/src/main/cpp/CMakeLists.txt");
        String bcoreLog = readSource("Bcore/src/main/cpp/Log.h");
        String rawSyscallProbe = readSource("Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp");

        assertTrue("The app build should enable R8 when diagnostic logcat is compiled out",
                appBuildGradle.contains("blackboxDiagnosticLogcatMinifyEnabled")
                        && appBuildGradle.contains("blackboxDiagnosticLogcatMinifyRequested")
                        && appBuildGradle.contains("!blackboxDiagnosticLogcatEnabledValue ||")
                        && appBuildGradle.contains("proguard-diagnostic-logcat-disabled.pro"));
        assertTrue("The no-log diagnostic build should be production-like by default and avoid ART/JDWP debug slow paths",
                appBuildGradle.contains("blackboxDebuggableEnabledValue")
                        && appBuildGradle.contains("blackBoxBooleanOption('blackboxDebuggableEnabled', blackboxDiagnosticLogcatEnabledValue)")
                        && appBuildGradle.contains("debuggable blackboxDebuggableEnabledValue")
                        && appBuildGradle.contains("jniDebuggable blackboxDebuggableEnabledValue"));
        assertTrue("The diagnostic-logcat-disabled R8 rules should remove Java Log calls without obfuscating names",
                disabledProguard.contains("-assumenosideeffects class android.util.Log")
                        && disabledProguard.contains("-assumenosideeffects class top.niunaijun.blackbox.utils.Slog")
                        && disabledProguard.contains("-keep,allowoptimization class ** { *; }")
                        && disabledProguard.contains("-dontobfuscate"));
        assertTrue("Pine native build should receive the same compile-time diagnostic logcat switch",
                pineBuildGradle.contains("blackboxDiagnosticLogcatEnabledValue")
                        && pineBuildGradle.contains("BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED")
                        && pineBuildGradle.contains("-DPINE_LOGCAT_ENABLED="));
        assertTrue("Pine CMake should convert the Gradle switch into a native compile definition",
                pineCmake.contains("PINE_LOGCAT_ENABLED")
                        && pineCmake.contains("target_compile_definitions(pine PRIVATE PINE_LOGCAT_ENABLED="));
        assertTrue("Pine native log macros should compile log calls out when the macro is false",
                pineLog.contains("#if PINE_LOGCAT_ENABLED")
                        && pineLog.contains("#define LOGD(...) ((void) 0)")
                        && pineLog.contains("#define LOGF(...) ((void) 0)"));
        assertTrue("Bcore native build should receive the same compile-time diagnostic logcat switch",
                bcoreBuildGradle.contains("blackboxDiagnosticLogcatEnabledValue")
                        && bcoreBuildGradle.contains("-DBLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED="));
        assertTrue("Bcore CMake should convert the Gradle switch into a native compile definition",
                bcoreCmake.contains("BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED")
                        && bcoreCmake.contains("target_compile_definitions(blackbox PRIVATE")
                        && bcoreCmake.contains("BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED=${BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED}"));
        assertTrue("Bcore native debug log macros should compile log calls out when diagnostic logcat is disabled",
                bcoreLog.contains("#if BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED")
                        && bcoreLog.contains("#define log_print_debug(...) ((void) 0)"));
        assertTrue("raw SVC probe debug telemetry is hot-path diagnostic output and should use the compile-time logcat gate",
                rawSyscallProbe.contains("#if BLACKBOX_DIAGNOSTIC_LOGCAT_ENABLED")
                        && rawSyscallProbe.contains("#define RAW_SYSCALL_LOGD")
                        && rawSyscallProbe.contains("RAW_SYSCALL_LOGD("));
    }

}
