package top.niunaijun.blackbox.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PineJitMoveInfoSourceTest {

    @Test
    public void pineJitCodeCacheCandidateIsValidatedBeforeMoveObsoleteMethod() throws Exception {
        String androidCpp = readSource("Bcore/pine-core/src/main/cpp/android.cpp");
        String androidHeader = readSource("Bcore/pine-core/src/main/cpp/android.h");

        assertTrue("JitCodeCache candidate validation should read process pointers before enabling MoveJitInfo",
                androidCpp.contains("isReadableProcessAddress")
                        && androidCpp.contains("jitCodeCacheFromJit")
                        && androidCpp.contains("jit_code_cache_validated_")
                        && androidCpp.contains("Pine JitCodeCache candidate"));
        assertTrue("MoveJitInfo must skip MoveObsoleteMethod unless the JitCodeCache pointer was validated",
                androidHeader.contains("jit_code_cache_validated_")
                        && androidHeader.indexOf("jit_code_cache_validated_")
                        < androidHeader.indexOf("move_obsolete_method_(jit_code_cache_, from, to)")
                        && androidHeader.contains("Skipping MoveJitInfo because JitCodeCache candidate was not validated"));
    }

    @Test
    public void pineJitCodeCacheValidationAccountsForAndroidRJitVtable() throws Exception {
        String androidCpp = readSource("Bcore/pine-core/src/main/cpp/android.cpp");

        assertTrue("Android R art::jit::Jit has a vtable before code_cache_",
                androidCpp.contains("kJitCodeCacheOffset = sizeof(void*)")
                        && androidCpp.contains("reinterpret_cast<uintptr_t>(jit) + kJitCodeCacheOffset")
                        && androidCpp.contains("jitCodeCacheSlot")
                        && androidCpp.contains("isReadableProcessAddress(jitCodeCacheSlot)"));
        assertFalse("reading the first word of art::jit::Jit validates against the vtable, not code_cache_",
                androidCpp.contains("jitCodeCacheFromJit = *reinterpret_cast<void**>(jit);"));
    }

    @Test
    public void pineMoveJitInfoIsDisabledOnAndroidRToAvoidHeapTaskDaemonRootSweepCrash() throws Exception {
        String androidHeader = readSource("Bcore/pine-core/src/main/cpp/android.h");

        assertTrue("Android R+ should clear backup jit info instead of calling MoveObsoleteMethod",
                androidHeader.contains("version < kR")
                        && androidHeader.indexOf("version < kR")
                        < androidHeader.indexOf("move_obsolete_method_(jit_code_cache_, from, to)")
                        && androidHeader.contains("Skipping MoveJitInfo on Android R+"));
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
