package io.github.edrock200.appdownloader.ui

import android.content.Context
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.History
import io.github.edrock200.appdownloader.data.InstallRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lists when the app was installed and updated on this device, and to which versions. */
@Composable
fun HistoryDialog(
    entry: AppEntry,
    onDismiss: () -> Unit,
    onRestore: ((InstallRecord) -> Unit)? = null,
    hasSavedApk: (InstallRecord) -> Boolean = { false },
    installedVersionCode: Long? = null,
    restoreEnabled: Boolean = true,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (onRestore != null) "Past installs" else "Update history: ${entry.name}") },
        text = {
            if (entry.history.isEmpty()) {
                Text(
                    "Nothing recorded yet. Installs and updates of this app on this device will be listed here " +
                        "(the app must be installed for its versions to be tracked).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    entry.history.forEachIndexed { index, record ->
                        val local = hasSavedApk(record)
                        val canRestore = local || record.tag != null
                        if (index > 0) HorizontalDivider()
                        // Focusable rows let a TV remote move through (and scroll) the list.
                        Column(
                            (if (onRestore == null || !canRestore || !restoreEnabled) Modifier
                                .focusHighlight(FocusShapes.Row, scaleUp = false).focusable() else Modifier)
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(historyTime(context, record.at), style = MaterialTheme.typography.labelLarge)
                            Text(History.describe(record), style = MaterialTheme.typography.bodyMedium)
                            if (onRestore != null) {
                                Text("Build ${record.versionCode}" +
                                    if (record.versionCode == installedVersionCode) " · Currently installed" else "",
                                    style = MaterialTheme.typography.bodySmall)
                                Text(when {
                                    local -> "APK saved on this device"
                                    record.tag != null -> "Checks this exact GitHub release when selected"
                                    else -> "Unavailable: no saved APK or release recorded"
                                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(
                                    onClick = { onRestore(record) }, enabled = canRestore && restoreEnabled,
                                    modifier = Modifier.fillMaxWidth().focusHighlight(scaleUp = false),
                                ) { Text("Restore this build") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight()) { Text("Close") }
        },
    )
}

/** e.g. "Thu, Nov 6, 2026 at 3:05 PM", following the device's 12/24-hour setting. */
private fun historyTime(context: Context, millis: Long): String {
    if (millis <= 0) return "Unknown date"
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMdyyyy")
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis)) + " at " +
        android.text.format.DateFormat.getTimeFormat(context).format(Date(millis))
}
