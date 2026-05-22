package com.ommidroid.example

import android.content.Context
import androidx.core.content.edit

object OmmiPermissionRequestStore {
    private const val PREFS_NAME = "ommi_permission_request_store"
    private const val KEY_PACKAGE = "pending_package_name"
    private const val KEY_USER_ID = "pending_user_id"

    fun save(context: Context, packageName: String?, userId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_PACKAGE, packageName)
                putInt(KEY_USER_ID, userId)
            }
    }

    fun read(context: Context): PendingPermissionRequest? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val packageName = prefs.getString(KEY_PACKAGE, null) ?: return null
        val userId = prefs.getInt(KEY_USER_ID, 0)
        return PendingPermissionRequest(packageName, userId)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                remove(KEY_PACKAGE)
                remove(KEY_USER_ID)
            }
    }
}

data class PendingPermissionRequest(
    val packageName: String,
    val userId: Int,
)
