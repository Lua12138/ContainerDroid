package com.ommidroid.example

import org.junit.Assert.assertEquals
import org.junit.Test

class OmmiBlackBoxTestRunnerTest {
    @Test
    fun resolveTestPackage_returnsOverrideWhenProvided() {
        assertEquals(
            "com.example.override",
            OmmiBlackBoxTestRunner.resolveTestPackage("  com.example.override  "),
        )
    }

    @Test
    fun resolveTestPackage_returnsDefaultWhenOverrideMissing() {
        assertEquals(
            "com.example.tester",
            OmmiBlackBoxTestRunner.resolveTestPackage("   "),
        )
    }
}
