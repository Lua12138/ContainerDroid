package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class JniDiagnosticsHookSourceTest {
    private static final List<String> FORBIDDEN_TARGET_MARKERS = Arrays.asList(
            "com.bestv",
            "TelnetCommand",
            "WONT",
            "Jiagu",
            "jiagu");

    @Test
    public void nativeHookInstallsJniDiagnosticsHook() throws Exception {
        String source = readSource("Bcore/src/main/cpp/BoxCore.cpp");

        assertTrue("BoxCore should include the JNI diagnostics hook header",
                source.contains("#include <Hook/JniDiagnosticsHook.h>"));
        assertTrue("nativeHook should delegate to the opt-in JNI diagnostics hook",
                source.contains("JniDiagnosticsHook::init(env);"));
        assertTrue("JNI diagnostics should be installed after the base hook setup",
                source.indexOf("BaseHook::init(env);") < source.indexOf("JniDiagnosticsHook::init(env);"));
    }

    @Test
    public void jniDiagnosticsHookIsBuiltIntoNativeLibrary() throws Exception {
        String source = readSource("Bcore/src/main/cpp/CMakeLists.txt");

        assertTrue("CMake should compile the JNI diagnostics hook",
                source.contains("Hook/JniDiagnosticsHook.cpp"));
    }

    @Test
    public void jniDiagnosticsHookOnlyLogsFailedFieldLookupsAndRestoresExceptions() throws Exception {
        String source = readSource("Bcore/src/main/cpp/Hook/JniDiagnosticsHook.cpp");

        assertTrue("Hook should copy and replace the current JNIEnv function table",
                source.contains("gOriginalFunctions = env->functions")
                        && source.contains("std::memcpy(&gHookedFunctions, gOriginalFunctions, sizeof(gHookedFunctions))")
                        && source.contains("env->functions = &gHookedFunctions"));
        assertTrue("Hook should replace only JNI field lookup slots",
                source.contains("gHookedFunctions.GetFieldID = hookedGetFieldID")
                        && source.contains("gHookedFunctions.GetStaticFieldID = hookedGetStaticFieldID")
                        && !source.contains("GetStaticIntField ="));
        assertTrue("Hook should log only failing field lookups with a pending exception",
                source.contains("result == nullptr && env->ExceptionCheck()"));
        assertTrue("Hook should preserve the original pending Java exception after inspection",
                source.contains("ExceptionOccurred()")
                        && source.contains("ExceptionClear()")
                        && source.contains("Throw(pending)")
                        && source.contains("DeleteLocalRef(pending)"));
        assertTrue("Hook should report the native caller library and offset generically",
                source.contains("dladdr(returnAddress, &info)")
                        && source.contains("__builtin_return_address(0)")
                        && source.contains("callerOffset=0x"));
        assertTrue("Hook should report class loader and declared-field metadata generically",
                source.contains("describeClassLoader(env, clazz)")
                        && source.contains("describeDeclaredFields(env, clazz, name)")
                        && source.contains("classLoader=%s")
                        && source.contains("declaredFields=%s")
                        && source.contains("getDeclaredFields")
                        && source.contains("getModifiers")
                        && source.contains("static="));
        assertTrue("Hook should guard against recursive JNI diagnostics",
                source.contains("thread_local bool gInHook"));
    }

    @Test
    public void jniDeclaredFieldDetailsAreExplicitlyDebugGated() throws Exception {
        String source = readSource("Bcore/src/main/cpp/Hook/JniDiagnosticsHook.cpp");

        assertTrue("JNI field diagnostics table replacement should be disabled unless explicitly requested",
                source.contains("isFieldDiagnosticsEnabled()")
                        && source.contains("native_property::getBoolJniDiagnostic(\"debug.blackbox.jni_field_diag\"")
                        && source.contains("if (!isFieldDiagnosticsEnabled())")
                        && source.contains("JNI diagnostics disabled by debug property"));
        assertTrue("Reflection-heavy field metadata should be behind an explicit debug property",
                source.contains("isDetailedFieldDiagnosticsEnabled()")
                        && source.contains("native_property::getBoolJniDiagnostic(\"debug.blackbox.jni_field_details\""));
        assertTrue("Default failed-lookup log should still be lightweight",
                source.contains("const bool detailed = isDetailedFieldDiagnosticsEnabled();")
                        && source.contains("detailed ? describeClassLoader(env, clazz) : \"disabled\"")
                        && source.contains("detailed ? describeDeclaredFields(env, clazz, name) : \"disabled\""));
    }

    @Test
    public void jniDiagnosticsHookDoesNotContainTargetSpecificMarkers() throws Exception {
        String source = readSource("Bcore/src/main/cpp/Hook/JniDiagnosticsHook.cpp");

        for (String marker : FORBIDDEN_TARGET_MARKERS) {
            assertFalse("JNI diagnostics hook must stay target-agnostic: " + marker,
                    source.contains(marker));
        }
    }

}
