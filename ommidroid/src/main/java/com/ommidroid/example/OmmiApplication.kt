package com.ommidroid.example

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.util.Log

class OmmiApplication : Application() {
    companion object {
        private const val TAG = "OmmiApplication"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var appContext: Context? = null

        @JvmStatic
        fun getContext(): Context = requireNotNull(appContext) { "OmmiApplication context is not initialized" }
    }

    override fun attachBaseContext(base: Context?) {
        try {
            super.attachBaseContext(base)
            val context = requireNotNull(base) { "Application base context is null" }
            appContext = context
            OmmiBlackBoxState.clearInitializationFailure()
            OmmiAppManager.doAttachBaseContext(context)
        } catch (throwable: Throwable) {
            Log.e(TAG, "Critical failure in attachBaseContext", throwable)
            if (base != null) {
                appContext = base
            }
            OmmiBlackBoxState.markInitializationFailure(throwable.message ?: "BlackBoxCore 初始化失败")
        }
    }

    override fun onCreate() {
        try {
            super.onCreate()
            if (appContext == null) {
                appContext = applicationContext
            }
            val initializationError = OmmiBlackBoxState.initializationErrorMessage
            if (initializationError != null) {
                Log.e(TAG, "Skipping doOnCreate due to initialization failure: $initializationError")
                return
            }
            OmmiAppManager.doOnCreate(getContext())
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed to create OmmiApplication", throwable)
        }
    }
}
