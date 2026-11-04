package com.dmeltz.personalworkout.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.dmeltz.personalworkout.core.Measure
import com.dmeltz.personalworkout.core.Progression
import com.dmeltz.personalworkout.core.WorkoutRecord
import com.dmeltz.personalworkout.core.views
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One exercise: its ladder of variations, form cues, and history. */
@Composable
fun ExerciseScreen(exercise: Exercise, state: AppState, records: List<WorkoutRecord>, onBack: () -> Unit) {
    val s = state.exerciseState(exercise.id)
    val color = groupColors.getValue(exercise.group)
    Page(title = exercise.title, onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(exercise.group.label, style = MaterialTheme.typography.labelLarge)
            // The figure for the variation you are on (or will start with).
            FigureViews(exercise.figureAt(s?.variation ?: state.startLevels[exercise.id]?.variation ?: exercise.start), 170.dp)
            ExerciseMuscles(exercise)
            if (s != null) {
                Text(
                    "Level %.2f · now ${s.sets} × ${exercise.amount(s.variation, s.target)}".format(Progression.level(exercise, s)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
            } else {
                Text("Not started yet. It joins your plan in its turn.", style = MaterialTheme.typography.bodyMedium)
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) { LadderDetail(exercise, s) }
            }

            Text("Variations, easiest first", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    exercise.variations.forEachIndexed { i, v ->
                        val current = s?.variation == i
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Each variation's own figure, in its main view.
                            Figures.views(exercise.figureAt(i)).firstOrNull()?.let { f ->
                                StickFigure(f, Modifier.width(84.dp).height(84.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${i + 1}. ${v.name}  (${v.min}–${exercise.amount(i, v.max)})" + if (current) "  ← you are here" else "",
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                    color = if (current) color else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(v.how, style = MaterialTheme.typography.bodySmall)
                                v.cues.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
            Text("Form", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            exercise.cues.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }

            Text("Level over time", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val byDay = records.groupBy { it.day }.map { (d, rs) -> d to rs.last().levelAfter }.sortedBy { it.first }
            LevelChart(listOf(LineSeries(exercise.title, color, byDay)), Modifier.fillMaxWidth().height(180.dp))
            Text(
                "The level is the variation number plus how far you are through its range, so it climbs " +
                    "steadily even when you move to a harder variation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            Text(
                if (exercise.measure == Measure.REPS) "Reps per set over time" else "Seconds per set over time",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            val logged = records.filter { it.effort != Effort.SKIPPED && it.done.isNotEmpty() }.groupBy { it.day }
                .map { (d, rs) -> d to rs.last() }.sortedBy { it.first }
            val target = LineSeries("Target", Color.White, logged.map { (d, r) -> d to r.target.toDouble() })
            val done = LineSeries("Done (average)", color, logged.map { (d, r) -> d to r.done.average() })
            LevelChart(listOf(target, done), Modifier.fillMaxWidth().height(180.dp))
            Legend(listOf(target, done))
            val changes = records.zipWithNext().filter { (a, b) -> b.variationIndex != a.variationIndex }
            if (changes.isNotEmpty()) {
                val format = DateTimeFormatter.ofPattern("d MMM")
                Text(
                    "Drops in the target are moves to a new variation: " +
                        changes.joinToString("; ") { (_, b) -> "${LocalDate.ofEpochDay(b.day).format(format)} to ${b.variation}" } + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            if (records.isNotEmpty()) {
                Text("Recent logs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val format = DateTimeFormatter.ofPattern("EEE d MMM")
                for (r in records.takeLast(15).reversed()) {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("${LocalDate.ofEpochDay(r.day).format(format)} · ${r.variation}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (r.effort == Effort.SKIPPED) "Skipped (target ${r.target})"
                                else "${r.done.joinToString(" / ")} ${exercise.unit}${if (r.perSide) " per side" else ""} (target ${r.target})",
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
