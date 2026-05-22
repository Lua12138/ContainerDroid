package com.ommidroid.example

import android.graphics.drawable.Drawable

private const val DEFAULT_USER_ID = 0

data class VirtualAppItem(
    val name: String,
    val packageName: String,
    val sourceDir: String,
    val icon: Drawable?,
    val userId: Int = DEFAULT_USER_ID,
    val isRunning: Boolean = false,
)
