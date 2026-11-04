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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dmeltz.personalworkout.core.AppState
import com.dmeltz.personalworkout.core.Category
import com.dmeltz.personalworkout.core.Curriculum
import com.dmeltz.personalworkout.core.GradePoint
import com.dmeltz.personalworkout.core.Mascot
import com.dmeltz.personalworkout.core.Mastery
import com.dmeltz.personalworkout.core.Mood
import com.dmeltz.personalworkout.core.Rule
import com.dmeltz.personalworkout.today
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun ProgressScreen(
    state: AppState,
    curriculum: Curriculum,
    onRule: (String) -> Unit,
    onBack: () -> Unit,
) {
    val day = today()
    val grade = state.averageGrade(day)
    val started = curriculum.rules.count { state.ruleStats(it.id).introduced }
    Page(title = "My progress", onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                val mood = when {
                    grade == null -> Mood.CALM
                    grade >= 80 -> Mood.HAPPY
                    grade >= 60 -> Mood.CALM
                    else -> Mood.WAITING
                }
                PipSays(mood, Mascot.progressComment(state, curriculum, day), Modifier.padding(bottom = 12.dp))
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Average grade: ${grade?.let { "$it%" } ?: "–"}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "$started of ${curriculum.rules.size} rules started. The grade averages the started rules.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        GradeChart(state.gradeHistory, Modifier.fillMaxWidth().height(180.dp).padding(top = 12.dp))
                    }
                }
            }
            for (category in Category.entries) {
                item {
                    Text(
                        if (category == Category.SPELLING) "Spelling" else "Grammar",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                }
                items(curriculum.rules.filter { it.category == category }, key = { it.id }) { rule ->
                    RuleRow(rule, state, day) { onRule(rule.id) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RuleRow(rule: Rule, state: AppState, day: Long, onClick: () -> Unit) {
    val stats = state.ruleStats(rule.id)
    val score = Mastery.score(stats, day)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${rule.id}  ${rule.title}", style = MaterialTheme.typography.bodyLarge)
            when {
                !stats.introduced -> Text("Not started yet", style = MaterialTheme.typography.bodySmall)
                !stats.reliable -> Text(
                    "${stats.answers} answers so far, score still settling",
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> Text(
                    "${stats.correctAnswers} of ${stats.answers} answers right",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (stats.introduced) {
            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.End) {
                Text(
                    "${Math.round(score * 100)}%",
                    color = scoreColor(score),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                LinearProgressIndicator(
                    progress = { score.toFloat() },
                    color = scoreColor(score),
                    modifier = Modifier.width(64.dp),
                )
            }
        }
    }
}

/** Average grade per practice day, 0 to 100%. */
@Composable
fun GradeChart(points: List<GradePoint>, modifier: Modifier) {
    if (points.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "The graph appears after your second practice day.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val format = DateTimeFormatter.ofPattern("d MMM")
    Column(modifier) {
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(36.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                for (label in listOf("100%", "75%", "50%", "25%", "0%")) Text(label, style = labelStyle)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp)) {
                for (i in 0..4) {
                    val y = size.height * i / 4f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                val first = points.first().day
                val span = (points.last().day - first).coerceAtLeast(1).toFloat()
                fun at(p: GradePoint) = Offset(
                    size.width * (p.day - first) / span,
                    size.height * (1f - p.grade / 100f),
                )
                val path = Path().apply {
                    moveTo(at(points[0]).x, at(points[0]).y)
                    for (p in points.drop(1)) lineTo(at(p).x, at(p).y)
                }
                drawPath(path, lineColor, style = Stroke(width = 3.dp.toPx()))
                if (points.size <= 40) for (p in points) drawCircle(lineColor, radius = 3.dp.toPx(), center = at(p))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 36.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(LocalDate.ofEpochDay(points.first().day).format(format), style = labelStyle)
            Text(LocalDate.ofEpochDay(points.last().day).format(format), style = labelStyle)
        }
    }
}


