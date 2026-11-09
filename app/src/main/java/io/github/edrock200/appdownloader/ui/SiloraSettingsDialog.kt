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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.edrock200.appdownloader.BuildConfig
import io.github.edrock200.appdownloader.R
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.data.SettingsState
import io.github.edrock200.appdownloader.data.ThemeMode
import io.github.edrock200.appdownloader.data.InstallMethod
import io.github.edrock200.appdownloader.install.ShizukuState
import android.content.Context
import java.util.Date

@Composable
fun SiloraSettingsDialog(
    current: SettingsState,
    installAllowed: Boolean,
    notificationsEnabled: Boolean,
    shizuku: ShizukuState,
    onInstallMethod: (InstallMethod) -> Unit,
    onAuthorizeShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onShizukuHelp: () -> Unit,
    onInterval: (Int) -> Unit,
    onDailyCheck: (Int) -> Unit,
    onAutomaticInstall: (Boolean) -> Unit,
    onDeleteAfterInstall: (Boolean) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onInstallPermission: () -> Unit,
    onNotifications: () -> Unit,
    onHistory: () -> Unit,
    onCheckSelfUpdate: () -> Unit,
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
                SettingsSection("Check for Silora and updater updates")
                Text("Checks for both Silora and Silora Updater also run when you open the updater. Background timing may vary with your device's power settings.",
                    style = MaterialTheme.typography.bodySmall)
                Settings.INTERVAL_OPTIONS.forEach { hours ->
                    PreferenceChoice(if (hours == 0) "When opened / manual only" else "Every $hours hours",
                        !current.hasDailyCheck && current.checkIntervalHours == hours) { onInterval(hours) }
                }
                PreferenceChoice("Custom", current.hasDailyCheck) { onDailyCheck(current.checkHour) }
                if (current.hasDailyCheck) {
                    Text("Daily check time", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { onDailyCheck((current.checkHour + 23) % 24) },
                            modifier = Modifier.focusHighlight().semantics { contentDescription = "One hour earlier" }) {
                            Text("−")
                        }
                        Text(formatCheckHour(LocalContext.current, current.checkHour),
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = { onDailyCheck((current.checkHour + 1) % 24) },
                            modifier = Modifier.focusHighlight().semantics { contentDescription = "One hour later" }) {
                            Text("+")
                        }
                    }
                    Text("Checks once a day at this time in your device's local time zone. " +
                        "If the device is asleep or offline, the check may happen later.",
                        style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                SettingsSection("App installation method")
                InstallMethod.entries.forEach { method ->
                    PreferenceChoice(method.label, current.installMethod == method,
                        enabled = method != InstallMethod.SHIZUKU || shizuku != ShizukuState.UNSUPPORTED) { onInstallMethod(method) }
                }
                if (current.installMethod == InstallMethod.SHIZUKU) {
                    Text("Shizuku: ${shizuku.label}", style = MaterialTheme.typography.titleSmall)
                    Text(shizuku.help, style = MaterialTheme.typography.bodySmall)
                    if (shizuku == ShizukuState.PERMISSION_REQUIRED) {
                        OutlinedButton(onClick = onAuthorizeShizuku, modifier = Modifier.focusHighlight()) { Text("Authorize Shizuku") }
                    }
                    if (shizuku != ShizukuState.NOT_INSTALLED && shizuku != ShizukuState.UNSUPPORTED) {
                        OutlinedButton(onClick = onOpenShizuku, modifier = Modifier.focusHighlight()) { Text("Open Shizuku") }
                    }
                    OutlinedButton(onClick = onShizukuHelp, modifier = Modifier.focusHighlight()) { Text("Shizuku setup help") }
                } else {
                    Text(if (installAllowed) "Install permission: allowed" else "Install permission: needs setup",
                        style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = onInstallPermission, modifier = Modifier.focusHighlight()) { Text("Install permission settings") }
                }
                HorizontalDivider()
                PreferenceSwitch("Install updates automatically", current.autoInstallUpdates,
                    enabled = current.checkIntervalHours > 0, onChange = onAutomaticInstall)
                Text(
                    if (current.checkIntervalHours == 0) "Choose a background check schedule to enable automatic installation."
                    else if (current.installMethod == InstallMethod.SHIZUKU)
                        "At each background check, download newer builds and install them while Shizuku is ready. " +
                            "Otherwise the download waits. Includes Silora Updater itself; Silora must be installed once first."
                    else "At each background check, download newer builds and install them when Android allows it. " +
                        "If confirmation is needed, the download waits for you. Includes Silora Updater itself; Silora must be installed once first.",
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
                OutlinedButton(onClick = onCheckSelfUpdate, modifier = Modifier.focusHighlight()) { Text("Check for updater update") }
                Text("Silora Updater ${BuildConfig.VERSION_NAME} · Settings save immediately.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight()) { Text("Done") } },
    )
}

/** Uses the same device 12/24-hour preference in Settings and the dashboard. */
internal fun formatCheckHour(context: Context, hour: Int): String =
    android.text.format.DateFormat.getTimeFormat(context).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.format(Date(hour * 3_600_000L))

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
