package com.ommidroid.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

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

    @Test
    fun releaseProguardRules_doNotDuplicateBroadBcoreKeeps() {
        val rules = readProjectFile("proguard-rules.pro")

        assertFalse(rules.contains("-keep class top.niunaijun.blackbox.**"))
        assertFalse(rules.contains("-keep class top.niunaijun.jnihook.**"))
        assertFalse(rules.contains("-keep class mirror.**"))
        assertFalse(rules.contains("-keep class android.**"))
        assertFalse(rules.contains("-keep class com.android.**"))
        assertFalse(rules.contains("-keep @top.niunaijun.blackreflection.annotation"))
    }

    private fun readProjectFile(path: String): String {
        val candidates = listOf(
            File(path),
            File("ommidroid", path),
        )
        return candidates.first { it.isFile }.readText()
    }
}
