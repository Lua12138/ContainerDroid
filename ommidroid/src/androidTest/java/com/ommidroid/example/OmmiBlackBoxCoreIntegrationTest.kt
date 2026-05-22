package com.ommidroid.example

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith
import top.niunaijun.blackbox.BlackBoxCore
import java.io.File

@RunWith(AndroidJUnit4::class)
class OmmiBlackBoxCoreIntegrationTest {
    @After
    fun tearDown() {
        runCatching {
            BlackBoxCore.get().stopPackage(TEST_PACKAGE, USER_ID)
            BlackBoxCore.get().uninstallPackageAsUser(TEST_PACKAGE, USER_ID)
        }
    }

    @Test
    fun installsLaunchesStopsClearsAndUninstallsApkViaRepositoryBcoreCalls() {
        val apkFile = copyPhysicalPackageApkToCache()

        val core = BlackBoxCore.get()
        val repository = OmmiAppsRepository()
        runCatching {
            core.stopPackage(TEST_PACKAGE, USER_ID)
            core.uninstallPackageAsUser(TEST_PACKAGE, USER_ID)
        }

        val installResult = repository.installFromFile(apkFile, USER_ID).getOrThrow()
        assertTrue(
            "installFromFile($TEST_PACKAGE) failed: ${installResult.msg}",
            installResult.success,
        )
        assertTrue(core.isInstalled(TEST_PACKAGE, USER_ID))
        assertTrue(
            "installed applications should include $TEST_PACKAGE",
            repository.loadInstalledApps(USER_ID).getOrThrow().any { it.packageName == TEST_PACKAGE },
        )

        assertTrue("launch($TEST_PACKAGE) should return true", repository.launch(TEST_PACKAGE, USER_ID).getOrThrow())
        repository.stop(TEST_PACKAGE, USER_ID).getOrThrow()
        repository.clearData(TEST_PACKAGE, USER_ID).getOrThrow()
        repository.uninstall(TEST_PACKAGE, USER_ID).getOrThrow()
        assertFalse(core.isInstalled(TEST_PACKAGE, USER_ID))
    }

    private fun copyPhysicalPackageApkToCache(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        try {
            packageManager.getPackageInfo(TEST_PACKAGE, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            assumeNoException("Physical $TEST_PACKAGE must be installed before running this test", e)
        }
        val sourceApk = File(packageManager.getApplicationInfo(TEST_PACKAGE, 0).sourceDir)
        val cachedApk = File(context.cacheDir, "$TEST_PACKAGE.apk")
        sourceApk.copyTo(cachedApk, overwrite = true)
        return cachedApk
    }

    private companion object {
        private const val TEST_PACKAGE = "com.example.tester"
        private const val USER_ID = 0
    }
}
