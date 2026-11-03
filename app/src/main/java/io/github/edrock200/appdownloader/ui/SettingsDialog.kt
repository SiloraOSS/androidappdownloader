package io.github.edrock200.appdownloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.data.SettingsState

@Composable
fun SettingsDialog(
    current: SettingsState,
    onDismiss: () -> Unit,
    onSave: (SettingsState) -> Unit,
) {
    val context = LocalContext.current
    var token by rememberSaveable { mutableStateOf(current.githubToken) }
    var interval by rememberSaveable { mutableStateOf(current.checkIntervalHours) }
    var deleteAfter by rememberSaveable { mutableStateOf(current.deleteApkAfterInstall) }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Delete APK after installing", Modifier.weight(1f))
                    Switch(checked = deleteAfter, onCheckedChange = { deleteAfter = it })
                }
                HorizontalDivider()
                val canInstall = SystemActions.canInstallPackages(context)
                Text(
                    if (canInstall) "Permission to install apps: granted" else "Permission to install apps: not granted",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { SystemActions.openInstallPermissionSettings(context) }) {
                    Text("Open install permission settings")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(SettingsState(githubToken = token, checkIntervalHours = interval, deleteApkAfterInstall = deleteAfter))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
