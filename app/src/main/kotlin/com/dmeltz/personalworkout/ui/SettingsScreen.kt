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
import com.dmeltz.personalworkout.core.AppState

@Composable
fun SettingsScreen(
    state: AppState,
    itemCount: Int,
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
                    "Remind me at %02d:%02d if I haven't practiced".format(state.reminderHour, state.reminderMinute),
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
            Text("Send my answers to Claude", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            SyncPanel()

            HorizontalDivider()
            Text("Share my answers as a file", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Saves your progress and every answer, including all mistakes, as one file you can share.")
            OutlinedButton(onClick = onExport) { Text("Export answer log") }

            HorizontalDivider()
            Text("App updates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            UpdatePanel(compact = false)

            HorizontalDivider()
            Text("Version $version · $itemCount practice items", style = MaterialTheme.typography.bodySmall)
        }
    }
}
