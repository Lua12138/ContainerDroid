package com.ommidroid.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: OmmiAppsViewModel

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTestIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            val repository = OmmiAppsRepository()
            viewModel = ViewModelProvider(
                this,
                OmmiAppsViewModel.Factory(repository, applicationContext),
            )[OmmiAppsViewModel::class.java]
            if (handleTestIntent(intent)) {
                return
            }
            setContent {
                OmmiTheme {
                    OmmiApp(viewModel = viewModel)
                }
            }
        } catch (throwable: Throwable) {
            Log.e(TAG, "Critical failure in MainActivity.onCreate", throwable)
            throw throwable
        }
    }

    private fun handleTestIntent(intent: Intent?): Boolean {
        if (!OmmiBlackBoxTestConfig.shouldRun(intent)) {
            return false
        }
        OmmiBlackBoxTestRunner.start(this, OmmiBlackBoxTestConfig.getTestPackage(intent))
        return true
    }

    private companion object {
        private const val TAG = "MainActivity"
    }
}

@Composable
private fun OmmiApp(viewModel: OmmiAppsViewModel) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val navController = rememberNavController()
    val allFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            OmmiStoragePermission.hasAllFilesAccess()
        } else {
            true
        }
        viewModel.onPermissionFlowHandled(granted)
    }
    val appDetailsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val hasReadPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            OmmiStoragePermission.hasLegacyStoragePermission(appContext)
        } else {
            true
        }
        viewModel.onPermissionFlowHandled(granted = hasReadPermission)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val legacyGranted = if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M until Build.VERSION_CODES.TIRAMISU) {
            result[android.Manifest.permission.READ_EXTERNAL_STORAGE] == true
        } else {
            false
        }
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (OmmiStoragePermission.hasAllFilesAccess()) {
                    viewModel.onPermissionFlowHandled(true)
                } else {
                    viewModel.onPermissionFlowHandled(
                        granted = false,
                        recoveryTarget = PermissionRecoveryTarget.AllFiles,
                    )
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                viewModel.onPermissionFlowHandled(legacyGranted)
            }
            else -> {
                viewModel.onPermissionFlowHandled(true)
            }
        }
    }

    LaunchedEffect(Unit) {
        OmmiBlackBoxState.initializationErrorMessage?.let { errorMessage ->
            viewModel.onInitializationFailed(errorMessage)
        }
        val pendingRequest = OmmiPermissionRequestStore.read(appContext)
        if (pendingRequest != null) {
            viewModel.onStoragePermissionRequired(pendingRequest.packageName, pendingRequest.userId)
            OmmiPermissionRequestStore.clear(appContext)
        }
    }

    LaunchedEffect(viewModel.uiState.value.pendingPermissionPackage, viewModel.uiState.value.pendingPermissionUserId) {
        if (viewModel.uiState.value.pendingPermissionPackage == null) {
            return@LaunchedEffect
        }
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                allFilesLauncher.launch(createAllFilesAccessIntent(context))
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                permissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    )
                )
            }
            else -> {
                viewModel.onPermissionFlowHandled(true)
            }
        }
    }

    LaunchedEffect(viewModel.uiState.value.permissionRecoveryTarget) {
        when (viewModel.uiState.value.permissionRecoveryTarget) {
            PermissionRecoveryTarget.AllFiles -> {
                viewModel.onPermissionSettingsOpened()
                allFilesLauncher.launch(createAllFilesAccessIntent(context))
            }
            PermissionRecoveryTarget.AppDetails -> {
                viewModel.onPermissionSettingsOpened()
                appDetailsLauncher.launch(createAppDetailsIntent(context))
            }
            null -> Unit
        }
    }

    NavHost(navController = navController, startDestination = "apps") {
        composable("apps") {
            OmmiAppsRoute(
                viewModel = viewModel,
                onOpenDetail = { packageName -> navController.navigate("detail/$packageName") },
                onOpenPermissionSettings = {
                    when (viewModel.uiState.value.permissionRecoveryTarget) {
                        PermissionRecoveryTarget.AllFiles -> allFilesLauncher.launch(createAllFilesAccessIntent(context))
                        PermissionRecoveryTarget.AppDetails -> appDetailsLauncher.launch(createAppDetailsIntent(context))
                        null -> appDetailsLauncher.launch(createAppDetailsIntent(context))
                    }
                },
            )
        }
        composable(
            route = "detail/{packageName}",
            arguments = listOf(navArgument("packageName") { type = NavType.StringType }),
        ) { backStackEntry ->
            val packageName = backStackEntry.arguments?.getString("packageName").orEmpty()
            OmmiAppDetailRoute(
                packageName = packageName,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

private fun createAllFilesAccessIntent(context: Context): Intent {
    val packageIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
        data = "package:${context.packageName}".toUri()
    }
    return if (packageIntent.resolveActivity(context.packageManager) != null) {
        packageIntent
    } else {
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            .takeIf { it.resolveActivity(context.packageManager) != null }
            ?: createAppDetailsIntent(context)
    }
}

private fun createAppDetailsIntent(context: Context): Intent {
    return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = "package:${context.packageName}".toUri()
    }
}
