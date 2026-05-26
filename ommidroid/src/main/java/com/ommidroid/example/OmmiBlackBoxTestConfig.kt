package com.ommidroid.example

import android.content.Intent

object OmmiBlackBoxTestConfig {
    const val FLAG_TEST = "FLAG_TEST"
    const val TEST_PACKAGE = "TEST_PACKAGE"
    const val USER_ID = 0

    fun shouldRun(intent: Intent?): Boolean {
        return intent?.getBooleanExtra(FLAG_TEST, false) == true
    }

    fun getTestPackage(intent: Intent?): String? {
        return intent?.getStringExtra(TEST_PACKAGE)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
