package top.niunaijun.blackboxa.view.main

import org.junit.Assert.assertEquals
import org.junit.Test

class BlackBoxTestRunnerTest {

    @Test
    fun resolveTestPackage_returnsOverrideWhenProvided() {
        assertEquals(
            "com.example.override",
            BlackBoxTestRunner.resolveTestPackage("  com.example.override  ")
        )
    }

    @Test
    fun resolveTestPackage_returnsDefaultWhenOverrideMissing() {
        assertEquals(
            "com.example.tester",
            BlackBoxTestRunner.resolveTestPackage("   ")
        )
    }
}
