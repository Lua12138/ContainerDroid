package top.niunaijun.blackbox.core;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static top.niunaijun.blackbox.core.SourceAssertions.readSource;

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
}
