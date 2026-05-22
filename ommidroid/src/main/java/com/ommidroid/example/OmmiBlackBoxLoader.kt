package com.ommidroid.example

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.BActivityThread
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback
import top.niunaijun.blackbox.app.configuration.ClientConfiguration

class OmmiBlackBoxLoader {
    fun attachBaseContext(context: Context) {
        try {
            BlackBoxCore.get().doAttachBaseContext(
                context,
                object : ClientConfiguration() {
                    override fun getHostPackageName(): String = context.packageName
                    override fun isHideRoot(): Boolean = false
                    override fun isHideXposed(): Boolean = false
                    override fun isEnableDaemonService(): Boolean = false
                    override fun isEnableLauncherActivity(): Boolean = true
                    override fun isEnableDiagnosticLogcat(): Boolean = false
                    override fun isEnableDexDump(): Boolean = false
                    override fun requestInstallPackage(file: File?, userId: Int): Boolean = false
                },
            )
        } catch (throwable: Throwable) {
            OmmiBlackBoxState.markInitializationFailure(throwable.message ?: "BlackBoxCore attachBaseContext 失败")
            Log.e(TAG, "Failed to attach BlackBoxCore", throwable)
        }
    }

    fun addLifecycleCallback() {
        try {
            BlackBoxCore.get().addAppLifecycleCallback(
                object : AppLifecycleCallback() {
                    override fun beforeCreateApplication(
                        packageName: String?,
                        processName: String?,
                        context: Context?,
                        userId: Int,
                    ) {
                        Log.d(TAG, "beforeCreateApplication pkg=$packageName process=$processName user=${BActivityThread.getUserId()}")
                    }

                    override fun beforeApplicationOnCreate(
                        packageName: String?,
                        processName: String?,
                        application: Application?,
                        userId: Int,
                    ) {
                        Log.d(TAG, "beforeApplicationOnCreate pkg=$packageName process=$processName")
                    }

                    override fun afterApplicationOnCreate(
                        packageName: String?,
                        processName: String?,
                        application: Application?,
                        userId: Int,
                    ) {
                        Log.d(TAG, "afterApplicationOnCreate pkg=$packageName process=$processName")
                    }
                },
            )
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed to add lifecycle callback", throwable)
        }
    }

    fun doOnCreate(context: Context) {
        try {
            BlackBoxCore.get().doCreate()
            Log.d(TAG, "BlackBoxCore created for ${context.packageName}")
        } catch (throwable: Throwable) {
            OmmiBlackBoxState.markInitializationFailure(throwable.message ?: "BlackBoxCore doCreate 失败")
            Log.e(TAG, "Failed to create BlackBoxCore", throwable)
        }
    }

    companion object {
        const val ACTION_REQUEST_STORAGE_PERMISSION = "com.ommidroid.example.REQUEST_STORAGE_PERMISSION"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_USER_ID = "user_id"
        private const val TAG = "OmmiBlackBoxLoader"
    }
}
