package io.github.edrock200.appdownloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.edrock200.appdownloader.BuildConfig
import io.github.edrock200.appdownloader.OpState
import io.github.edrock200.appdownloader.R
import io.github.edrock200.appdownloader.data.InstallMethod
import io.github.edrock200.appdownloader.data.SettingsState
import io.github.edrock200.appdownloader.install.ShizukuState

/** Presentation only; permission handling and all update actions stay in SiloraScreen. */
@Composable
internal fun SiloraDashboard(
    item: EntryUi?,
    checking: Boolean,
    updatingAutomatically: Boolean,
    settings: SettingsState,
    shizuku: ShizukuState,
    installAllowed: Boolean,
    notificationsEnabled: Boolean,
    canOpen: Boolean,
    isTv: Boolean,
    onInstall: () -> Unit,
    onCheck: () -> Unit,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    restoring: Boolean,
    onInstallPermission: () -> Unit,
    onNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val usingShizuku = settings.installMethod == InstallMethod.SHIZUKU
    BoxWithConstraints(modifier.fillMaxSize().background(colors.background).background(Brush.verticalGradient(
        listOf(colors.primaryContainer.copy(alpha = 0.32f), colors.background, colors.background),
    ))) {
        val wide = maxWidth >= 840.dp
        val compact = isTv && wide
        val separateHeaderActions = maxWidth < 480.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = if (isTv) 48.dp else 20.dp, vertical = if (isTv) 20.dp else 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 24.dp),
        ) {
            Column(Modifier.widthIn(max = 1120.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(painterResource(R.drawable.silora_mark), contentDescription = null, Modifier.size(38.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Silora", style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
                        Text("UPDATER", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                    if (!separateHeaderActions) SiloraHeaderActions(onHistory, onSettings)
                }
                if (separateHeaderActions) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SiloraHeaderActions(onHistory, onSettings)
                }
            }
            if (!compact) Column(
                Modifier.widthIn(max = 1120.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Your next chapter starts here.", style = MaterialTheme.typography.headlineLarge, color = colors.onBackground)
                Text("The latest Silora. The right build for your device.", color = colors.onSurfaceVariant)
            }
            val updateCard: @Composable (Modifier) -> Unit = { cardModifier ->
                SiloraUpdateCard(item, checking, updatingAutomatically, usingShizuku, shizuku, canOpen, isTv, compact,
                    onInstall, onCheck, onOpen, onCancel, restoring, cardModifier)
            }
            val preferencesCard: @Composable (Modifier) -> Unit = { cardModifier ->
                SiloraPreferencesCard(settings, shizuku, installAllowed, notificationsEnabled,
                    onSettings, onInstallPermission, onNotifications, compact, cardModifier)
            }
            if (wide) {
                Row(Modifier.widthIn(max = 1120.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    updateCard(Modifier.weight(1.35f))
                    preferencesCard(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    updateCard(Modifier.fillMaxWidth())
                    preferencesCard(Modifier.fillMaxWidth())
                }
            }
            Text("SILORA UPDATER  /  ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun SiloraHeaderActions(onHistory: () -> Unit, onSettings: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onHistory, modifier = Modifier.focusHighlight()) { Text("Past installs") }
        OutlinedButton(onClick = onSettings, modifier = Modifier.focusHighlight()) {
            Icon(Icons.Default.Settings, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Settings")
        }
    }
}

@Composable
private fun SiloraUpdateCard(
    item: EntryUi?, checking: Boolean, updatingAutomatically: Boolean,
    usingShizuku: Boolean, shizuku: ShizukuState, canOpen: Boolean, isTv: Boolean, compact: Boolean,
    onInstall: () -> Unit, onCheck: () -> Unit, onOpen: () -> Unit, onCancel: () -> Unit,
    restoring: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val op = item?.op
    val busy = restoring || (op != null && op !is OpState.Failed)
    val known = item?.entry?.latestVersionCode != null && item.entry.lastCheckError == null
    val error = op is OpState.Failed || item?.entry?.lastCheckError != null
    val current = item != null && item.installed != null && known && !item.hasUpdate && !item.waiting
    Card(modifier, shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.background(Brush.linearGradient(listOf(colors.primaryContainer.copy(alpha = 0.45f), colors.surface)))
            .padding(if (compact) 20.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(shape = MaterialTheme.shapes.medium, color = colors.background.copy(alpha = 0.75f)) {
                    Image(painterResource(R.drawable.silora_mark), contentDescription = null, Modifier.padding(6.dp).size(if (compact) 48.dp else 60.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Silora", style = MaterialTheme.typography.headlineMedium)
                    Text(if (compact) "TV edition" else if (isTv) "Fire TV · Android TV · Google TV" else "Android phones & tablets",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                if (compact) UpdateBadge(item, checking, busy, error, current)
            }
            if (!compact) UpdateBadge(item, checking, busy, error, current)
            if (item == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    VersionLabel("INSTALLED", item.installed?.versionName ?: if (item.installed != null) "Installed" else "Not installed", Modifier.weight(1f))
                    VersionLabel("LATEST RELEASE", item.entry.latestVersionName ?: "Not checked yet", Modifier.weight(1f))
                }
                if (!compact) HorizontalDivider(color = colors.outlineVariant)
                val status = when {
                    restoring -> "A past install is being restored. Follow the restore dialog to continue."
                    op == OpState.Resolving -> "Checking the latest Silora build…"
                    op is OpState.Downloading -> op.progress?.let { "Downloading Silora… ${(it * 100).toInt()}%" } ?: "Downloading Silora…"
                    op == OpState.Installing -> if (usingShizuku) "Installing Silora with Shizuku…" else "Confirm the install when your device asks."
                    op is OpState.Failed -> op.message
                    checking -> "Checking for updates…"
                    item.waiting -> if (usingShizuku && !shizuku.ready) "An update is downloaded. ${shizuku.help}"
                        else "An update is downloaded. Choose Install update to finish."
                    item.entry.lastCheckError != null -> item.entry.lastCheckError
                    item.hasUpdate -> "A new Silora build is ready. Update to keep watching at your best."
                    !known -> "Check for updates to get started."
                    item.installed == null -> "Install Silora and make yourself at home."
                    else -> "You have the latest Silora build for this device. Enjoy the show."
                }
                Text(status, color = if (error) colors.error else colors.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (busy || checking) {
                    val progress = (op as? OpState.Downloading)?.progress
                    if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                val openPrimary = canOpen && current
                val checkPrimary = !known && item.installed != null && !item.waiting
                val primaryAction: @Composable (Modifier) -> Unit = { actionModifier ->
                    Button(
                        onClick = { when { openPrimary -> onOpen(); checkPrimary -> onCheck(); else -> onInstall() } },
                        enabled = !busy && !checking && (openPrimary || checkPrimary || item.installed == null || item.hasUpdate || item.waiting),
                        modifier = actionModifier.heightIn(min = 52.dp).focusHighlight(scaleUp = false),
                    ) {
                        Text(when {
                            busy -> "Please wait…"
                            openPrimary -> "Open Silora"
                            checkPrimary -> "Check for updates"
                            item.installed == null -> "Install Silora"
                            item.waiting -> "Install update"
                            item.hasUpdate -> "Update Silora"
                            else -> "Up to date"
                        })
                        Spacer(Modifier.width(10.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, Modifier.size(18.dp))
                    }
                }
                val secondaryAction: @Composable (Modifier) -> Unit = { actionModifier ->
                    OutlinedButton(onClick = onCheck, enabled = !busy && !checking,
                        modifier = actionModifier.heightIn(min = 52.dp).focusHighlight(scaleUp = false)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Check for updates")
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (compact) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            primaryAction(Modifier.weight(1f))
                            if (!checkPrimary) secondaryAction(Modifier.weight(1f))
                        }
                    } else {
                        primaryAction(Modifier.fillMaxWidth())
                        if (!checkPrimary) secondaryAction(Modifier.fillMaxWidth())
                    }
                    if (canOpen && !openPrimary) {
                        TextButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().focusHighlight(scaleUp = false)) { Text("Open Silora") }
                    }
                    if (busy && !updatingAutomatically && !restoring) {
                        TextButton(onClick = onCancel, modifier = Modifier.focusHighlight()) {
                            Text(if (op == OpState.Installing) "Dismiss" else "Cancel download")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateBadge(item: EntryUi?, checking: Boolean, busy: Boolean, error: Boolean, current: Boolean) {
    val colors = MaterialTheme.colorScheme
    val known = item?.entry?.latestVersionCode != null && item.entry.lastCheckError == null
    val badgeColor = when { error -> colors.error; current && !busy && !checking -> colors.tertiary; else -> colors.primary }
    Surface(shape = CircleShape, color = badgeColor.copy(alpha = 0.12f), contentColor = badgeColor) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(when { error -> Icons.Default.Info; current && !busy && !checking -> Icons.Default.CheckCircle; else -> Icons.Default.Refresh },
                contentDescription = null, Modifier.size(16.dp))
            Text(when {
                busy -> "Update in progress"
                checking || item == null -> "Checking for updates"
                error -> "Needs attention"
                item.waiting -> "Ready to install"
                item.hasUpdate -> "Update available"
                current -> "You're up to date"
                known -> "Ready when you are"
                else -> "Let's get started"
            }, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun VersionLabel(label: String, version: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(version, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SiloraPreferencesCard(
    settings: SettingsState, shizuku: ShizukuState, installAllowed: Boolean, notificationsEnabled: Boolean,
    onSettings: () -> Unit, onInstallPermission: () -> Unit, onNotifications: () -> Unit, compact: Boolean, modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val usingShizuku = settings.installMethod == InstallMethod.SHIZUKU
    Card(modifier, shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = colors.surface), border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.padding(if (compact) 20.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp)) {
            Text("Make it effortless.", style = MaterialTheme.typography.titleLarge)
            if (!compact) Text("Your update preferences, at a glance.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            PreferenceSummary(Icons.Default.Refresh, "Background checks",
                if (settings.hasDailyCheck) "Daily at ${formatCheckHour(androidx.compose.ui.platform.LocalContext.current, settings.checkHour)}"
                else if (settings.checkIntervalHours == 0) "Off · check when opened" else "Every ${settings.checkIntervalHours} hours")
            HorizontalDivider(color = colors.outlineVariant)
            PreferenceSummary(Icons.Default.CheckCircle, "Automatic installation", if (settings.autoInstallUpdates) "On" else "Off")
            HorizontalDivider(color = colors.outlineVariant)
            PreferenceSummary(Icons.Default.Settings, "Installation method", settings.installMethod.label)
            if (usingShizuku) {
                Text("Shizuku: ${shizuku.label}", style = MaterialTheme.typography.titleSmall,
                    color = if (shizuku.ready) colors.tertiary else colors.onSurface)
                if (!shizuku.ready) {
                    Text(shizuku.help, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    OutlinedButton(onClick = onSettings, modifier = Modifier.focusHighlight()) { Text("Set up Shizuku") }
                }
            } else if (settings.autoInstallUpdates && !installAllowed) {
                Text("Allow installs so automatic updates can finish. Downloads can wait for your confirmation.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                OutlinedButton(onClick = onInstallPermission, modifier = Modifier.focusHighlight()) { Text("Allow installs") }
            }
            if (!notificationsEnabled && settings.checkIntervalHours > 0) {
                Text("Update notifications are off. Enable them to receive update alerts.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                OutlinedButton(onClick = onNotifications, modifier = Modifier.focusHighlight()) { Text("Enable notifications") }
            }
            Text(if (usingShizuku) "Shizuku must be started and Silora Updater authorized. Downloads stay ready if Shizuku stops."
                else "The first install may ask you to allow Silora Updater to install apps. Follow your device's install prompts.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun PreferenceSummary(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(10.dp).size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall)
        }
    }
}
