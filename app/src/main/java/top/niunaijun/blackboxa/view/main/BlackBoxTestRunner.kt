package top.niunaijun.blackboxa.view.main

import android.app.Activity
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object BlackBoxTestRunner {
    private const val TAG = "BlackBoxTest"
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
        Log.d(TAG, "test start")
        thread(name = "BlackBoxTestRunner") {
            try {
                val blackBoxCore = BlackBoxCore.get()
                val installResult = blackBoxCore.installPackageAsUser(
                    targetPackage,
                    BlackBoxTestConfig.USER_ID
                )
                Log.d(
                    TAG,
                    "install package=$targetPackage success=${installResult.success} msg=${installResult.msg}"
                )
                if (!installResult.success && !blackBoxCore.isInstalled(
                        targetPackage,
                        BlackBoxTestConfig.USER_ID
                    )
                ) {
                    Log.e(TAG, "test abort: install failed")
                    return@thread
                }
                activity.runOnUiThread {
                    val launched = blackBoxCore.launchApk(
                        targetPackage,
                        BlackBoxTestConfig.USER_ID
                    )
                    Log.d(TAG, "launch package=$targetPackage result=$launched")
                    if (!launched) {
                        Log.e(TAG, "test abort: launch failed")
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "test failed", e)
            } finally {
                running.set(false)
            }
        }
    }
}
