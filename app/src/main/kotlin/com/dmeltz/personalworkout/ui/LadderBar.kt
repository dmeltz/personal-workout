package com.dmeltz.personalworkout.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dmeltz.personalworkout.core.Exercise
import com.dmeltz.personalworkout.core.ExerciseState
import com.dmeltz.personalworkout.core.Progression

/** How far the target is through the current variation's range, 0 to 1. */
fun rangeFraction(ex: Exercise, s: ExerciseState): Float {
    val v = ex.variation(s.variation)
    return ((s.target - v.min).toFloat() / (v.max - v.min)).coerceIn(0f, 1f)
}

/**
 * Where an exercise stands on its ladder: one block per variation, easiest on the left. Passed
 * variations are full, the current one is filled as far as the target is through its range, and
 * the rest are empty.
 */
@Composable
fun LadderBar(ex: Exercise, s: ExerciseState?, height: Dp = 8.dp, modifier: Modifier = Modifier) {
    val color = groupColors.getValue(ex.group)
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        ex.variations.indices.forEach { i ->
            val fill = when {
                s == null -> 0f
                i < s.variation -> 1f
                i == s.variation -> rangeFraction(ex, s).coerceAtLeast(0.04f)
                else -> 0f
            }
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(empty)) {
                if (fill > 0f) {
                    val alpha = if (s != null && i < s.variation) 0.55f else 1f
                    Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(color.copy(alpha = alpha)))
                }
            }
        }
    }
}

/** The ladder bar with words: which variation, and where the target is in its range. */
@Composable
fun LadderDetail(ex: Exercise, s: ExerciseState?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LadderBar(ex, s, height = 14.dp)
        Row(Modifier.fillMaxWidth()) {
            ex.variations.forEachIndexed { i, _ ->
                Text(
                    "${i + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (s?.variation == i) FontWeight.Bold else FontWeight.Normal,
                    color = if (s?.variation == i) groupColors.getValue(ex.group) else MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (s == null) {
            Text("Not started yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            val v = ex.variation(s.variation)
            Text(
                "Variation ${s.variation + 1} of ${ex.variations.size}: ${v.name}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Target ${ex.amount(s.variation, s.target)}, in a range of ${v.min}–${v.max} " +
                    "(${(rangeFraction(ex, s) * 100).toInt()}% of the way). " +
                    if (s.variation < ex.variations.lastIndex) "Past ${v.max} you move to variation ${s.variation + 2}."
                    else "This is the hardest variation: the target stops at ${v.max}.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Always ${ex.sets} sets.", style = MaterialTheme.typography.bodySmall)
            if (Progression.atTop(ex, s)) {
                Text(
                    "You've reached the top of this ladder. Tell Claude in the project and you'll decide together what comes next.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = groupColors.getValue(ex.group),
                )
            }
        }
    }
}
