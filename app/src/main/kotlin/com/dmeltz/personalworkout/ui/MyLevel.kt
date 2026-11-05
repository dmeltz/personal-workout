package com.dmeltz.personalworkout.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dmeltz.personalworkout.core.AppState
import com.dmeltz.personalworkout.core.Exercise
import com.dmeltz.personalworkout.core.ExerciseState
import com.dmeltz.personalworkout.core.Levels
import com.dmeltz.personalworkout.core.Library
import com.dmeltz.personalworkout.core.Progression
import com.dmeltz.personalworkout.today

/**
 * Set where you are on one exercise: the variation and the target per set. A started exercise
 * moves there now; one not started yet will start there when its turn comes.
 */
@Composable
fun MyLevelCard(ex: Exercise, state: AppState, library: Library, onSave: (AppState) -> Unit) {
    val day = today()
    val original = Levels.current(state, ex, day)
    var level by remember(original) { mutableStateOf(original) }
    val started = state.exerciseState(ex.id) != null
    val v = ex.variation(level.variation)
    val color = groupColors.getValue(ex.group)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Set my level", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "%.1f".format(Progression.level(ex, ExerciseState(level.variation, level.target, level.sets, 0))),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
            }
            Text(
                if (started) "If the app has you too high or too low, set what you can do now with good form (always ${ex.sets} sets). Progress carries on from here."
                else "Where this exercise starts when its turn comes (always ${ex.sets} sets).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Stepper(
                label = "Variation",
                value = "${level.variation + 1}/${ex.variations.size} ${v.name}",
                onMinus = if (level.variation > 0) {
                    { level = Levels.clamp(ex, level.copy(variation = level.variation - 1, target = ex.variation(level.variation - 1).max)) }
                } else null,
                onPlus = if (level.variation < ex.variations.lastIndex) {
                    { level = Levels.clamp(ex, level.copy(variation = level.variation + 1, target = ex.variation(level.variation + 1).min)) }
                } else null,
            )
            val step = Progression.small(ex)
            Stepper(
                label = if (ex.perSide(level.variation)) "Per set, per side" else "Per set",
                value = "${level.target} ${ex.unit}  (${v.min}–${v.max})",
                onMinus = if (level.target > v.min) { { level = Levels.clamp(ex, level.copy(target = level.target - step)) } } else null,
                onPlus = if (level.target < v.max) { { level = Levels.clamp(ex, level.copy(target = level.target + step)) } } else null,
            )
            if (level != original) {
                Button(
                    onClick = { onSave(Levels.set(library, state, mapOf(ex.id to level), day)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save my level") }
            }
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
