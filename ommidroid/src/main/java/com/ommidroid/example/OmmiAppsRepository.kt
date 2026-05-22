package com.ommidroid.example

import android.content.pm.ApplicationInfo
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class OmmiAppsRepository {
    fun loadInstalledApps(userId: Int): Result<List<VirtualAppItem>> = runCatching {
        val runningPackages = linkedSetOf<String>()
        val callerPackage = OmmiApplication.getContext().packageName

        runCatching {
            BlackBoxCore.getBActivityManager().getRunningAppProcesses(callerPackage, userId)
        }.getOrNull()?.mAppProcessInfoList.orEmpty().forEach { process ->
            process.pkgList?.forEach { packageName ->
                if (!packageName.isNullOrBlank()) {
                    runningPackages += packageName
                }
            }
        }

        runCatching {
            BlackBoxCore.getBActivityManager().getRunningServices(callerPackage, userId)
        }.getOrNull()?.mRunningServiceInfoList.orEmpty().forEach { service ->
            service.service?.packageName?.takeIf { it.isNotBlank() }?.let(runningPackages::add)
        }

        BlackBoxCore.get()
            .getInstalledApplications(0, userId)
            .orEmpty()
            .map { it.toVirtualAppItem(runningPackages) }
            .sortedBy { it.name.lowercase() }
    }

    fun installFromUri(uri: Uri, userId: Int) = runCatching {
        BlackBoxCore.get().installPackageAsUser(uri, userId)
    }

    fun installFromFile(file: File, userId: Int) = runCatching {
        BlackBoxCore.get().installPackageAsUser(file, userId)
    }

    suspend fun downloadApk(
        url: String,
        onProgress: (Int?) -> Unit,
    ): Result<DownloadedApk> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = URL(url)
            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                doInput = true
            }
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }

            val fileName = extractFileName(connection, endpoint)
            val outputFile = File(OmmiApplication.getContext().cacheDir, fileName)
            val totalLength = connection.contentLength.takeIf { it > 0 }

            BufferedInputStream(connection.inputStream).use { inputStream ->
                FileOutputStream(outputFile).use { outputStream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    var bytesRead = inputStream.read(buffer)
                    while (bytesRead >= 0) {
                        outputStream.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        onProgress(totalLength?.let { ((downloaded * 100) / it).toInt().coerceIn(0, 100) })
                        bytesRead = inputStream.read(buffer)
                    }
                    outputStream.flush()
                }
            }
            connection.disconnect()
            DownloadedApk(outputFile, fileName)
        }
    }

    fun launch(packageName: String, userId: Int): Result<Boolean> = runCatching {
        BlackBoxCore.get().launchApk(packageName, userId)
    }

    fun hasLaunchPrerequisitePermission(): Boolean {
        return true
    }

    fun uninstall(packageName: String, userId: Int): Result<Unit> = runCatching {
        BlackBoxCore.get().uninstallPackageAsUser(packageName, userId)
    }

    fun clearData(packageName: String, userId: Int): Result<Unit> = runCatching {
        BlackBoxCore.get().clearPackage(packageName, userId)
    }

    fun stop(packageName: String, userId: Int): Result<Unit> = runCatching {
        BlackBoxCore.get().stopPackage(packageName, userId)
    }

    private fun ApplicationInfo.toVirtualAppItem(runningPackages: Set<String>): VirtualAppItem {
        val packageManager = BlackBoxCore.getPackageManager()
        val appName = runCatching {
            packageManager.getApplicationLabel(this).toString()
        }.getOrDefault(packageName)
        val appIcon = runCatching {
            packageManager.getApplicationIcon(this)
        }.getOrNull()
        return VirtualAppItem(
            name = appName,
            packageName = packageName,
            sourceDir = sourceDir.orEmpty(),
            icon = appIcon,
            isRunning = runningPackages.contains(packageName),
        )
    }

    private fun extractFileName(connection: HttpURLConnection, endpoint: URL): String {
        val disposition = connection.getHeaderField("Content-Disposition")
        val dispositionName = disposition
            ?.substringAfter("filename=", "")
            ?.trim('"', '\'', ' ')
            ?.takeIf { it.isNotBlank() }
        val pathName = endpoint.path.substringAfterLast('/').takeIf { it.isNotBlank() }
        val rawName = dispositionName ?: pathName ?: "downloaded.apk"
        return if (rawName.endsWith(".apk", ignoreCase = true)) rawName else "$rawName.apk"
    }
}
