package io.github.edrock200.appdownloader.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.edrock200.appdownloader.data.Repeat
import io.github.edrock200.appdownloader.data.Schedule
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Picks the date and time of the scheduled "update all" run. Uses step buttons instead of
 * calendar/clock pickers so it is easy to operate with a TV remote.
 */
@Composable
fun ScheduleDialog(
    initialAt: Long,
    initialRepeat: Repeat,
    onDismiss: () -> Unit,
    onSave: (at: Long, repeat: Repeat) -> Unit,
) {
    val context = LocalContext.current
    val start = initialRunTime(initialAt)
    var day by rememberSaveable { mutableLongStateOf(start) }
    var hour by rememberSaveable { mutableIntStateOf(Calendar.getInstance().apply { timeInMillis = start }.get(Calendar.HOUR_OF_DAY)) }
    var minute by rememberSaveable { mutableIntStateOf(Calendar.getInstance().apply { timeInMillis = start }.get(Calendar.MINUTE) / 5 * 5) }
    var repeat by rememberSaveable { mutableStateOf(initialRepeat) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val at = Schedule.atTime(day, hour, minute)
    val today = Schedule.atTime(System.currentTimeMillis(), 0, 0)
    val isToday = Schedule.atTime(day, 0, 0) <= today

    fun shiftDay(by: Int) {
        day = Calendar.getInstance().apply { timeInMillis = day; add(Calendar.DAY_OF_YEAR, by) }.timeInMillis
        error = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule updates") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Date", style = MaterialTheme.typography.titleSmall)
                Stepper(
                    value = formatDate(day),
                    onDecrease = { shiftDay(-1) },
                    onIncrease = { shiftDay(1) },
                    decreaseLabel = "Previous day",
                    increaseLabel = "Next day",
                    decreaseEnabled = !isToday,
                    decreaseText = "◀",
                    increaseText = "▶",
                )

                Text("Time", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Stepper(
                        value = "%02d".format(hour),
                        onDecrease = { hour = (hour + 23) % 24; error = null },
                        onIncrease = { hour = (hour + 1) % 24; error = null },
                        decreaseLabel = "Earlier hour",
                        increaseLabel = "Later hour",
                    )
                    Text(":", style = MaterialTheme.typography.titleLarge)
                    Stepper(
                        value = "%02d".format(minute),
                        onDecrease = { minute = (minute + 55) % 60; error = null },
                        onIncrease = { minute = (minute + 5) % 60; error = null },
                        decreaseLabel = "5 minutes earlier",
                        increaseLabel = "5 minutes later",
                    )
                }
                Text(
                    if (repeat.isHourly) {
                        "Starts ${formatDateTime(context, at)}, then ${repeat.label.lowercase()}"
                    } else {
                        "Runs ${formatDateTime(context, at)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text("Repeat", style = MaterialTheme.typography.titleSmall)
                Repeat.entries.filter { !it.isHourly }.forEach { option ->
                    RepeatOption(option, selected = repeat == option) { repeat = option; error = null }
                }

                Text("Or check throughout the day", style = MaterialTheme.typography.titleSmall)
                Repeat.entries.filter { it.isHourly }.forEach { option ->
                    RepeatOption(option, selected = repeat == option) { repeat = option; error = null }
                }
                if (repeat.isHourly) {
                    Text(
                        "Frequent checks work best with \"Only update links whose file has changed\" turned on. " +
                            "With many GitHub apps, add a GitHub token in Settings to avoid hitting GitHub's limit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.focusHighlight(), onClick = {
                val next = Schedule.nextRun(at, repeat, System.currentTimeMillis())
                if (next == null) {
                    error = "Choose a time in the future"
                } else {
                    onSave(next, repeat)
                }
            }) { Text("Set") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusHighlight()) { Text("Cancel") }
        },
    )
}

@Composable
private fun RepeatOption(option: Repeat, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusHighlight(FocusShapes.Row, scaleUp = false)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(option.label, Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
    }
}

@Composable
private fun Stepper(
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseLabel: String,
    increaseLabel: String,
    decreaseEnabled: Boolean = true,
    decreaseText: String = "−",
    increaseText: String = "+",
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onDecrease,
            enabled = decreaseEnabled,
            modifier = Modifier.focusHighlight().semantics { contentDescription = decreaseLabel },
        ) { Text(decreaseText) }
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 40.dp),
        )
        OutlinedButton(
            onClick = onIncrease,
            modifier = Modifier.focusHighlight().semantics { contentDescription = increaseLabel },
        ) { Text(increaseText) }
    }
}

private fun initialRunTime(initialAt: Long): Long =
    if (initialAt > System.currentTimeMillis()) initialAt else Schedule.defaultTime(System.currentTimeMillis())

private fun formatDate(millis: Long): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMdyyyy")
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}

/** e.g. "Thu, Nov 6, 2026 at 3:00 AM", using the device's 12/24-hour preference. */
fun formatDateTime(context: Context, millis: Long): String =
    formatDate(millis) + " at " + android.text.format.DateFormat.getTimeFormat(context).format(Date(millis))
