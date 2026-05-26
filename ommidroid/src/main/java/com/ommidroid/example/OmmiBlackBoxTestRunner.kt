package com.ommidroid.example

import android.app.Activity
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import top.niunaijun.blackbox.BlackBoxCore

object OmmiBlackBoxTestRunner {
    private const val TAG = "OmmiBlackBoxTest"
    private const val DEFAULT_TEST_PACKAGE = "com.example.tester"
    private val running = AtomicBoolean(false)

    internal fun resolveTestPackage(testPackage: String?): String {
        return testPackage?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_TEST_PACKAGE
    }

    fun start(activity: Activity, testPackage: String? = null) {
        if (!running.compareAndSet(false, true)) {
            Log.d(TAG, "test already running")
            return
        }
        val targetPackage = resolveTestPackage(testPackage)
        Log.d(TAG, "test start package=$targetPackage")
        thread(name = "OmmiBlackBoxTestRunner") {
            try {
                val blackBoxCore = BlackBoxCore.get()
                val installResult = blackBoxCore.installPackageAsUser(
                    targetPackage,
                    OmmiBlackBoxTestConfig.USER_ID,
                )
                Log.d(
                    TAG,
                    "install package=$targetPackage success=${installResult.success} msg=${installResult.msg}",
                )
                if (!installResult.success && !blackBoxCore.isInstalled(targetPackage, OmmiBlackBoxTestConfig.USER_ID)) {
                    Log.e(TAG, "test abort: install failed")
                    return@thread
                }
                activity.runOnUiThread {
                    val launched = blackBoxCore.launchApk(
                        targetPackage,
                        OmmiBlackBoxTestConfig.USER_ID,
                    )
                    Log.d(TAG, "launch package=$targetPackage result=$launched")
                    if (!launched) {
                        Log.e(TAG, "test abort: launch failed")
                    }
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, "test failed", throwable)
            } finally {
                running.set(false)
            }
        }
    }
}
