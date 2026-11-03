package io.github.edrock200.appdownloader.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.edrock200.appdownloader.AppController
import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.SourceType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val items by vm.items.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sharedText by vm.sharedText.collectAsStateWithLifecycle()
    val selfUpdate by vm.selfUpdate.collectAsStateWithLifecycle()
    val ops by vm.ops.collectAsStateWithLifecycle()
    val isTv = rememberIsTv()
    val snackbar = remember { SnackbarHostState() }

    var showAdd by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AppEntry?>(null) }
    var deleting by remember { mutableStateOf<AppEntry?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var pendingInstallId by rememberSaveable { mutableStateOf<String?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(sharedText) {
        if (sharedText != null) showAdd = true
    }

    fun startInstall(id: String) {
        if (id == AppController.SELF_UPDATE_ID) vm.installSelfUpdate() else vm.install(id)
    }

    fun install(id: String) {
        if (SystemActions.canInstallPackages(context)) startInstall(id) else pendingInstallId = id
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Downloader") },
                actions = {
                    if (items.any { it.entry.type == SourceType.GITHUB }) {
                        if (checking) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            IconButton(onClick = vm::checkAll, modifier = Modifier.focusHighlight(FocusShapes.Icon)) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Check for updates")
                            }
                        }
                    }
                    IconButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.focusHighlight(FocusShapes.Icon),
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            if (items.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showAdd = true },
                    modifier = Modifier.focusHighlight(FocusShapes.Fab),
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyState(Modifier.padding(padding), onAdd = { showAdd = true })
        } else {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                    // TVs need extra margin: edges of the picture can be cut off (overscan).
                    contentPadding = if (isTv) {
                        PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 120.dp)
                    } else {
                        PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)
                    },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items, key = { it.entry.id }) { item ->
                        EntryCard(
                            item = item,
                            onInstall = { install(item.entry.id) },
                            onCancel = { vm.clearOp(item.entry.id) },
                            onCheck = { vm.checkOne(item.entry.id) },
                            onEdit = { editing = item.entry },
                            onDelete = { deleting = item.entry },
                            onMessage = vm::message,
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        EntryDialog(
            original = null,
            prefill = sharedText,
            onDismiss = {
                showAdd = false
                vm.sharedText.value = null
            },
            onSubmit = { form, installNow ->
                val canInstall = SystemActions.canInstallPackages(context)
                when (val result = vm.add(form, installNow && canInstall)) {
                    is MainViewModel.AddResult.Invalid -> result.message
                    is MainViewModel.AddResult.Added -> {
                        showAdd = false
                        vm.sharedText.value = null
                        if (installNow && !canInstall) pendingInstallId = result.id
                        if (result.type == SourceType.GITHUB && settings.checkIntervalHours > 0) askForNotifications()
                        null
                    }
                }
            },
        )
    }

    editing?.let { entry ->
        EntryDialog(
            original = entry,
            prefill = null,
            onDismiss = { editing = null },
            onSubmit = { form, _ ->
                vm.edit(entry, form).also { if (it == null) editing = null }
            },
        )
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${entry.name}?") },
            text = { Text("This removes it from your list. The installed app (if any) is not uninstalled.") },
            confirmButton = {
                TextButton(modifier = Modifier.focusHighlight(), onClick = {
                    vm.delete(entry.id)
                    deleting = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(modifier = Modifier.focusHighlight(), onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    pendingInstallId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingInstallId = null },
            title = { Text("Allow app installs") },
            text = {
                Text(
                    "To install APKs, allow App Downloader to install unknown apps.\n\n" +
                        Device.installPermissionHelp(context)
                )
            },
            confirmButton = {
                TextButton(modifier = Modifier.focusHighlight(), onClick = {
                    if (SystemActions.canInstallPackages(context)) {
                        pendingInstallId = null
                        startInstall(id)
                    } else if (!SystemActions.openInstallPermissionSettings(context)) {
                        vm.message("Could not open settings")
                    }
                }) { Text(if (SystemActions.canInstallPackages(context)) "Continue" else "Open settings") }
            },
            dismissButton = {
                TextButton(modifier = Modifier.focusHighlight(), onClick = {
                    pendingInstallId = null
                    startInstall(id) // The system may still prompt for permission itself.
                }) { Text("Try anyway") }
            },
        )
    }

    selfUpdate?.let { update ->
        SelfUpdateDialog(
            update = update,
            op = ops[AppController.SELF_UPDATE_ID],
            onUpdate = { install(AppController.SELF_UPDATE_ID) },
            onLater = vm::dismissSelfUpdate,
        )
    }

    if (showSettings) {
        SettingsDialog(
            current = settings,
            onCheckSelfUpdate = vm::checkSelfUpdateNow,
            onDismiss = { showSettings = false },
            onSave = {
                vm.saveSettings(it)
                showSettings = false
                if (it.checkIntervalHours > 0 && items.any { i -> i.entry.type == SourceType.GITHUB }) {
                    askForNotifications()
                }
            },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onAdd: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No apps yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "Add a direct APK link, an aftv.news code (like 12345), or a GitHub repository " +
                "(like github.com/owner/repo) to download, install and keep apps up to date.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, modifier = Modifier.focusHighlight()) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Add an app")
        }
    }
}
