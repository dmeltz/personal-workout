package com.dmeltz.personalworkout.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.FilterChip
import com.dmeltz.personalworkout.core.AppState
import com.dmeltz.personalworkout.core.Library

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: AppState,
    library: Library,
    version: String,
    onState: (AppState) -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    Page(title = "Settings", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Daily reminder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Remind me at %02d:%02d if I haven't trained".format(state.reminderHour, state.reminderMinute),
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.reminderEnabled, onCheckedChange = { onState(state.copy(reminderEnabled = it)) })
            }
            OutlinedButton(onClick = {
                TimePickerDialog(context, { _, hour, minute ->
                    onState(state.copy(reminderHour = hour, reminderMinute = minute, reminderEnabled = true))
                }, state.reminderHour, state.reminderMinute, true).show()
            }) { Text("Change reminder time") }

            HorizontalDivider()
            Text("Rest days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Planned rest days don't break your streak. Mark capoeira days on the home screen.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WEEKDAYS.forEachIndexed { i, name ->
                    val wd = i + 1
                    FilterChip(
                        selected = wd in state.restWeekdays,
                        onClick = {
                            val days = if (wd in state.restWeekdays) state.restWeekdays - wd else (state.restWeekdays + wd).sorted()
                            onState(state.copy(restWeekdays = days))
                        },
                        label = { Text(name) },
                    )
                }
            }

            HorizontalDivider()
            SafetyCard(library.safety, onOk = null)

            HorizontalDivider()
            Text("Send my history to Claude", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            SyncPanel()

            HorizontalDivider()
            Text("Export my history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Saves your progress and every logged exercise as one JSON file you can share or keep.")
            OutlinedButton(onClick = onExport) { Text("Export history") }

            HorizontalDivider()
            Text("App updates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            UpdatePanel(compact = false)

            HorizontalDivider()
            Text("Version $version · ${library.exercises.size} exercises", style = MaterialTheme.typography.bodySmall)
        }
    }
}
