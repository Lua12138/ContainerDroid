package com.ommidroid.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StoragePermissionRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != OmmiBlackBoxLoader.ACTION_REQUEST_STORAGE_PERMISSION) {
            return
        }
        OmmiPermissionRequestStore.save(
            context = context.applicationContext,
            packageName = intent.getStringExtra(OmmiBlackBoxLoader.EXTRA_PACKAGE_NAME),
            userId = intent.getIntExtra(OmmiBlackBoxLoader.EXTRA_USER_ID, 0),
        )
    }
}
