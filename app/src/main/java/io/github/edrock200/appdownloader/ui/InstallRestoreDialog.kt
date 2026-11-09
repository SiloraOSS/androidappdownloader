package io.github.edrock200.appdownloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.edrock200.appdownloader.install.InstallRestoreState

@Composable
internal fun InstallRestoreDialog(
    state: InstallRestoreState,
    installed: Boolean,
    automaticUpdates: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onSettings: () -> Unit,
) {
    val pending = state.pending
    val installing = pending?.installing == true
    val canCancel = !installing && !(pending != null && state.preparing)
    AlertDialog(
        onDismissRequest = { if (canCancel) onCancel() },
        title = { Text(when {
            state.preparing -> "Preparing past install…"
            installing -> "Restoring Silora…"
            pending == null -> "Restore unavailable"
            else -> "Restore this Silora build?"
        }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.preparing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Preparing and checking the exact APK. Silora stays installed until you confirm the next step.")
                } else if (installing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Follow your device's installation prompts. The selected APK is saved so you can retry if installation is cancelled or fails.")
                } else if (pending != null) {
                    Text("${pending.record.versionName ?: "Silora"} · Build ${pending.record.versionCode}",
                        style = MaterialTheme.typography.titleMedium)
                    pending.record.tag?.let { Text("Release $it", style = MaterialTheme.typography.bodySmall) }
                    Text(if (installed)
                        "Android requires uninstalling Silora first, then installing the selected build. This is a fresh installation."
                        else "The selected build will be installed as a fresh installation.")
                    Text(if (installed)
                        "Uninstalling deletes Silora's local app data, settings, sign-in and downloaded content. You will need to sign in and set up Silora again. Restoring a build does not restore your app data."
                        else "This restores the app version only. Previous local app data, settings and downloads are not restored; you may need to sign in and set up Silora again.",
                        color = MaterialTheme.colorScheme.error)
                    Text("The APK is saved and verified. If installation fails after uninstalling, Silora will remain uninstalled until you retry.")
                    if (automaticUpdates) Text("Automatic installation will be turned off so the restored build is not immediately replaced. You can enable it again in Settings.")
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (pending != null && !installing && !state.preparing) {
                TextButton(onClick = onConfirm, modifier = Modifier.focusHighlight()) {
                    Text(if (installed) "Uninstall & restore" else "Install selected build")
                }
            } else if (pending == null && !state.preparing) {
                TextButton(onClick = onCancel, modifier = Modifier.focusHighlight()) { Text("Close") }
            }
        },
        dismissButton = {
            if (canCancel && (state.preparing || pending != null)) {
                Column {
                    TextButton(onClick = onCancel, modifier = Modifier.focusHighlight()) { Text("Cancel") }
                    if (pending != null && !state.preparing) TextButton(onClick = onSettings,
                        modifier = Modifier.focusHighlight()) { Text("Installation settings") }
                }
            }
        },
    )
}
