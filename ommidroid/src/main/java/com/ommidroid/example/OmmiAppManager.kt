package com.ommidroid.example

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object OmmiAppManager {
    private const val TAG = "OmmiAppManager"

    @JvmStatic
    val blackBoxLoader by lazy {
        try {
            OmmiBlackBoxLoader()
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed to create OmmiBlackBoxLoader", throwable)
            OmmiBlackBoxLoader()
        }
    }

    @JvmStatic
    val remarkSharedPreferences: SharedPreferences by lazy {
        OmmiApplication.getContext().getSharedPreferences("OmmiUserRemark", Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun requireContext(): Context = OmmiApplication.getContext()

    fun doAttachBaseContext(context: Context) {
        try {
            blackBoxLoader.attachBaseContext(context)
            blackBoxLoader.addLifecycleCallback()
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed in doAttachBaseContext", throwable)
        }
    }

    fun doOnCreate(context: Context) {
        try {
            blackBoxLoader.doOnCreate(context)
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed in doOnCreate", throwable)
        }
    }
}
