package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SeccompVirtualUidSourceTest {

    @Test
    public void nativeCoreExposesVirtualUidConfigurationToSeccompShield() throws Exception {
        String nativeCore = readSource(
                "src/main/java/top/niunaijun/blackbox/core/NativeCore.java",
                "Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java");
        String boxCore = readSource(
                "src/main/cpp/BoxCore.cpp",
                "Bcore/src/main/cpp/BoxCore.cpp");

        assertTrue("NativeCore should expose a native virtual uid configuration method",
                nativeCore.contains("native void setVirtualUid(int virtualUid)"));
        assertTrue("BoxCore should register the setVirtualUid JNI method",
                boxCore.contains("{\"setVirtualUid\",")
                        && boxCore.contains("blackbox::seccomp::setVirtualUid(virtualUid)"));
    }

    @Test
    public void seccompShieldDoesNotTrapNativeGetuidSyscalls() throws Exception {
        String source = readSource(
                "src/main/cpp/SeccompShield.cpp",
                "Bcore/src/main/cpp/SeccompShield.cpp");
        String header = readSource(
                "src/main/cpp/SeccompShield.h",
                "Bcore/src/main/cpp/SeccompShield.h");

        assertTrue("SeccompShield should retain the configured sandbox uid",
                source.contains("gVirtualUid"));
        assertTrue("SeccompShield should expose setVirtualUid in the header",
                header.contains("void setVirtualUid(int virtual_uid);"));
        assertFalse("getuid must not be seccomp-trapped: it is too hot and creates nested SIGSYS in arbitrary threads",
                source.contains("BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysGetuid")
                        || source.contains("BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysGeteuid")
                        || source.contains("BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysGetuid32")
                        || source.contains("BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kSysGeteuid32"));
        assertFalse("The SIGSYS handler should not emulate virtual UID returns anymore",
                source.contains("isVirtualUidSyscall(sysno)")
                        || source.contains("emulateReturn(context, static_cast<uintptr_t>(virtual_uid))"));
    }

    @Test
    public void virtualUidConfigurationRemainsAvailableForJavaAndFutureNativeCallers() throws Exception {
        String source = readSource(
                "src/main/cpp/SeccompShield.cpp",
                "Bcore/src/main/cpp/SeccompShield.cpp");

        assertTrue("Virtual UID state should remain configurable even when seccomp no longer traps getuid",
                source.contains("void setVirtualUid(int virtual_uid)")
                        && source.contains("gVirtualUid.store(virtual_uid"));
    }

    private static String readSource(String moduleRelativePath, String rootRelativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            Path moduleCandidate = dir.resolve(moduleRelativePath);
            if (Files.isRegularFile(moduleCandidate)) {
                return new String(Files.readAllBytes(moduleCandidate), StandardCharsets.UTF_8);
            }
            Path rootCandidate = dir.resolve(rootRelativePath);
            if (Files.isRegularFile(rootCandidate)) {
                return new String(Files.readAllBytes(rootCandidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(rootRelativePath + " not found from " + current);
    }
}
