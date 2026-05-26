package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

public class HostPackagePropagationSourceTest {

    @Test
    public void nativeSandboxReceivesHostPackageFromClientConfiguration() throws Exception {
        String nativeCore = readSource("Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java");
        String bActivityThread = readSource("Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java");
        String boxCore = readSource("Bcore/src/main/cpp/BoxCore.cpp");
        String nativeFileHook = readSource("Bcore/src/main/cpp/Hook/NativeFileHook.cpp");

        assertTrue("NativeCore should expose host package as an explicit native sandbox argument",
                nativeCore.contains("setNativeSandboxEnvironment(String packageName, String processName, String hostPackageName)"));
        assertTrue("BActivityThread must pass the ClientConfiguration host package into native hooks",
                bActivityThread.contains("NativeCore.setNativeSandboxEnvironment(packageName, processName, BlackBoxCore.getHostPkg())"));
        assertTrue("JNI registration should include the host package argument",
                boxCore.contains("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V")
                        && boxCore.contains("setNativeSandboxEnvironment(package_name, process_name, host_package)"));
        assertTrue("Native file hooks should store the host package dynamically",
                nativeFileHook.contains("gNativeHostPackage")
                        && nativeFileHook.contains("isNativeHostPackagePath"));
    }

    @Test
    public void procMapsSanitizersDoNotHardcodeAppHostPackage() throws Exception {
        String ioCore = readSource("Bcore/src/main/java/top/niunaijun/blackbox/core/IOCore.java");
        String runtimeHook = readSource("Bcore/src/main/cpp/Hook/RuntimeHook.cpp");
        String nativeFileHook = readSource("Bcore/src/main/cpp/Hook/NativeFileHook.cpp");
        String rawSyscallProbe = readSource("Bcore/src/main/cpp/RawSyscallTerminationProbe.cpp");

        assertTrue("Java proc maps sanitizer should use BlackBoxCore.getHostPkg(), not an app package literal",
                ioCore.contains("BlackBoxCore.getHostPkg()"));
        assertFalse("Java proc maps sanitizer must not hardcode the historical app host package prefix",
                ioCore.contains("\"top.niunaijun.blackbox\""));
        assertFalse("Runtime proc shim must not hardcode app's old host package",
                runtimeHook.contains("top.niunaijun.blackboxa32"));
        assertFalse("Native file hook maps sanitizer must not hardcode a host package prefix",
                nativeFileHook.contains("kBlackBoxHostPackagePrefix"));
        assertFalse("Raw syscall map scanner must not hardcode app's old split APK package path",
                rawSyscallProbe.contains("top.niunaijun.blackboxa32"));
    }

    @Test
    public void manifestProxyTaskAffinityUsesHostApplicationIdPlaceholder() throws Exception {
        String manifest = readSource("Bcore/src/main/AndroidManifest.xml");

        assertFalse("Proxy task affinity must not expose the historical BlackBox host package to consuming apps",
                manifest.contains("top.niunaijun.blackbox.task_affinity"));
        assertTrue("Proxy task affinity should be derived from the consuming host applicationId",
                manifest.contains("${applicationId}.blackbox.task_affinity"));
    }
}
