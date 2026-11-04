package com.dmeltz.personalworkout.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dmeltz.personalworkout.core.AppState
import com.dmeltz.personalworkout.core.Body
import com.dmeltz.personalworkout.core.Day
import com.dmeltz.personalworkout.core.Exercise
import com.dmeltz.personalworkout.core.Group
import com.dmeltz.personalworkout.core.Library
import com.dmeltz.personalworkout.core.Mascot
import com.dmeltz.personalworkout.core.Mood
import com.dmeltz.personalworkout.core.Progression
import com.dmeltz.personalworkout.today
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor

/** One color per muscle group, for the group lines in the overall chart. */
val groupColors = mapOf(
    Group.PUSH to Color(0xFF8FB4FF),
    Group.PULL to Color(0xFF7BD88F),
    Group.LEGS to Color(0xFFFFB066),
    Group.CORE to Color(0xFFD9A6FF),
    Group.CONDITIONING to Color(0xFFFF7A7A),
)

@Composable
fun ProgressScreen(
    state: AppState,
    library: Library,
    onState: (AppState) -> Unit,
    onExercise: (String) -> Unit,
    onBack: () -> Unit,
) {
    val day = today()
    val overall = Progression.overallLevel(library, state)
    val started = library.exercises.count { state.exerciseState(it.id) != null }
    Page(title = "My progress", onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                PipSays(
                    if (overall == null) Mood.CALM else Mood.HAPPY,
                    Mascot.progressComment(state, library, day),
                    Modifier.padding(bottom = 12.dp),
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Overall level: ${overall?.let { "%.2f".format(it) } ?: "–"}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "$started of ${library.exercises.size} exercises started. Each exercise's level is its " +
                                "variation (1 = easiest) plus how far you are through that variation's range.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val series = buildList {
                            add(LineSeries("Overall", Color.White, state.levelHistory.map { it.day to it.overall }))
                            for (g in Group.entries) {
                                val pts = state.levelHistory.mapNotNull { p -> p.groups[g.name]?.let { p.day to it } }
                                if (pts.isNotEmpty()) add(LineSeries(g.short, groupColors.getValue(g), pts))
                            }
                        }
                        LevelChart(series, Modifier.fillMaxWidth().height(200.dp).padding(top = 12.dp))
                        Legend(series)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val last = state.weights.lastOrNull()
                        Text(
                            "Weight: ${last?.let { "%.1f kg".format(it.kg) } ?: "–"}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Body.change(state, day, 28)?.let {
                            Text("%+.1f kg over the last 4 weeks.".format(it), style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            "With muscle building, a slow gain (up to about 0.25 kg a week) is expected and fine.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        LevelChart(
                            listOf(LineSeries("Weight", Color(0xFFDCC878), state.weights.map { it.day to it.kg })),
                            Modifier.fillMaxWidth().height(160.dp),
                        )
                        var adding by remember { mutableStateOf(false) }
                        if (adding) {
                            WeightInput(last?.kg) {
                                onState(Body.logWeight(state, day, it))
                                adding = false
                            }
                        } else {
                            TextButton(onClick = { adding = true }) { Text("Add a weigh-in") }
                        }
                    }
                }
            }
            for (group in Group.entries) {
                val exercises = library.inGroup(group)
                item {
                    val level = Progression.groupLevel(library, state, group)
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(group.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(level?.let { "%.2f".format(it) } ?: "", color = groupColors.getValue(group), fontWeight = FontWeight.Bold)
                    }
                }
                items(exercises, key = { it.id }) { ex ->
                    ExerciseRow(ex, state) { onExercise(ex.id) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ExerciseRow(ex: Exercise, state: AppState, onClick: () -> Unit) {
    val s = state.exerciseState(ex.id)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(ex.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (s == null) "Not started yet"
                else "${ex.variation(s.variation).name} · ${s.sets} × ${s.target} ${ex.unit}${if (ex.perSide) " per side" else ""}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (s != null) {
            Text(
                "%.1f".format(Progression.level(ex, s)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = groupColors.getValue(ex.group),
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}

data class LineSeries(val name: String, val color: Color, val points: List<Pair<Day, Double>>)

@Composable
fun Legend(series: List<LineSeries>) {
    if (series.size < 2) return
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (s in series) Text("● ${s.name}", color = s.color, style = MaterialTheme.typography.labelSmall)
    }
}

/** Levels over time. The y axis fits the data, in whole levels. */
@Composable
fun LevelChart(series: List<LineSeries>, modifier: Modifier) {
    val all = series.flatMap { it.points }
    if (all.map { it.first }.distinct().size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "The graph appears after your second entry.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val lo = floor(all.minOf { it.second }).coerceAtLeast(0.0)
    val hi = ceil(all.maxOf { it.second }).coerceAtLeast(lo + 1)
    val first = all.minOf { it.first }
    val last = all.maxOf { it.first }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val format = DateTimeFormatter.ofPattern("d MMM")
    val steps = (hi - lo).toInt().coerceIn(1, 6)
    Column(modifier) {
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(28.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                for (i in steps downTo 0) Text("%.0f".format(lo + (hi - lo) * i / steps), style = labelStyle)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp)) {
                for (i in 0..steps) {
                    val y = size.height * i / steps
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                val span = (last - first).coerceAtLeast(1).toFloat()
                fun at(p: Pair<Day, Double>) = Offset(
                    size.width * (p.first - first) / span,
                    size.height * (1f - ((p.second - lo) / (hi - lo)).toFloat()),
                )
                for (s in series.reversed()) {
                    if (s.points.isEmpty()) continue
                    val main = s === series.first()
                    val path = Path().apply {
                        moveTo(at(s.points[0]).x, at(s.points[0]).y)
                        for (p in s.points.drop(1)) lineTo(at(p).x, at(p).y)
                    }
                    drawPath(path, s.color, style = Stroke(width = (if (main) 3f else 1.5f).dp.toPx()))
                    if (main && s.points.size <= 40) for (p in s.points) drawCircle(s.color, radius = 3.dp.toPx(), center = at(p))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 28.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(LocalDate.ofEpochDay(first).format(format), style = labelStyle)
            Text(LocalDate.ofEpochDay(last).format(format), style = labelStyle)
        }
    }
}
