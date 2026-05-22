package com.ommidroid.example

enum class PermissionRecoveryTarget {
    AppDetails,
    AllFiles,
}

enum class InitializationStatus {
    Ready,
    Failed,
}

data class OmmiAppsUiState(
    val isLoading: Boolean = false,
    val isInstalling: Boolean = false,
    val isDownloading: Boolean = false,
    val apps: List<VirtualAppItem> = emptyList(),
    val selectedPackageName: String? = null,
    val message: String? = null,
    val pendingPermissionPackage: String? = null,
    val pendingPermissionUserId: Int? = null,
    val showPermissionRecoveryDialog: Boolean = false,
    val permissionRecoveryTarget: PermissionRecoveryTarget? = null,
    val installProgressMessage: String? = null,
    val downloadProgress: Int? = null,
    val initializationStatus: InitializationStatus = InitializationStatus.Ready,
    val initializationErrorMessage: String? = null,
)
