package com.ommidroid.example

import android.content.Context
import android.net.Uri
import android.util.Patterns
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.entity.pm.InstallResult

class OmmiAppsViewModel(
    private val repository: OmmiAppsRepository,
    private val context: Context,
) : ViewModel() {
    private val userId = 0

    var uiState = mutableStateOf(OmmiAppsUiState())
        private set

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun isInitializationFailed(): Boolean {
        val initializationError = OmmiBlackBoxState.initializationErrorMessage
        if (initializationError != null) {
            onInitializationFailed(initializationError)
            return true
        }
        return uiState.value.initializationStatus == InitializationStatus.Failed
    }

    fun loadApps() {
        if (isInitializationFailed()) {
            return
        }
        viewModelScope.launch {
            uiState.value = uiState.value.copy(isLoading = true)
            repository.loadInstalledApps(userId)
                .onSuccess { apps ->
                    uiState.value = uiState.value.copy(isLoading = false, apps = apps)
                }
                .onFailure { throwable ->
                    uiState.value = uiState.value.copy(
                        isLoading = false,
                        message = throwable.message ?: string(R.string.unknown_error),
                    )
                }
        }
    }

    fun installApk(uri: Uri) {
        if (isInitializationFailed()) {
            return
        }
        viewModelScope.launch {
            uiState.value = uiState.value.copy(
                isInstalling = true,
                isDownloading = false,
                downloadProgress = null,
                installProgressMessage = string(R.string.install_apk),
            )
            delay(120)
            val installResult = withContext(Dispatchers.IO) {
                repository.installFromUri(uri, userId)
            }
            handleInstallResult(installResult, uri.lastPathSegment.orEmpty())
        }
    }

    fun downloadAndInstallApk(url: String) {
        if (isInitializationFailed()) {
            return
        }
        if (!Patterns.WEB_URL.matcher(url).matches()) {
            uiState.value = uiState.value.copy(message = string(R.string.invalid_url_message))
            return
        }
        viewModelScope.launch {
            uiState.value = uiState.value.copy(
                isDownloading = true,
                isInstalling = false,
                downloadProgress = null,
                installProgressMessage = string(R.string.download_unknown_progress_message),
            )
            val downloadResult = repository.downloadApk(url) { progress ->
                uiState.value = uiState.value.copy(
                    downloadProgress = progress,
                    installProgressMessage = progress?.let { string(R.string.download_progress_message, it) }
                        ?: string(R.string.download_unknown_progress_message),
                )
            }
            downloadResult
                .onSuccess { downloadedApk ->
                    uiState.value = uiState.value.copy(
                        isDownloading = false,
                        isInstalling = true,
                        downloadProgress = null,
                        installProgressMessage = string(R.string.install_apk),
                    )
                    delay(120)
                    val installResult = withContext(Dispatchers.IO) {
                        repository.installFromFile(downloadedApk.file, userId)
                    }
                    handleInstallResult(installResult, downloadedApk.suggestedName)
                }
                .onFailure { throwable ->
                    uiState.value = uiState.value.copy(
                        isDownloading = false,
                        isInstalling = false,
                        downloadProgress = null,
                        installProgressMessage = null,
                        message = string(
                            R.string.download_failed_message,
                            throwable.message ?: string(R.string.unknown_error),
                        ),
                    )
                }
        }
    }

    fun launchApp(packageName: String) {
        if (isInitializationFailed()) {
            return
        }
        if (!repository.hasLaunchPrerequisitePermission()) {
            onStoragePermissionRequired(packageName, userId)
            return
        }
        viewModelScope.launch {
            repository.launch(packageName, userId)
                .onSuccess { launched ->
                    if (!launched && uiState.value.pendingPermissionPackage == null) {
                        uiState.value = uiState.value.copy(message = string(R.string.launch_failed_message))
                    }
                }
                .onFailure { throwable ->
                    uiState.value = uiState.value.copy(
                        message = throwable.message ?: string(R.string.launch_failed_message),
                    )
                }
        }
    }

    fun performAction(packageName: String, action: OmmiAppAction) {
        if (isInitializationFailed()) {
            return
        }
        viewModelScope.launch {
            val result = when (action) {
                OmmiAppAction.Stop -> repository.stop(packageName, userId)
                OmmiAppAction.ClearData -> repository.clearData(packageName, userId)
                OmmiAppAction.Uninstall -> repository.uninstall(packageName, userId)
                OmmiAppAction.Launch -> repository.launch(packageName, userId).map { Unit }
            }
            result
                .onSuccess {
                    val message = when (action) {
                        OmmiAppAction.Stop -> string(R.string.stop_success_message)
                        OmmiAppAction.ClearData -> string(R.string.clear_success_message)
                        OmmiAppAction.Uninstall -> string(R.string.uninstall_success_message)
                        OmmiAppAction.Launch -> null
                    }
                    uiState.value = uiState.value.copy(message = message)
                    if (action != OmmiAppAction.Launch) {
                        loadApps()
                    }
                }
                .onFailure { throwable ->
                    uiState.value = uiState.value.copy(
                        message = throwable.message ?: string(R.string.action_failed_message),
                    )
                }
        }
    }

    fun onInitializationFailed(message: String) {
        uiState.value = uiState.value.copy(
            initializationStatus = InitializationStatus.Failed,
            initializationErrorMessage = message,
            isLoading = false,
            isInstalling = false,
            isDownloading = false,
            downloadProgress = null,
            installProgressMessage = null,
            message = message,
        )
    }

    fun onStoragePermissionRequired(packageName: String?, userId: Int) {
        uiState.value = uiState.value.copy(
            pendingPermissionPackage = packageName,
            pendingPermissionUserId = userId,
            showPermissionRecoveryDialog = false,
            permissionRecoveryTarget = null,
            message = string(R.string.storage_permission_required_message),
        )
    }

    fun onPermissionFlowHandled(
        granted: Boolean,
        recoveryTarget: PermissionRecoveryTarget? = null,
    ) {
        val packageName = uiState.value.pendingPermissionPackage
        uiState.value = uiState.value.copy(
            pendingPermissionPackage = if (granted) null else packageName,
            pendingPermissionUserId = if (granted) null else uiState.value.pendingPermissionUserId,
            showPermissionRecoveryDialog = !granted,
            permissionRecoveryTarget = recoveryTarget,
            message = if (granted) null else string(R.string.storage_permission_denied_message),
        )
        if (granted && !packageName.isNullOrBlank()) {
            launchApp(packageName)
        }
    }

    fun onPermissionSettingsOpened() {
        uiState.value = uiState.value.copy(
            showPermissionRecoveryDialog = false,
            permissionRecoveryTarget = null,
        )
    }

    fun dismissPermissionRecoveryDialog() {
        uiState.value = uiState.value.copy(
            showPermissionRecoveryDialog = false,
            permissionRecoveryTarget = null,
        )
    }

    fun selectPackage(packageName: String?) {
        uiState.value = uiState.value.copy(selectedPackageName = packageName)
    }

    fun consumeMessage() {
        uiState.value = uiState.value.copy(message = null)
    }

    private fun handleInstallResult(installResult: Result<InstallResult>, fallbackLabel: String) {
        installResult
            .onSuccess { result ->
                val message = if (result.success) {
                    string(
                        R.string.install_success_message,
                        result.packageName ?: fallbackLabel,
                    )
                } else {
                    string(
                        R.string.install_failed_message,
                        result.msg ?: string(R.string.unknown_error),
                    )
                }
                uiState.value = uiState.value.copy(
                    isInstalling = false,
                    isDownloading = false,
                    downloadProgress = null,
                    installProgressMessage = null,
                    message = message,
                )
                if (result.success) {
                    loadApps()
                }
            }
            .onFailure { throwable ->
                uiState.value = uiState.value.copy(
                    isInstalling = false,
                    isDownloading = false,
                    downloadProgress = null,
                    installProgressMessage = null,
                    message = string(
                        R.string.install_failed_message,
                        throwable.message ?: string(R.string.unknown_error),
                    ),
                )
            }
    }

    class Factory(
        private val repository: OmmiAppsRepository,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return OmmiAppsViewModel(repository, context.applicationContext) as T
        }
    }
}
