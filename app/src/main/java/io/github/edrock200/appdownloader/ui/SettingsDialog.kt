package io.github.edrock200.appdownloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.edrock200.appdownloader.BuildConfig
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.data.SettingsState

@Composable
fun SettingsDialog(
    current: SettingsState,
    onCheckSelfUpdate: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (SettingsState) -> Unit,
) {
    val context = LocalContext.current
    var token by rememberSaveable { mutableStateOf(current.githubToken) }
    var interval by rememberSaveable { mutableStateOf(current.checkIntervalHours) }
    var deleteAfter by rememberSaveable { mutableStateOf(current.deleteApkAfterInstall) }
    var selfUpdates by rememberSaveable { mutableStateOf(current.checkSelfUpdates) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Check GitHub for updates", style = MaterialTheme.typography.titleSmall)
                Settings.INTERVAL_OPTIONS.forEach { hours ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .focusHighlight(FocusShapes.Row, scaleUp = false)
                            .selectable(selected = interval == hours, onClick = { interval = hours }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = interval == hours, onClick = null)
                        Text(
                            if (hours == 0) "Never (manual only)" else "Every $hours hours",
                            modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                        )
                    }
                }
                HorizontalDivider()
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("GitHub token (optional)") },
                    supportingText = {
                        Text("Raises the API limit from 60 to 5,000 checks/hour and allows private repos.")
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow("Delete APK after installing", deleteAfter) { deleteAfter = it }
                HorizontalDivider()
                Text("App Downloader ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                SwitchRow("Check for App Downloader updates when opened", selfUpdates) { selfUpdates = it }
                OutlinedButton(onClick = onCheckSelfUpdate, modifier = Modifier.focusHighlight()) {
                    Text("Check for app update now")
                }
                HorizontalDivider()
                val canInstall = SystemActions.canInstallPackages(context)
                Text(
                    if (canInstall) "Permission to install apps: granted" else "Permission to install apps: not granted",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!canInstall) {
                    Text(
                        Device.installPermissionHelp(context),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { SystemActions.openInstallPermissionSettings(context) },
                    modifier = Modifier.focusHighlight(),
                ) {
                    Text("Open install permission settings")
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.focusHighlight(), onClick = {
                onSave(
                    SettingsState(
                        githubToken = token,
                        checkIntervalHours = interval,
                        deleteApkAfterInstall = deleteAfter,
                        checkSelfUpdates = selfUpdates,
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight()) { Text("Cancel") } },
    )
}

/** A label + switch where the whole row is one focusable, toggleable target (easier with a remote). */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusHighlight(FocusShapes.Row, scaleUp = false)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f).padding(start = 4.dp, end = 8.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
