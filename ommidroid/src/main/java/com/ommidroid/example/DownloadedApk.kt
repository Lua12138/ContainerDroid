package com.ommidroid.example

import java.io.File

data class DownloadedApk(
    val file: File,
    val suggestedName: String,
)
