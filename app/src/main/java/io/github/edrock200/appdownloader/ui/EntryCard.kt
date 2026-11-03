package io.github.edrock200.appdownloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.edrock200.appdownloader.OpState
import io.github.edrock200.appdownloader.data.SourceType
import io.github.edrock200.appdownloader.net.AftvResolver

@Composable
fun EntryCard(
    item: EntryUi,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onCheck: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val entry = item.entry
    val installed = item.installed
    val op = item.op
    val busy = op != null && op !is OpState.Failed

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (item.hasUpdate) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        TypeBadge(entry.type)
                    }
                    Text(
                        when (entry.type) {
                            SourceType.URL -> entry.source
                            SourceType.AFTV_CODE -> AftvResolver.shortUrl(entry.source).removePrefix("https://")
                            SourceType.GITHUB -> "github.com/${entry.source}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OverflowMenu(item, onCheck, onEdit, onDelete, onMessage)
            }

            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(end = 12.dp)) {
                InfoLine(
                    "Installed",
                    when {
                        installed != null -> versionLabel(installed.versionName, installed.versionCode)
                        entry.packageName != null -> "Not installed"
                        else -> "Unknown (install once to track)"
                    },
                )
                if (entry.type == SourceType.GITHUB) {
                    when {
                        entry.lastCheckError != null -> Text(
                            entry.lastCheckError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        entry.latestTag != null -> InfoLine(
                            "Latest",
                            buildString {
                                append(entry.latestTag)
                                entry.latestPublishedAt?.take(10)?.let { append(" · $it") }
                                entry.latestAssetName?.let { append(" · $it") }
                            },
                            highlight = item.hasUpdate,
                        )
                        else -> InfoLine("Latest", "Not checked yet")
                    }
                }

                when (op) {
                    null -> Unit
                    is OpState.Failed -> {
                        Spacer(Modifier.height(4.dp))
                        Text(op.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when (op) {
                                OpState.Resolving -> if (entry.type == SourceType.GITHUB) "Checking release…" else "Resolving link…"
                                is OpState.Downloading -> op.progress?.let { "Downloading… ${(it * 100).toInt()}%" } ?: "Downloading…"
                                OpState.Installing -> "Waiting for installer…"
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        val progress = (op as? OpState.Downloading)?.progress
                        if (progress != null) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        busy -> OutlinedButton(onClick = onCancel) { Text(if (op == OpState.Installing) "Dismiss" else "Cancel") }
                        else -> Button(onClick = onInstall) {
                            Text(
                                when {
                                    item.hasUpdate -> "Update to ${entry.latestTag}"
                                    installed == null -> "Install"
                                    entry.type == SourceType.GITHUB -> "Reinstall"
                                    else -> "Re-download"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (installed != null && entry.packageName != null && !busy &&
                        SystemActions.canLaunch(context, entry.packageName)
                    ) {
                        OutlinedButton(onClick = { SystemActions.launchApp(context, entry.packageName) }) { Text("Open") }
                    }
                    if (op is OpState.Failed) {
                        op.openUrl?.let { url ->
                            TextButton(onClick = {
                                if (!SystemActions.openUrl(context, url)) onMessage("No browser available")
                            }) { Text("Open in browser") }
                        }
                        TextButton(onClick = onCancel) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverflowMenu(
    item: EntryUi,
    onCheck: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val entry = item.entry
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (entry.type == SourceType.GITHUB) {
                DropdownMenuItem(text = { Text("Check for update") }, onClick = { open = false; onCheck() })
                DropdownMenuItem(text = { Text("Open release page") }, onClick = {
                    open = false
                    val url = entry.latestReleaseUrl ?: "https://github.com/${entry.source}/releases"
                    if (!SystemActions.openUrl(context, url)) onMessage("No browser available")
                })
            } else {
                DropdownMenuItem(text = { Text("Open link in browser") }, onClick = {
                    open = false
                    val url = if (entry.type == SourceType.AFTV_CODE) AftvResolver.shortUrl(entry.source) else entry.source
                    if (!SystemActions.openUrl(context, url)) onMessage("No browser available")
                })
            }
            DropdownMenuItem(text = { Text("Edit") }, onClick = { open = false; onEdit() })
            if (item.installed != null && entry.packageName != null) {
                DropdownMenuItem(text = { Text("Uninstall app") }, onClick = {
                    open = false
                    if (!SystemActions.uninstall(context, entry.packageName)) onMessage("Could not open uninstaller")
                })
            }
            DropdownMenuItem(text = { Text("Remove from list") }, onClick = { open = false; onDelete() })
        }
    }
}

@Composable
private fun TypeBadge(type: SourceType) {
    val (label, color) = when (type) {
        SourceType.URL -> "Link" to MaterialTheme.colorScheme.secondaryContainer
        SourceType.AFTV_CODE -> "aftv.news" to MaterialTheme.colorScheme.primaryContainer
        SourceType.GITHUB -> "GitHub" to Color(0xFF24292F)
    }
    val textColor = if (type == SourceType.GITHUB) Color.White else MaterialTheme.colorScheme.onSurface
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(
            label,
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String, highlight: Boolean = false) {
    Row {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun versionLabel(name: String?, code: Long) = if (name.isNullOrBlank()) "build $code" else "$name ($code)"
