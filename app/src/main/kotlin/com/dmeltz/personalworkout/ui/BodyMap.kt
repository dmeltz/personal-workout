package com.dmeltz.personalworkout.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dmeltz.personalworkout.core.Muscle

/** Strong color for a muscle an exercise mainly works, lighter for one it also uses. */
val PrimaryMuscle = Color(0xFFFF8A4C)
val SecondaryMuscle = Color(0xFFFFC9A3).copy(alpha = 0.75f)
private val BodyBase = Color(0xFF3A4458)
private val MuscleIdle = Color(0xFF55617A)

/** One muscle area: an ellipse in a 100 x 120 box, on the front or the back view, drawn on both sides. */
private class Area(val muscle: Muscle, val back: Boolean, val cx: Float, val cy: Float, val rx: Float, val ry: Float, val mirror: Boolean = true)

private val areas = listOf(
    // Front
    Area(Muscle.SHOULDERS, false, 33f, 26f, 5f, 5f),
    Area(Muscle.CHEST, false, 43f, 32f, 7f, 5f),
    Area(Muscle.BICEPS, false, 29.5f, 37f, 3.3f, 7f),
    Area(Muscle.FOREARMS, false, 26.3f, 53.5f, 2.8f, 7.5f),
    Area(Muscle.WRISTS, false, 25.5f, 63.3f, 2.3f, 1.5f),
    Area(Muscle.HANDS, false, 25.2f, 67.6f, 2.6f, 2.9f),
    Area(Muscle.OBLIQUES, false, 40.5f, 50f, 2.6f, 7.5f),
    Area(Muscle.QUADS, false, 44f, 78f, 4.6f, 11f),
    Area(Muscle.CALVES, false, 44f, 100f, 2.5f, 6f),
    Area(Muscle.FEET, false, 45.8f, 111.8f, 3.4f, 2.2f),
    // Back
    Area(Muscle.UPPER_BACK, true, 50f, 29f, 12f, 6.5f, mirror = false),
    Area(Muscle.SHOULDERS, true, 33f, 26f, 5f, 5f),
    Area(Muscle.TRICEPS, true, 29.5f, 37f, 3.3f, 7f),
    Area(Muscle.FOREARMS, true, 26.3f, 53.5f, 2.8f, 7.5f),
    Area(Muscle.WRISTS, true, 25.5f, 63.3f, 2.3f, 1.5f),
    Area(Muscle.HANDS, true, 25.2f, 67.6f, 2.6f, 2.9f),
    Area(Muscle.LATS, true, 41.5f, 43f, 5f, 8.5f),
    Area(Muscle.LOWER_BACK, true, 46.5f, 55f, 3f, 5.5f),
    Area(Muscle.GLUTES, true, 44.5f, 67f, 5.5f, 5.5f),
    Area(Muscle.HAMSTRINGS, true, 44f, 82f, 4.3f, 9f),
    Area(Muscle.CALVES, true, 44f, 100f, 3.4f, 7f),
    Area(Muscle.FEET, true, 44f, 111.5f, 3f, 2.4f),
)

/** The six-pack blocks' corner, block size and gap, in the 100 x 120 box. */
private const val ABS_X = 45f
private const val ABS_Y = 39.5f

/**
 * The muscle at a point of the 100 x 120 box of the front or back view, or null. A tap a little
 * outside an area still counts, and the closest area wins.
 */
private fun muscleAt(x: Float, y: Float, back: Boolean): Muscle? {
    if (!back && x in ABS_X - 0.5f..ABS_X + 10.3f && y in ABS_Y - 0.5f..ABS_Y + 19.8f) return Muscle.ABS
    var best: Muscle? = null
    var bestD = 1.6f
    for (a in areas.filter { it.back == back }) {
        val centers = if (a.mirror) listOf(a.cx, 100f - a.cx) else listOf(a.cx)
        for (cx in centers) {
            val dx = (x - cx) / (a.rx + 1.5f)
            val dy = (y - a.cy) / (a.ry + 1.5f)
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = a.muscle }
        }
    }
    return best
}

/**
 * A front and a back body outline with each muscle area filled in its color from [colors].
 * Muscles not in the map are drawn as plain outlines. With [onTap], tapping a muscle reports it
 * (null for a tap on no muscle); the [selected] one is outlined and the others dimmed.
 */
@Composable
fun BodyMap(
    colors: Map<Muscle, Color>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    selected: Muscle? = null,
    onTap: ((Muscle?) -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val currentOnTap by rememberUpdatedState(onTap)
        val tap = if (onTap == null) Modifier else Modifier.pointerInput(Unit) {
            detectTapGestures { o ->
                val scale = minOf(size.width / 210f, size.height / 122f)
                val oy = (size.height - 122f * scale) / 2f
                val y = (o.y - oy) / scale - 1f
                val front = (o.x - (size.width / 2f - 105f * scale)) / scale
                val back = (o.x - (size.width / 2f + 5f * scale)) / scale
                currentOnTap?.invoke(
                    when {
                        front in 0f..100f -> muscleAt(front, y, back = false)
                        back in 0f..100f -> muscleAt(back, y, back = true)
                        else -> null
                    },
                )
            }
        }
        Canvas(Modifier.widthIn(max = height * 1.9f).fillMaxWidth().aspectRatio(210f / 122f).then(tap)) {
            val scale = minOf(size.width / 210f, size.height / 122f)
            val oy = (size.height - 122f * scale) / 2f
            drawView(colors, back = false, ox = (size.width / 2f) - 105f * scale, oy = oy, scale = scale, selected = selected)
            drawView(colors, back = true, ox = (size.width / 2f) + 5f * scale, oy = oy, scale = scale, selected = selected)
        }
        Row(Modifier.widthIn(max = height * 1.9f).fillMaxWidth()) {
            Text("Front", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("Back", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        }
    }
}

private fun DrawScope.drawView(colors: Map<Muscle, Color>, back: Boolean, ox: Float, oy: Float, scale: Float, selected: Muscle? = null) {
    fun p(x: Float, y: Float) = Offset(ox + x * scale, oy + (y + 1f) * scale)
    fun limb(vararg pts: Float, width: Float) {
        for (i in 0 until pts.size / 2 - 1) {
            drawLine(BodyBase, p(pts[i * 2], pts[i * 2 + 1]), p(pts[i * 2 + 2], pts[i * 2 + 3]), width * scale, StrokeCap.Round)
        }
    }
    // Body outline: head, neck, torso, arms and legs.
    drawCircle(BodyBase, 7f * scale, p(50f, 10f))
    limb(50f, 14f, 50f, 22f, width = 6f)
    for (side in listOf(1f, -1f)) {
        fun x(v: Float) = 50f + (v - 50f) * side
        limb(x(33f), 24f, x(28.5f), 45f, x(25.5f), 64f, width = 8f)
        limb(x(25.5f), 64f, x(25f), 69f, width = 6f)
        limb(x(44f), 66f, x(44f), 92f, x(44f), 110f, width = 10f)
        limb(x(44f), 110f, x(44f + 4f * (if (back) 0f else 1f)), 114f, width = 5f)
    }
    val torso = Path().apply {
        val pts = listOf(35f to 21f, 65f to 21f, 62f to 40f, 60f to 57f, 62f to 68f, 38f to 68f, 40f to 57f, 38f to 40f)
        pts.forEachIndexed { i, (x, y) -> val o = p(x, y); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
        close()
    }
    drawPath(torso, BodyBase)
    // Shoulders round off the torso corners.
    drawCircle(BodyBase, 5.5f * scale, p(34f, 25f))
    drawCircle(BodyBase, 5.5f * scale, p(66f, 25f))

    // With a muscle selected, the others fade so it stands out.
    fun colorOf(m: Muscle): Color {
        val c = colors[m] ?: MuscleIdle
        return if (selected != null && m != selected) c.copy(alpha = c.alpha * 0.35f) else c
    }
    val outline = Stroke(width = 1.2f * scale)
    for (a in areas.filter { it.back == back }) {
        val centers = if (a.mirror) listOf(a.cx, 100f - a.cx) else listOf(a.cx)
        for (cx in centers) {
            val topLeft = p(cx - a.rx, a.cy - a.ry)
            val size = Size(a.rx * 2 * scale, a.ry * 2 * scale)
            drawOval(colorOf(a.muscle), topLeft, size)
            if (a.muscle == selected) drawOval(Color.White, topLeft, size, style = outline)
        }
    }
    if (!back) {
        // The six-pack: a column of small blocks.
        for (row in 0 until 3) for (col in 0 until 2) {
            val topLeft = p(ABS_X + col * 5.2f, ABS_Y + row * 6.6f)
            val size = Size(4.6f * scale, 5.8f * scale)
            drawRoundRect(colorOf(Muscle.ABS), topLeft, size, CornerRadius(1.5f * scale))
            if (selected == Muscle.ABS) drawRoundRect(Color.White, topLeft, size, CornerRadius(1.5f * scale), style = outline)
        }
    }
}

/** "Works: chest, triceps and shoulders. Also: abs." */
fun musclesText(primary: List<Muscle>, secondary: List<Muscle>): String {
    fun list(ms: List<Muscle>): String {
        val names = ms.map { it.label.lowercase() }
        return if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
    return buildString {
        append("Works: ").append(list(primary)).append('.')
        if (secondary.isNotEmpty()) append(" Also: ").append(list(secondary)).append('.')
    }
}

/** A small legend entry: a colored dot and its label. */
@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Highlight colors for a set of muscles: true = primary, false = secondary. */
fun highlight(muscles: Map<Muscle, Boolean>): Map<Muscle, Color> =
    muscles.mapValues { (_, primary) -> if (primary) PrimaryMuscle else SecondaryMuscle }
