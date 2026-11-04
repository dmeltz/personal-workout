package com.dmeltz.personalworkout.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.dmeltz.personalworkout.core.Figure
import com.dmeltz.personalworkout.core.Pose
import com.dmeltz.personalworkout.core.Prop

/** The exercise's stick figure, moving slowly between its start and end positions. */
@Composable
fun StickFigure(figure: Figure, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "figure")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, delayMillis = 400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "t",
    )
    val body = Brand
    val far = Brand.copy(alpha = 0.45f)
    val propColor = Color(0xFF8E96A4)
    val noFloor = Prop.NoFloor in figure.props
    Canvas(modifier) {
        // A 100 x 104 box, scaled to fit and centered.
        val scale = minOf(size.width / 100f, size.height / 104f)
        val ox = (size.width - 100f * scale) / 2f
        val oy = (size.height - 104f * scale) / 2f
        fun p(x: Float, y: Float) = Offset(ox + x * scale, oy + y * scale)
        fun j(i: Int): Offset {
            val a = figure.start; val b = figure.end
            return p(a.x(i) + (b.x(i) - a.x(i)) * t, a.y(i) + (b.y(i) - a.y(i)) * t)
        }
        val w = 3.2f * scale

        if (!noFloor) drawLine(propColor, p(0f, 90f), p(100f, 90f), strokeWidth = 0.8f * scale)
        for (prop in figure.props) when (prop) {
            is Prop.Bar -> drawLine(propColor, p(22f, prop.y), p(78f, prop.y), strokeWidth = 2.2f * scale, cap = StrokeCap.Round)
            is Prop.Wall -> drawLine(propColor, p(prop.x, 0f), p(prop.x, 90f), strokeWidth = 2f * scale)
            is Prop.Door -> {
                drawRect(propColor, p(prop.x, 4f), Size(12f * scale, 86f * scale), style = Stroke(1f * scale))
                drawLine(Color(0xFFDCC878), j(Pose.HAND), p(prop.x, prop.handleY), strokeWidth = 1.2f * scale)
            }
            is Prop.Box -> drawRect(propColor, p(prop.x, prop.y), Size(prop.w * scale, prop.h * scale), style = Stroke(1f * scale))
            is Prop.Bag -> drawRoundRect(
                Color(0xFFD08050), p(prop.x, prop.y), Size(prop.w * scale, prop.h * scale),
                CornerRadius(5f * scale), style = Stroke(1.6f * scale),
            )
            Prop.NoFloor, Prop.Front -> Unit
        }
        fun limb(a: Int, b: Int, c: Int, color: Color) {
            drawLine(color, j(a), j(b), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(color, j(b), j(c), strokeWidth = w, cap = StrokeCap.Round)
        }
        if (figure.front) {
            // Shoulders and hips have width; both sides are drawn alike.
            fun off(o: Offset, dx: Float) = Offset(o.x + dx * scale, o.y)
            val neck = j(Pose.NECK); val hip = j(Pose.HIP)
            val sl = off(neck, -Pose.SHOULDER_HALF); val sr = off(neck, Pose.SHOULDER_HALF)
            val hl = off(hip, -Pose.HIP_HALF); val hr = off(hip, Pose.HIP_HALF)
            fun line(a: Offset, b: Offset) = drawLine(body, a, b, strokeWidth = w, cap = StrokeCap.Round)
            line(sl, sr); line(hl, hr); line(neck, hip)
            line(sl, j(Pose.ELBOW)); line(j(Pose.ELBOW), j(Pose.HAND))
            line(sr, j(Pose.ELBOW2)); line(j(Pose.ELBOW2), j(Pose.HAND2))
            line(hl, j(Pose.KNEE)); line(j(Pose.KNEE), j(Pose.FOOT))
            line(hr, j(Pose.KNEE2)); line(j(Pose.KNEE2), j(Pose.FOOT2))
            drawCircle(body, radius = 5f * scale, center = j(Pose.HEAD), style = Stroke(w * 0.8f))
            return@Canvas
        }
        // Far side first, so the near arm and leg are drawn on top.
        limb(Pose.NECK, Pose.ELBOW2, Pose.HAND2, far)
        limb(Pose.HIP, Pose.KNEE2, Pose.FOOT2, far)
        drawLine(body, j(Pose.NECK), j(Pose.HIP), strokeWidth = w, cap = StrokeCap.Round)
        limb(Pose.HIP, Pose.KNEE, Pose.FOOT, body)
        limb(Pose.NECK, Pose.ELBOW, Pose.HAND, body)
        drawCircle(body, radius = 5f * scale, center = j(Pose.HEAD), style = Stroke(w * 0.8f))
    }
}


/** All views of an exercise side by side (side view, and a front or rear view when there is one). */
@Composable
fun FigureViews(name: String, height: androidx.compose.ui.unit.Dp) {
    val views = com.dmeltz.personalworkout.core.Figures.views(name)
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
        for (f in views) StickFigure(f, Modifier.weight(1f).height(height))
    }
}
