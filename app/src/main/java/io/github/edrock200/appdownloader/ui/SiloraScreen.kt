package io.github.edrock200.appdownloader.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.edrock200.appdownloader.data.FirstRunStep
import io.github.edrock200.appdownloader.data.InstallMethod
import io.github.edrock200.appdownloader.data.Silora
import io.github.edrock200.appdownloader.work.Notifications
import kotlinx.coroutines.launch

/** A fixed, remote-friendly front door with an explicitly confirmed past-install restore. */
@Composable
fun SiloraScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val items by vm.items.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val updatingAutomatically by vm.updatingAll.collectAsStateWithLifecycle()
    val restore by vm.installRestore.collectAsStateWithLifecycle()
    val selfUpdate by vm.selfUpdate.collectAsStateWithLifecycle()
    val ops by vm.ops.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val item = items.firstOrNull { it.entry.id == Silora.ID }
    val snackbar = remember { SnackbarHostState() }
    val pendingInstallAfterPermission = remember { vm.pendingInstallAfterPermission }
    var firstRunStep by remember { mutableStateOf(vm.firstRunStep) }
    val firstRunActive = firstRunStep != FirstRunStep.COMPLETE
    var permissionPrompt by rememberSaveable { mutableStateOf(pendingInstallAfterPermission != null) }
    val firstRunInstall = firstRunStep == FirstRunStep.INSTALL_PERMISSION && !permissionPrompt
    val showInstallPermission = permissionPrompt || firstRunInstall
    var allowed by remember { mutableStateOf(SystemActions.canInstallPackages(context)) }
    val isTv = rememberIsTv()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val shizuku by vm.shizukuStatus.collectAsStateWithLifecycle()
    val usingShizuku = settings.installMethod == InstallMethod.SHIZUKU
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showShizukuHelp by rememberSaveable { mutableStateOf(false) }
    var showWaiting by rememberSaveable { mutableStateOf(false) }
    var requestingNotifications by rememberSaveable { mutableStateOf(false) }
    var installAfterPermission by rememberSaveable { mutableStateOf(pendingInstallAfterPermission ?: true) }
    var notificationsEnabled by remember { mutableStateOf(Notifications.areEnabled(context)) }

    fun finishFirstRun() {
        vm.markNotificationPromptSeen()
        vm.saveFirstRunStep(FirstRunStep.COMPLETE)
        firstRunStep = FirstRunStep.COMPLETE
    }

    fun offerNotifications() {
        if (Notifications.areEnabled(context)) finishFirstRun()
        else {
            vm.saveFirstRunStep(FirstRunStep.NOTIFICATIONS)
            firstRunStep = FirstRunStep.NOTIFICATIONS
        }
    }

    fun startPermissionSetup() {
        if (usingShizuku || SystemActions.canInstallPackages(context)) offerNotifications()
        else {
            vm.saveFirstRunStep(FirstRunStep.INSTALL_PERMISSION)
            firstRunStep = FirstRunStep.INSTALL_PERMISSION
        }
    }
    val uninstallForRestore = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refreshInstalled()
        vm.installAfterRestoreUninstall()
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requestingNotifications = false
        notificationsEnabled = Notifications.areEnabled(context)
        if (granted) vm.notificationPermissionGranted()
    }
    fun notificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !vm.notificationPermissionAsked) {
            vm.markNotificationPermissionAsked()
            requestingNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!SystemActions.openNotificationSettings(context)) {
            vm.message("Open your device's Settings to enable notifications for Silora Updater.")
        }
    }
    LaunchedEffect(firstRunStep, notificationsEnabled) {
        if (firstRunStep == FirstRunStep.NOTIFICATIONS && notificationsEnabled) finishFirstRun()
    }
    LaunchedEffect(firstRunActive, requestingNotifications, item?.waiting, item?.op) {
        if (!firstRunActive && !requestingNotifications && item?.waiting == true && item.op == null && !vm.waitingPromptShown) {
            vm.waitingPromptShown = true
            showWaiting = true
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshShizuku()
        vm.refreshInstallRestore()
        allowed = SystemActions.canInstallPackages(context)
        val enabled = Notifications.areEnabled(context)
        if (enabled && !notificationsEnabled) vm.notificationPermissionGranted()
        notificationsEnabled = enabled
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    fun install() {
        if (usingShizuku) {
            val state = vm.refreshShizuku()
            if (state.ready) vm.install(Silora.ID)
            else { showSettings = true; vm.message(state.help) }
        } else if (SystemActions.canInstallPackages(context)) vm.install(Silora.ID)
        else {
            installAfterPermission = true
            permissionPrompt = true
        }
    }

    fun dismissInstallPermission() {
        vm.saveInstallPermissionRequest(null)
        permissionPrompt = false
        if (firstRunInstall) offerNotifications()
    }

    fun openInstallSettings() {
        // First-run setup has its own durable step and must never resume an install.
        if (!firstRunInstall) vm.saveInstallPermissionRequest(installAfterPermission)
        if (!SystemActions.openInstallPermissionSettings(context)) {
            vm.saveInstallPermissionRequest(null)
            vm.message("Open your device's Settings and allow installs for Silora Updater.")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        SiloraDashboard(
            item = item, checking = checking, updatingAutomatically = updatingAutomatically,
            settings = settings, shizuku = shizuku, installAllowed = allowed,
            notificationsEnabled = notificationsEnabled,
            canOpen = item?.installed != null && SystemActions.canLaunch(context, Silora.PACKAGE),
            isTv = isTv,
            onInstall = ::install, onCheck = vm::checkAll,
            onOpen = { SystemActions.launchApp(context, Silora.PACKAGE) },
            onCancel = { vm.clearOp(Silora.ID) },
            onSettings = { showSettings = true },
            onHistory = { showHistory = true }, restoring = restore.active,
            onInstallPermission = { installAfterPermission = false; permissionPrompt = true },
            onNotifications = ::notificationSettings,
            modifier = Modifier.padding(padding),
        )
    }

    if (!firstRunActive && !requestingNotifications && !showSettings && !showInstallPermission && !showShizukuHelp && !restore.active && !showWaiting) {
        selfUpdate?.let { update ->
            SelfUpdateDialog(update, ops[io.github.edrock200.appdownloader.AppController.SELF_UPDATE_ID],
                onUpdate = {
                    if (usingShizuku && !vm.refreshShizuku().ready) showSettings = true
                    else if (!usingShizuku && !SystemActions.canInstallPackages(context)) {
                        // Set up access only; return to this prompt for an explicit updater install.
                        installAfterPermission = false
                        permissionPrompt = true
                    } else vm.installSelfUpdate()
                }, onLater = vm::dismissSelfUpdate)
        }
    }

    if (firstRunStep == FirstRunStep.WELCOME && !permissionPrompt && !restore.active) {
        AlertDialog(
            onDismissRequest = ::finishFirstRun,
            title = { Text("Welcome to Silora Updater") },
            text = { Text("Install Silora and keep it up to date.\n\n" +
                "First, we'll help you allow app installs and choose whether to receive update notifications. " +
                "You can change these permissions later in Settings.") },
            confirmButton = {
                TextButton(onClick = ::startPermissionSetup, modifier = Modifier.focusHighlight()) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = ::finishFirstRun, modifier = Modifier.focusHighlight()) { Text("Not now") }
            },
        )
    }
    if (showInstallPermission) {
        AlertDialog(
            onDismissRequest = ::dismissInstallPermission,
            title = { Text("Allow Silora installs") },
            text = { Text("Enable installs for Silora Updater, then return here and choose Continue.\n\n" + Device.installPermissionHelp(context)) },
            confirmButton = {
                TextButton(modifier = Modifier.focusHighlight(), onClick = {
                    if (usingShizuku || SystemActions.canInstallPackages(context)) {
                        val continueInstall = !firstRunInstall && installAfterPermission
                        dismissInstallPermission()
                        if (continueInstall) install()
                    } else openInstallSettings()
                }) { Text(if (allowed) "Continue" else "Open settings") }
            },
            dismissButton = {
                TextButton(onClick = ::dismissInstallPermission, modifier = Modifier.focusHighlight()) {
                    Text(if (firstRunInstall) "Not now" else "Cancel")
                }
            },
        )
    }
    if (showSettings) {
        SiloraSettingsDialog(
            current = settings, installAllowed = allowed, notificationsEnabled = notificationsEnabled,
            shizuku = shizuku, onInstallMethod = vm::setInstallMethod,
            onAuthorizeShizuku = vm::authorizeShizuku,
            onOpenShizuku = {
                if (!SystemActions.openShizuku(context)) { showSettings = false; showShizukuHelp = true }
            },
            onShizukuHelp = { showSettings = false; showShizukuHelp = true },
            onInterval = vm::setCheckInterval, onDailyCheck = vm::setDailyCheck,
            onAutomaticInstall = { enabled ->
                vm.setAutomaticInstallation(enabled)
                if (enabled && !usingShizuku && !allowed) {
                    showSettings = false
                    installAfterPermission = false
                    permissionPrompt = true
                }
            },
            onDeleteAfterInstall = vm::setDeleteAfterInstall, onTheme = vm::setTheme,
            onWaitUntilPlaybackStops = vm::setWaitUntilPlaybackStops,
            onInstallPermission = {
                showSettings = false
                installAfterPermission = false
                permissionPrompt = true
                openInstallSettings()
            },
            onNotifications = ::notificationSettings,
            onHistory = { showSettings = false; showHistory = true },
            onCheckSelfUpdate = { showSettings = false; vm.checkSelfUpdateNow() },
            onDismiss = { showSettings = false },
        )
    }
    if (showHistory && !restore.active) item?.entry?.let {
        HistoryDialog(it, onDismiss = { showHistory = false },
            onRestore = { record -> showHistory = false; vm.prepareInstallRestore(record) },
            hasSavedApk = vm::hasSavedApk, installedVersionCode = item.installed?.versionCode,
            restoreEnabled = !updatingAutomatically && (item.op == null || item.op is io.github.edrock200.appdownloader.OpState.Failed))
    }
    if ((restore.active || restore.error != null) && item != null && !showSettings && !showInstallPermission && !showShizukuHelp) {
        InstallRestoreDialog(restore, installed = item.installed != null,
            automaticUpdates = settings.autoInstallUpdates,
            onConfirm = {
                scope.launch {
                    vm.refreshInstalled()
                    val uninstall = vm.confirmInstallRestore(item.installed?.versionCode) ?: return@launch
                    if (uninstall) {
                        try {
                            @Suppress("DEPRECATION")
                            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${Silora.PACKAGE}"))
                                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                            uninstallForRestore.launch(intent)
                        } catch (_: Exception) {
                            vm.restoreError("Could not open Android's uninstall confirmation. Nothing was uninstalled.")
                        }
                    } else vm.installAfterRestoreUninstall()
                }
            }, onCancel = vm::cancelInstallRestore, onSettings = { showSettings = true })
    }
    if (!firstRunActive && !requestingNotifications && showWaiting && item?.waiting == true && !restore.active) {
        AlertDialog(onDismissRequest = { showWaiting = false }, title = { Text("Silora update ready") },
            text = { Text(if (usingShizuku) "A background update is downloaded. ${shizuku.help} Choose Install update when ready."
                else "A background update was downloaded and needs your confirmation to install.") },
            confirmButton = { TextButton(onClick = { showWaiting = false; install() }, modifier = Modifier.focusHighlight()) { Text("Install update") } },
            dismissButton = { TextButton(onClick = { showWaiting = false }, modifier = Modifier.focusHighlight()) { Text("Later") } },
        )
    }
    if (showShizukuHelp) {
        AlertDialog(onDismissRequest = { showShizukuHelp = false }, title = { Text("Set up Shizuku") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1. Install Shizuku from shizuku.rikka.app/download.\n\n" +
                    "2. Open Shizuku and follow its instructions to start it with wireless debugging or ADB. TV devices may need ADB from another device.\n\n" +
                    "3. Return here, choose Shizuku in Settings and select Authorize Shizuku.\n\n" +
                    "Shizuku normally needs starting again after a reboot on non-rooted devices.")
                Text("Full guide: https://shizuku.rikka.app/guide/setup/", style = MaterialTheme.typography.bodySmall)
                QrCode("https://shizuku.rikka.app/guide/setup/", Modifier.widthIn(max = 160.dp).fillMaxWidth().align(Alignment.CenterHorizontally))
            } },
            confirmButton = { TextButton(onClick = { showShizukuHelp = false; showSettings = true }, modifier = Modifier.focusHighlight()) { Text("Back to settings") } },
            dismissButton = { TextButton(onClick = {
                if (!SystemActions.openUrl(context, "https://shizuku.rikka.app/guide/setup/")) vm.message("Open shizuku.rikka.app/guide/setup on your phone or computer.")
            }, modifier = Modifier.focusHighlight()) { Text("Open guide") } },
        )
    }
    if (firstRunStep == FirstRunStep.NOTIFICATIONS && !permissionPrompt && !restore.active) {
        AlertDialog(onDismissRequest = ::finishFirstRun, title = { Text("Get Silora update alerts") },
            text = { Text("Allow notifications so Silora Updater can tell you when an update is available or needs your confirmation.\n\n" +
                "Notifications are optional. You can enable them later in Settings.") },
            confirmButton = { TextButton(onClick = { finishFirstRun(); notificationSettings() }, modifier = Modifier.focusHighlight()) { Text("Enable notifications") } },
            dismissButton = { TextButton(onClick = ::finishFirstRun, modifier = Modifier.focusHighlight()) { Text("Not now") } },
        )
    }

}
