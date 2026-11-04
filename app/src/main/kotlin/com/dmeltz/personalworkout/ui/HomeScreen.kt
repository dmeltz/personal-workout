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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dmeltz.personalworkout.core.AppState
import com.dmeltz.personalworkout.core.DayKind
import com.dmeltz.personalworkout.core.Library
import com.dmeltz.personalworkout.core.Mascot
import com.dmeltz.personalworkout.core.Progression
import com.dmeltz.personalworkout.core.SessionBuilder
import com.dmeltz.personalworkout.core.Sessions
import com.dmeltz.personalworkout.core.sessionToContinue
import com.dmeltz.personalworkout.currentMood
import com.dmeltz.personalworkout.today

@Composable
fun HomeScreen(
    state: AppState,
    library: Library,
    /** Starts a session; true means "train anyway" on a rest day. */
    onStart: (Boolean) -> Unit,
    onState: (AppState) -> Unit,
    onProgress: () -> Unit,
    onSettings: () -> Unit,
    onContinue: () -> Unit,
) {
    val day = today()
    val trained = state.trainedOn(day)
    val kind = SessionBuilder.dayKind(state, day)
    val level = Progression.overallLevel(library, state)
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
            if (!state.safetyAccepted) {
                SafetyCard(library.safety) { onState(state.copy(safetyAccepted = true)) }
                Spacer(Modifier.height(16.dp))
            }
            val mood = currentMood(state)
            Pip(mood, 130.dp)
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Text(
                    Mascot.greeting(mood, state, day),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Day streak", state.streak(day).toString(), Modifier.weight(1f))
                StatCard("Level", level?.let { "%.1f".format(it) } ?: "–", Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Capoeira today", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Counts for the streak. Today's session gets short and skips legs, and so does tomorrow's.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.isCapoeira(day), onCheckedChange = { onState(Sessions.setCapoeira(state, day, it)) })
            }
            Spacer(Modifier.height(16.dp))

            val unfinished = state.sessionToContinue(day)
            val plan = if (unfinished == null && kind != DayKind.REST) SessionBuilder.build(library, state, day) else null
            Text(
                when {
                    unfinished != null -> "Today's workout"
                    trained -> "Today's workout is done ✓"
                    kind == DayKind.REST -> "Rest day"
                    kind == DayKind.CAPOEIRA -> "Optional short session"
                    kind == DayKind.AFTER_CAPOEIRA -> "Upper body and core today"
                    else -> "Today's workout"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (trained && unfinished == null) Good else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
            if (unfinished != null) {
                Spacer(Modifier.height(8.dp))
                Text("${unfinished.doneCount} of ${unfinished.plannedCount} exercises", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { unfinished.doneCount.toFloat() / unfinished.plannedCount.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().height(12.dp),
                    color = Good,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            } else if (plan != null && !trained) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "About ${plan.minutes} minutes: " +
                        plan.exerciseIds.mapNotNull { library.exercise(it)?.title }.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(20.dp))
            when {
                unfinished != null -> Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Continue today's workout", fontSize = 18.sp)
                }
                kind == DayKind.REST && !trained -> OutlinedButton(
                    onClick = { onStart(true) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Train anyway") }
                trained -> OutlinedButton(onClick = { onStart(false) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Another session")
                }
                else -> Button(onClick = { onStart(false) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (kind == DayKind.CAPOEIRA) "Start the short session" else "Start today's workout", fontSize = 18.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onProgress, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("My progress")
            }
        }
    }
}

/** The safety note: shown on the home screen until acknowledged, and always in Settings. */
@Composable
fun SafetyCard(lines: List<String>, onOk: (() -> Unit)?) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Train safely", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            if (onOk != null) {
                Button(onClick = onOk, modifier = Modifier.fillMaxWidth()) { Text("Got it") }
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
