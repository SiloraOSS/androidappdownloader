package io.github.edrock200.appdownloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.SourceParser
import io.github.edrock200.appdownloader.data.SourceType

/**
 * Add or edit dialog. [onSubmit] returns an error message to show, or null to close.
 */
@Composable
fun EntryDialog(
    original: AppEntry?,
    prefill: String?,
    onDismiss: () -> Unit,
    onSubmit: (EntryForm, installNow: Boolean) -> String?,
) {
    val editing = original != null
    var input by rememberSaveable {
        mutableStateOf(
            original?.let { if (it.type == SourceType.GITHUB) "https://github.com/${it.source}" else it.source }
                ?: prefill.orEmpty()
        )
    }
    var name by rememberSaveable { mutableStateOf(original?.takeIf { !it.autoName }?.name.orEmpty()) }
    var includePre by rememberSaveable { mutableStateOf(original?.includePrereleases ?: false) }
    var filter by rememberSaveable { mutableStateOf(original?.assetFilter.orEmpty()) }
    var packageName by rememberSaveable { mutableStateOf(original?.packageName.orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val parsed = SourceParser.parse(input)
    val form = EntryForm(input, name, includePre, filter, packageName)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "Edit app" else "Add app") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    label = { Text("URL, aftv.news code or GitHub repo") },
                    placeholder = { Text("12345 or https://…") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    supportingText = {
                        Text(
                            error ?: when {
                                input.isBlank() -> "Direct APK link, numeric code, or github.com/owner/repo"
                                parsed == null -> "Not recognised"
                                parsed.type == SourceType.AFTV_CODE -> "aftv.news code ${parsed.source}"
                                parsed.type == SourceType.GITHUB -> "GitHub repo ${parsed.source} — releases are monitored"
                                else -> "Direct link"
                            },
                            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    placeholder = { Text("Detected from the APK") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (parsed?.type == SourceType.GITHUB) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        label = { Text("APK file filter (optional)") },
                        placeholder = { Text("e.g. universal or arm64") },
                        supportingText = { Text("Text or regex to pick a release asset when there are several") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .focusHighlight(FocusShapes.Row, scaleUp = false)
                            .toggleable(value = includePre, role = Role.Checkbox, onValueChange = { includePre = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = includePre, onCheckedChange = null)
                        Text("Include pre-releases", Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
                    }
                }
                if (editing) {
                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        label = { Text("Package name (optional)") },
                        supportingText = { Text("Set this to track an app you installed some other way") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { error = onSubmit(form, false) }, modifier = Modifier.focusHighlight()) { Text("Save") }
                if (!editing) {
                    Button(onClick = { error = onSubmit(form, true) }, modifier = Modifier.focusHighlight()) { Text("Save & install") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight()) { Text("Cancel") } },
    )
}
