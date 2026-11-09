package io.github.edrock200.appdownloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.edrock200.appdownloader.BuildConfig
import io.github.edrock200.appdownloader.R
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.data.SettingsState
import io.github.edrock200.appdownloader.data.ThemeMode

@Composable
fun SiloraSettingsDialog(
    current: SettingsState,
    installAllowed: Boolean,
    notificationsEnabled: Boolean,
    onInterval: (Int) -> Unit,
    onAutomaticInstall: (Boolean) -> Unit,
    onDeleteAfterInstall: (Boolean) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onInstallPermission: () -> Unit,
    onNotifications: () -> Unit,
    onHistory: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(painterResource(R.drawable.silora_mark), contentDescription = null, modifier = Modifier.size(32.dp))
                Text("Settings")
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsSection("Check for Silora updates")
                Text("Checks also run when you open the updater. Background timing may vary with your device's power settings.",
                    style = MaterialTheme.typography.bodySmall)
                Settings.INTERVAL_OPTIONS.forEach { hours ->
                    PreferenceChoice(if (hours == 0) "When opened / manual only" else "Every $hours hours",
                        current.checkIntervalHours == hours) { onInterval(hours) }
                }
                HorizontalDivider()
                SettingsSection("App installation")
                Text(if (installAllowed) "Install permission: allowed" else "Install permission: needs setup",
                    style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = onInstallPermission, modifier = Modifier.focusHighlight()) { Text("Install permission settings") }
                HorizontalDivider()
                PreferenceSwitch("Install updates automatically", current.autoInstallUpdates,
                    enabled = current.checkIntervalHours > 0, onChange = onAutomaticInstall)
                Text(
                    if (current.checkIntervalHours == 0) "Choose a background check interval to enable automatic installation."
                    else "At each background check, download newer builds and install them when Android allows it. " +
                        "If confirmation is needed, the download waits for you. Silora must be installed once first.",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                Text(if (notificationsEnabled) "Update notifications: enabled" else "Update notifications: off",
                    style = MaterialTheme.typography.titleSmall)
                Text("Alerts tell you about available updates and downloads waiting for confirmation. " +
                    "Some TV launchers may not display alerts; pending updates are always shown in this app.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onNotifications, modifier = Modifier.focusHighlight()) {
                    Text(if (notificationsEnabled) "Notification settings" else "Enable notifications")
                }
                HorizontalDivider()
                SettingsSection("Appearance")
                Text("Silora violet, in light or dark. Follow your device or choose your favorite.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ThemeMode.entries.forEach { mode ->
                    PreferenceChoice(mode.label, current.themeMode == mode) { onTheme(mode) }
                }
                PreferenceSwitch("Delete APK after installing", current.deleteApkAfterInstall, onChange = onDeleteAfterInstall)
                Text("Downloaded updates waiting for confirmation are kept until you install them.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onHistory, modifier = Modifier.focusHighlight()) { Text("Update history") }
                Text("Silora Updater ${BuildConfig.VERSION_NAME} · Settings save immediately.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight()) { Text("Done") } },
    )
}

@Composable
private fun PreferenceChoice(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).focusHighlight(FocusShapes.Row, scaleUp = false)
        .selectable(selected = selected, enabled = enabled, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, enabled = enabled, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
        Text(label, Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    }
}

@Composable
private fun PreferenceSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).focusHighlight(FocusShapes.Row, scaleUp = false)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
