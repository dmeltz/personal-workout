package com.dmeltz.personalworkout.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dmeltz.personalworkout.core.Effort
import com.dmeltz.personalworkout.core.History
import com.dmeltz.personalworkout.core.Library
import com.dmeltz.personalworkout.core.WorkoutRecord
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Every past workout, newest first, with each logged exercise to correct or delete. */
@Composable
fun HistoryScreen(
    library: Library,
    records: List<WorkoutRecord>,
    onEdit: (WorkoutRecord, List<Int>, Effort) -> Unit,
    onDelete: (List<WorkoutRecord>) -> Unit,
    onBack: () -> Unit,
) {
    val workouts = remember(records) { History.workouts(records) }
    // What is waiting for "are you sure?": a whole workout or one exercise.
    var confirm by remember { mutableStateOf<Pair<String, List<WorkoutRecord>>?>(null) }
    val format = DateTimeFormatter.ofPattern("EEEE d MMMM")
    Page(title = "Past workouts", onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Tap Edit to fix the reps or how it felt, or delete a log. Changing an exercise's latest log " +
                        "also redoes its next target; older logs only change the history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (workouts.isEmpty()) item { Text("No workouts yet.", style = MaterialTheme.typography.bodyLarge) }
            items(workouts, key = { "${it.day}-${it.session}" }) { w ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(LocalDate.ofEpochDay(w.day).format(format), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            val done = w.records.count { it.effort != Effort.SKIPPED }
                            Text(
                                "Workout ${w.session} · $done of ${w.records.size} exercises done",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        TextButton(onClick = {
                            confirm = "Delete the workout of ${LocalDate.ofEpochDay(w.day).format(format)}?" to w.records
                        }) { Text("Delete", color = Bad) }
                    }
                    for (r in w.records) {
                        val ex = library.exercise(r.exerciseId) ?: continue
                        key(r.timestamp, r.exerciseId) {
                            LoggedRow(
                                ex, r,
                                title = if (r.variation == ex.title) r.variation else "${ex.title}: ${r.variation}",
                                onDelete = { confirm = "Delete ${r.variation} from this workout?" to listOf(r) },
                            ) { sets, effort -> onEdit(r, sets, effort) }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { (question, gone) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(question) },
            text = { Text("This can't be undone. If it's an exercise's latest log, its next target goes back to what it was before.") },
            confirmButton = { TextButton(onClick = { onDelete(gone); confirm = null }) { Text("Delete", color = Bad) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
