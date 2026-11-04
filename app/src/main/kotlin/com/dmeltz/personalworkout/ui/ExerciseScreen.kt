package com.dmeltz.personalworkout.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dmeltz.personalworkout.core.AppState
import com.dmeltz.personalworkout.core.Effort
import com.dmeltz.personalworkout.core.Exercise
import com.dmeltz.personalworkout.core.Figures
import com.dmeltz.personalworkout.core.Progression
import com.dmeltz.personalworkout.core.WorkoutRecord
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One exercise: its ladder of variations, form cues, and history. */
@Composable
fun ExerciseScreen(exercise: Exercise, state: AppState, records: List<WorkoutRecord>, onBack: () -> Unit) {
    val s = state.exerciseState(exercise.id)
    val color = groupColors.getValue(exercise.group)
    val unit = exercise.unit + if (exercise.perSide) " per side" else ""
    Page(title = exercise.title, onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(exercise.group.label, style = MaterialTheme.typography.labelLarge)
            Figures.all[exercise.figure]?.let { StickFigure(it, Modifier.fillMaxWidth().height(170.dp)) }
            if (s != null) {
                Text(
                    "Level %.2f · now ${s.sets} × ${s.target} $unit".format(Progression.level(exercise, s)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
            } else {
                Text("Not started yet. It joins your plan in its turn.", style = MaterialTheme.typography.bodyMedium)
            }

            Text("Variations, easiest first", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    exercise.variations.forEachIndexed { i, v ->
                        val current = s?.variation == i
                        Column {
                            Text(
                                "${i + 1}. ${v.name}  (${v.min}–${v.max} $unit)" + if (current) "  ← you are here" else "",
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                color = if (current) color else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(v.how, style = MaterialTheme.typography.bodySmall)
                            v.cues.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            Text("Form", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            exercise.cues.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }

            Text("Level over time", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val byDay = records.groupBy { it.day }.map { (d, rs) -> d to rs.last().levelAfter }.sortedBy { it.first }
            LevelChart(listOf(LineSeries(exercise.title, color, byDay)), Modifier.fillMaxWidth().height(180.dp))

            if (records.isNotEmpty()) {
                Text("Recent logs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val format = DateTimeFormatter.ofPattern("EEE d MMM")
                for (r in records.takeLast(15).reversed()) {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("${LocalDate.ofEpochDay(r.day).format(format)} · ${r.variation}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (r.effort == Effort.SKIPPED) "Skipped (target ${r.target})"
                                else "${r.done.joinToString(" / ")} ${exercise.unit} (target ${r.target})",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            r.effort.name.lowercase().replaceFirstChar { it.uppercase() },
                            color = when (r.effort) {
                                Effort.EASY -> Good
                                Effort.OK -> Brand
                                Effort.HARD -> Color(0xFFFFB066)
                                Effort.SKIPPED -> Bad
                            },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
