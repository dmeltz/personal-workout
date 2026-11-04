package com.dmeltz.personalworkout.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dmeltz.personalworkout.core.AppState
import com.dmeltz.personalworkout.core.Exercise
import com.dmeltz.personalworkout.core.Figures
import com.dmeltz.personalworkout.core.Levels
import com.dmeltz.personalworkout.core.Library
import com.dmeltz.personalworkout.core.Progression
import com.dmeltz.personalworkout.core.StartLevel
import com.dmeltz.personalworkout.core.views
import com.dmeltz.personalworkout.today

/**
 * Set where you are on every exercise: the variation, the target per set and the number of sets.
 * A started exercise moves there now; one not started yet will start there when its turn comes.
 */
@Composable
fun LevelsScreen(state: AppState, library: Library, onSave: (AppState) -> Unit, onBack: () -> Unit) {
    val day = today()
    val original = remember { library.exercises.associate { it.id to Levels.current(state, it, day) } }
    val edits = remember { mutableStateMapOf<String, StartLevel>() }
    val changed = edits.filter { (id, level) -> level != original[id] }
    fun save() {
        onSave(Levels.set(library, state, changed, day))
        onBack()
    }
    Page(
        title = "Set my levels",
        onBack = onBack,
        actions = { TextButton(onClick = ::save, enabled = changed.isNotEmpty()) { Text("Save") } },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Set the variation and the reps or seconds per set you can do now with good form. " +
                        "Started exercises move there right away; the others will start there when their turn comes. " +
                        "Progress carries on from these levels.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(library.exercises, key = { it.id }) { ex ->
                val level = edits[ex.id] ?: original.getValue(ex.id)
                LevelCard(ex, level, started = state.exerciseState(ex.id) != null, changed = level != original[ex.id]) {
                    edits[ex.id] = Levels.clamp(ex, it)
                }
            }
            item {
                Button(onClick = ::save, enabled = changed.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (changed.isEmpty()) "No changes yet" else "Save ${changed.size} level" + if (changed.size > 1) "s" else "")
                }
            }
        }
    }
}

@Composable
private fun LevelCard(ex: Exercise, level: StartLevel, started: Boolean, changed: Boolean, onChange: (StartLevel) -> Unit) {
    val v = ex.variation(level.variation)
    val color = groupColors.getValue(ex.group)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Figures.views(ex.figureAt(level.variation)).firstOrNull()?.let { f ->
                    StickFigure(f, Modifier.size(56.dp).padding(end = 8.dp), still = true)
                }
                Column(Modifier.weight(1f)) {
                    Text(ex.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        (if (started) "Started" else "Not started yet") + if (changed) " · changed" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (changed) color else MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    "%.1f".format(Progression.level(ex, com.dmeltz.personalworkout.core.ExerciseState(level.variation, level.target, level.sets, 0))),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
            }
            Stepper(
                label = "Variation",
                value = "${level.variation + 1}/${ex.variations.size} ${v.name}",
                onMinus = if (level.variation > 0) {
                    { onChange(level.copy(variation = level.variation - 1, target = ex.variation(level.variation - 1).max)) }
                } else null,
                onPlus = if (level.variation < ex.variations.lastIndex) {
                    { onChange(level.copy(variation = level.variation + 1, target = ex.variation(level.variation + 1).min)) }
                } else null,
            )
            val step = Progression.small(ex)
            Stepper(
                label = if (ex.perSide(level.variation)) "Per set, per side" else "Per set",
                value = "${level.target} ${ex.unit}  (${v.min}–${v.max})",
                onMinus = if (level.target > v.min) { { onChange(level.copy(target = level.target - step)) } } else null,
                onPlus = if (level.target < v.max) { { onChange(level.copy(target = level.target + step)) } } else null,
            )
            Stepper(
                label = "Sets",
                value = "${level.sets}",
                onMinus = if (level.sets > 1) { { onChange(level.copy(sets = level.sets - 1)) } } else null,
                onPlus = if (level.sets < Progression.MAX_SETS) { { onChange(level.copy(sets = level.sets + 1)) } } else null,
            )
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: (() -> Unit)?, onPlus: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(84.dp))
        FilledTonalButton(onClick = { onMinus?.invoke() }, enabled = onMinus != null, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(44.dp)) { Text("−") }
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        FilledTonalButton(onClick = { onPlus?.invoke() }, enabled = onPlus != null, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(44.dp)) { Text("+") }
    }
}
