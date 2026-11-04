package com.dmeltz.personalworkout.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.dmeltz.personalworkout.core.Figure
import com.dmeltz.personalworkout.core.Figures
import com.dmeltz.personalworkout.core.Pose
import com.dmeltz.personalworkout.core.Prop
import com.dmeltz.personalworkout.core.views
import kotlin.math.floor

/**
 * An exercise's stick figure, moving through its frames. Parts further from the viewer are drawn
 * first and fainter, so the near arm and leg stand out; the eyes show which way the face points.
 */
@Composable
fun StickFigure(figure: Figure, modifier: Modifier) {
    val n = figure.frames.size
    val transition = rememberInfiniteTransition(label = "figure")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = if (figure.loop) {
            infiniteRepeatable(tween(figure.durationMs, easing = LinearEasing), RepeatMode.Restart)
        } else {
            infiniteRepeatable(tween(figure.durationMs, delayMillis = 300, easing = LinearEasing), RepeatMode.Reverse)
        },
        label = "t",
    )
    val body = Brand
    val background = MaterialTheme.colorScheme.background
    val propColor = Color(0xFF8E96A4)

    Canvas(modifier) {
        // A 100 x 104 box, scaled to fit and centered.
        val scale = minOf(size.width / 100f, size.height / 104f)
        val ox = (size.width - 100f * scale) / 2f
        val oy = (size.height - 104f * scale) / 2f
        fun p(x: Float, y: Float) = Offset(ox + x * scale, oy + y * scale)

        // The pose between two frames.
        val pos = if (n <= 1) 0f else if (figure.loop) t * n else t * (n - 1)
        val i = floor(pos).toInt().coerceIn(0, n - 1)
        val next = if (figure.loop) (i + 1) % n else (i + 1).coerceAtMost(n - 1)
        val f = pos - i
        val a = figure.frames[i].points
        val b = figure.frames[next].points
        val pts = FloatArray(a.size) { k -> a[k] + (b[k] - a[k]) * f }
        fun x(j: Int) = pts[j * 3]
        fun y(j: Int) = pts[j * 3 + 1]
        fun d(j: Int) = pts[j * 3 + 2]
        fun at(j: Int) = p(x(j), y(j))

        val w = 3.2f * scale * figure.scale
        if (figure.floor) drawLine(propColor, p(0f, 90f), p(100f, 90f), strokeWidth = 0.8f * scale)
        for (prop in figure.props) when (prop) {
            is Prop.Line -> drawLine(
                if (prop.faint) propColor.copy(alpha = 0.35f) else propColor,
                p(prop.x1, prop.y1), p(prop.x2, prop.y2), strokeWidth = prop.width * scale, cap = StrokeCap.Round,
            )
            is Prop.Dot -> drawCircle(propColor, prop.r * scale, p(prop.x, prop.y))
            is Prop.Bag -> drawRoundRect(
                Color(0xFFD08050), p(prop.x, prop.y), Size(prop.w * scale, prop.h * scale),
                CornerRadius(5f * scale * figure.scale), style = Stroke(1.6f * scale),
            )
            is Prop.Tether -> drawLine(Color(0xFFDCC878), at(prop.joint), p(prop.x, prop.y), strokeWidth = 1.2f * scale)
        }

        // Near parts at full strength, far ones fainter.
        val ref = d(Pose.PELVIS)
        fun shade(depth: Float): Color = body.copy(alpha = 1f - 0.55f * ((depth - ref) / 9f).coerceIn(0f, 1f))

        // Draw from far to near: the torso, each body line, and the head.
        val segs = Pose.SEGMENTS
        val count = segs.size / 2
        val torso = intArrayOf(Pose.SHOULDER_L, Pose.SHOULDER_R, Pose.HIP_R, Pose.HIP_L)
        val torsoDepth = torso.map { d(it) }.average().toFloat() + 0.01f
        val order = (0..count + 1).sortedByDescending { k ->
            when (k) {
                count -> torsoDepth
                count + 1 -> d(Pose.HEAD)
                else -> (d(segs[k * 2]) + d(segs[k * 2 + 1])) / 2f
            }
        }
        val headR = 5f * scale * figure.scale
        for (k in order) when (k) {
            count -> {
                val path = Path().apply {
                    torso.forEachIndexed { idx, j -> val o = at(j); if (idx == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
                    close()
                }
                drawPath(path, body.copy(alpha = 0.22f))
            }
            count + 1 -> {
                val c = shade(d(Pose.HEAD))
                drawCircle(background, headR, at(Pose.HEAD))
                drawCircle(c, headR, at(Pose.HEAD), style = Stroke(w * 0.8f))
                for (eye in intArrayOf(Pose.EYE_L, Pose.EYE_R)) {
                    if (d(eye) < d(Pose.HEAD) - 0.8f) drawCircle(c, 0.95f * scale * figure.scale, at(eye))
                }
            }
            else -> {
                val j1 = segs[k * 2]; val j2 = segs[k * 2 + 1]
                val foot = j2 == Pose.TOE_L || j2 == Pose.TOE_R
                // The neck stops at the head's outline.
                val end = if (j2 == Pose.HEAD) {
                    val dx = x(Pose.HEAD) - x(Pose.NECK); val dy = y(Pose.HEAD) - y(Pose.NECK)
                    val len = kotlin.math.sqrt(dx * dx + dy * dy)
                    val keep = if (len * scale > headR) 1f - headR / (len * scale) else 0f
                    p(x(Pose.NECK) + dx * keep, y(Pose.NECK) + dy * keep)
                } else at(j2)
                drawLine(
                    shade((d(j1) + d(j2)) / 2f), at(j1), end,
                    strokeWidth = if (foot) w * 0.75f else w, cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** All views of an exercise side by side, each named underneath (side, front, back, from above). */
@Composable
fun FigureViews(name: String, height: Dp) {
    val views = Figures.views(name)
    Row(Modifier.fillMaxWidth()) {
        for (f in views) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                StickFigure(f, Modifier.fillMaxWidth().height(height))
                if (views.size > 1) {
                    Text(f.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
