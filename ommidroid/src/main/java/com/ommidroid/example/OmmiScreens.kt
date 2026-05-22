package com.ommidroid.example

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmmiAppsRoute(
    viewModel: OmmiAppsViewModel,
    onOpenDetail: (String) -> Unit,
    onOpenPermissionSettings: () -> Unit,
) {
    val context = LocalContext.current
    val uiState = viewModel.uiState.value
    val snackbarHostState = remember { SnackbarHostState() }
    var showInstallMenu by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.installApk(uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadApps()
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.apps_title)) },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            Box {
                FloatingActionButton(
                    onClick = { showInstallMenu = true },
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(id = R.string.install_apk))
                }
                val localMimeTypes = remember {
                    arrayOf(
                        context.getString(R.string.apk_mime_type),
                        context.getString(R.string.binary_mime_type),
                    )
                }
                DropdownMenu(expanded = showInstallMenu, onDismissRequest = { showInstallMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(id = R.string.install_apk_local)) },
                        onClick = {
                            showInstallMenu = false
                            apkPicker.launch(localMimeTypes)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(id = R.string.install_apk_from_url)) },
                        onClick = {
                            showInstallMenu = false
                            showUrlDialog = true
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        when {
            uiState.initializationStatus == InitializationStatus.Failed -> InitializationFailedState(
                modifier = Modifier.padding(innerPadding),
                message = uiState.initializationErrorMessage
                    ?: stringResource(id = R.string.initialization_failed_default_message),
            )
            uiState.isLoading -> LoadingState(modifier = Modifier.padding(innerPadding))
            uiState.apps.isEmpty() -> EmptyState(modifier = Modifier.padding(innerPadding))
            else -> AppsList(
                apps = uiState.apps,
                modifier = Modifier.padding(innerPadding),
                onLaunch = viewModel::launchApp,
                onAction = { packageName, action ->
                    if (action == OmmiAppAction.Launch) {
                        viewModel.launchApp(packageName)
                    } else {
                        viewModel.performAction(packageName, action)
                    }
                },
                onOpenDetail = onOpenDetail,
            )
        }

        if (uiState.isInstalling || uiState.isDownloading) {
            InstallingOverlay(
                message = uiState.installProgressMessage ?: stringResource(id = R.string.installing_apk),
                progress = uiState.downloadProgress,
            )
        }

        if (uiState.showPermissionRecoveryDialog) {
            PermissionRecoveryDialog(
                onDismiss = viewModel::dismissPermissionRecoveryDialog,
                onOpenSettings = onOpenPermissionSettings,
            )
        }

        if (showUrlDialog) {
            NetworkUrlDialog(
                url = urlInput,
                onUrlChange = { urlInput = it },
                onDismiss = { showUrlDialog = false },
                onConfirm = {
                    showUrlDialog = false
                    viewModel.downloadAndInstallApk(urlInput)
                    urlInput = ""
                },
            )
        }
    }
}

@Composable
private fun NetworkUrlDialog(
    url: String,
    onUrlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = stringResource(id = R.string.confirm))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.cancel))
            }
        },
        title = {
            Text(text = stringResource(id = R.string.network_apk_url_title))
        },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                label = { Text(text = stringResource(id = R.string.network_apk_url_hint)) },
            )
        },
    )
}

@Composable
private fun InitializationFailedState(
    modifier: Modifier = Modifier,
    message: String,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = stringResource(id = R.string.initialization_failed_title), style = MaterialTheme.typography.titleLarge)
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = stringResource(id = R.string.empty_apps))
    }
}

@Composable
private fun InstallingOverlay(
    message: String,
    progress: Int?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (progress != null) {
                CircularProgressIndicator(progress = { progress / 100f })
            } else {
                CircularProgressIndicator()
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = message)
        }
    }
}

@Composable
private fun AppsList(
    apps: List<VirtualAppItem>,
    modifier: Modifier = Modifier,
    onLaunch: (String) -> Unit,
    onAction: (String, OmmiAppAction) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(apps, key = { it.packageName }) { app ->
            AppRow(app = app, onLaunch = onLaunch, onAction = onAction, onOpenDetail = onOpenDetail)
        }
    }
}

@Composable
private fun AppRow(
    app: VirtualAppItem,
    onLaunch: (String) -> Unit,
    onAction: (String, OmmiAppAction) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !app.isRunning) { onLaunch(app.packageName) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon = app.icon)
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(id = R.string.package_name, app.packageName),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = { onOpenDetail(app.packageName) }) {
            Icon(Icons.Default.Info, contentDescription = stringResource(id = R.string.detail_title))
        }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(id = R.string.action_more))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(id = R.string.launch_app)) },
                    leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    enabled = !app.isRunning,
                    onClick = {
                        expanded = false
                        onAction(app.packageName, OmmiAppAction.Launch)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(id = R.string.stop_app)) },
                    leadingIcon = { Icon(Icons.Default.Stop, contentDescription = null) },
                    enabled = app.isRunning,
                    onClick = {
                        expanded = false
                        onAction(app.packageName, OmmiAppAction.Stop)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(id = R.string.clear_app_data)) },
                    leadingIcon = { Icon(Icons.Default.Clear, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onAction(app.packageName, OmmiAppAction.ClearData)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(id = R.string.uninstall_app)) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onAction(app.packageName, OmmiAppAction.Uninstall)
                    },
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun PermissionRecoveryDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onOpenSettings) {
                Text(text = stringResource(id = R.string.go_to_settings))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.back))
            }
        },
        title = {
            Text(text = stringResource(id = R.string.permission_recovery_title))
        },
        text = {
            Text(text = stringResource(id = R.string.permission_recovery_message))
        },
    )
}

@Composable
private fun AppIcon(icon: Drawable?) {
    val bitmap = remember(icon) { icon?.toBitmap(width = 96, height = 96) }
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(bitmap.asImageBitmap()),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
        )
    } else {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = stringResource(id = R.string.default_app_icon_label), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmmiAppDetailRoute(
    packageName: String,
    viewModel: OmmiAppsViewModel,
    onBack: () -> Unit,
) {
    val app = viewModel.uiState.value.apps.firstOrNull { it.packageName == packageName }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(id = R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        if (app == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = stringResource(id = R.string.load_apps_failed, packageName))
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon(icon = app.icon)
            Text(text = app.name, style = MaterialTheme.typography.headlineSmall)
            Text(text = stringResource(id = R.string.package_name, app.packageName))
            Text(text = stringResource(id = R.string.source_path, app.sourceDir))
            Button(
                onClick = { viewModel.launchApp(app.packageName) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !app.isRunning,
            ) {
                Text(text = stringResource(id = R.string.launch_app))
            }
            Button(
                onClick = { viewModel.performAction(app.packageName, OmmiAppAction.Stop) },
                modifier = Modifier.fillMaxWidth(),
                enabled = app.isRunning,
            ) {
                Text(text = stringResource(id = R.string.stop_app))
            }
            Button(
                onClick = { viewModel.performAction(app.packageName, OmmiAppAction.ClearData) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(id = R.string.clear_app_data))
            }
            Button(
                onClick = { viewModel.performAction(app.packageName, OmmiAppAction.Uninstall) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(id = R.string.uninstall_app))
            }
            Text(
                text = stringResource(id = R.string.grant_storage_permission),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
