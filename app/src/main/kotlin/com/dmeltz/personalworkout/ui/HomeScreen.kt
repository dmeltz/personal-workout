package com.dmeltz.personalworkout.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dmeltz.personalworkout.core.AppState
import com.dmeltz.personalworkout.core.Mascot
import com.dmeltz.personalworkout.core.SessionBuilder
import com.dmeltz.personalworkout.core.sessionToContinue
import com.dmeltz.personalworkout.currentMood
import com.dmeltz.personalworkout.today

@Composable
fun HomeScreen(
    state: AppState,
    onStart: () -> Unit,
    onProgress: () -> Unit,
    onSettings: () -> Unit,
    onContinue: () -> Unit,
) {
    val day = today()
    val done = state.practicedOn(day)
    val grade = state.averageGrade(day)
    Page(
        title = "Personal Workout",
        onBack = null,
        actions = {
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            UpdatePanel(compact = true)
            val mood = currentMood(state)
            Pip(mood, 150.dp)
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Text(
                    Mascot.greeting(mood, state.streak(day), day),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Day streak", state.streak(day).toString(), Modifier.weight(1f))
                StatCard("Average grade", grade?.let { "$it%" } ?: "–", Modifier.weight(1f))
            }
            Spacer(Modifier.height(24.dp))
            val unfinished = state.sessionToContinue(day)
            val (answered, planned) = when {
                unfinished != null -> unfinished.answeredCount to unfinished.plannedCount
                done -> 1 to 1
                else -> 0 to SessionBuilder.SESSION_SIZE
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Today's practice", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    if (done && unfinished == null) "Done ✓" else "$answered of $planned exercises",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (done && unfinished == null) Good else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { answered.toFloat() / planned },
                modifier = Modifier.fillMaxWidth().height(12.dp),
                color = Good,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
            Spacer(Modifier.height(20.dp))
            if (unfinished != null) {
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Continue today's practice", fontSize = 18.sp)
                }
            } else {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (done) "Practice again" else "Start today's practice", fontSize = 18.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onProgress, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("My progress")
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
