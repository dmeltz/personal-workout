package com.dmeltz.personalworkout.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dmeltz.personalworkout.core.ActiveSession
import com.dmeltz.personalworkout.core.AppState
import com.dmeltz.personalworkout.core.Effort
import com.dmeltz.personalworkout.core.Exercise
import com.dmeltz.personalworkout.core.ExerciseState
import com.dmeltz.personalworkout.core.Figures
import com.dmeltz.personalworkout.core.Library
import com.dmeltz.personalworkout.core.Mascot
import com.dmeltz.personalworkout.core.Measure
import com.dmeltz.personalworkout.core.Mood
import com.dmeltz.personalworkout.core.Progression
import com.dmeltz.personalworkout.core.Sessions
import com.dmeltz.personalworkout.core.WarmupMove
import com.dmeltz.personalworkout.core.WorkoutRecord
import com.dmeltz.personalworkout.today
import kotlinx.coroutines.delay

@Composable
fun SessionScreen(
    library: Library,
    initialState: AppState,
    /** Every logged exercise before this screen opened, for "last time". */
    history: List<WorkoutRecord>,
    onState: (AppState) -> Unit,
    onRecord: (WorkoutRecord) -> Unit,
    onFinished: () -> Unit,
    onQuit: () -> Unit,
) {
    val day = remember { today() }
    // The session lives in the app state and is saved after every log, so it can be continued later.
    var state by remember { mutableStateOf(initialState) }
    fun update(newState: AppState) {
        state = newState
        onState(newState)
    }
    // Kept for the summary after the session is finished and cleared from the state.
    var finished by remember { mutableStateOf<ActiveSession?>(null) }
    var lastMessage by remember { mutableStateOf<String?>(null) }

    // The screen stays on while training.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val session = state.activeSession
    val nextId = session?.next()
    val exercise = nextId?.let { library.exercise(it) }
    val done = session == null || exercise == null

    LaunchedEffect(done) {
        if (done && finished == null && session != null) {
            finished = session
            update(Sessions.finish(library, state, day))
        }
    }

    fun log(ex: Exercise, before: ExerciseState, sets: List<Int>, effort: Effort, unclear: Boolean) {
        val current = state.activeSession ?: return
        val next = Sessions.log(state, ex, sets, effort, day)
        val after = next.exerciseState(ex.id) ?: return
        onRecord(
            WorkoutRecord(
                timestamp = System.currentTimeMillis(),
                day = day,
                session = current.number,
                exerciseId = ex.id,
                exercise = ex.title,
                group = ex.group,
                variationIndex = before.variation,
                variation = ex.variation(before.variation).name,
                measure = ex.measure,
                perSide = ex.perSide,
                target = before.target,
                setsPlanned = before.sets,
                done = sets,
                effort = effort,
                levelBefore = Progression.level(ex, before),
                levelAfter = Progression.level(ex, after),
                nextVariation = ex.variation(after.variation).name,
                nextTarget = after.target,
                nextSets = after.sets,
                capoeiraDay = state.isCapoeira(day),
                unclear = unclear,
            ),
        )
        lastMessage = if (after.variation > before.variation) {
            "Level up! Next time: ${ex.variation(after.variation).name}."
        } else Mascot.logged(effort, System.currentTimeMillis())
        update(next)
    }

    val shown = session ?: finished
    val planned = shown?.plannedCount ?: 0
    val position = shown?.doneCount ?: 0

    Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onQuit) { Icon(Icons.Filled.Close, contentDescription = "Stop") }
            LinearProgressIndicator(
                progress = { if (planned == 0) 1f else position.toFloat() / planned },
                modifier = Modifier.weight(1f).height(10.dp),
                color = Good,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
            Text(
                "$position / $planned",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 10.dp, end = 16.dp),
            )
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                done -> DoneView(finished ?: session, state, library, onFinished)
                !session!!.warmupDone -> WarmupView(library.warmup) { update(Sessions.warmupDone(state)) }
                else -> {
                    val ex = exercise!!
                    val es = state.exerciseState(ex.id) ?: Progression.initial(ex, day)
                    lastMessage?.let { PipSays(Mood.HAPPY, it, size = 48.dp) }
                    key(ex.id, position) {
                        ExerciseView(ex, es, position, planned, history.filter { it.exerciseId == ex.id && it.day != day }) { sets, effort, unclear ->
                            log(ex, es, sets, effort, unclear)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WarmupView(moves: List<WarmupMove>, onDoneClick: () -> Unit) {
    val onDone by rememberUpdatedState(onDoneClick)
    var index by remember { mutableIntStateOf(-1) }
    var left by remember { mutableIntStateOf(0) }
    LaunchedEffect(index) {
        if (index !in moves.indices) return@LaunchedEffect
        left = moves[index].seconds
        while (left > 0) {
            delay(1000)
            left--
            if (left in 1..2) beep(short = true)
        }
        beep()
        if (index < moves.lastIndex) index++ else onDone()
    }
    PipSays(Mood.CALM, "Two minutes of warm-up first. Your joints will thank you.")
    Text("Warm-up", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            moves.forEachIndexed { i, m ->
                val current = i == index
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        m.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        color = if (i < index) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (current) "$left s" else "${m.seconds} s", color = if (current) Good else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
    if (index < 0) {
        Button(onClick = { index = 0 }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Start the warm-up timer") }
    }
    OutlinedButton(onClick = { onDone() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text(if (index < 0) "Already warm, skip it" else "Done, let's train")
    }
}

@Composable
private fun ExerciseView(
    ex: Exercise,
    es: ExerciseState,
    position: Int,
    planned: Int,
    previous: List<WorkoutRecord>,
    onLog: (List<Int>, Effort, Boolean) -> Unit,
) {
    val v = ex.variation(es.variation)
    val sets = remember(ex.id) { mutableStateListOf<Int>().apply { repeat(es.sets) { add(es.target) } } }
    var unclear by remember(ex.id) { mutableStateOf(false) }
    val side = if (ex.perSide) " per side" else ""

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${ex.group.short} · exercise ${position + 1} of $planned",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        if (es.times == 0) {
            Surface(color = Good, shape = RoundedCornerShape(6.dp)) {
                Text(
                    "NEW",
                    color = Color(0xFF0B1B3A),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
    if (es.times == 0) PipSays(Mood.CALM, Mascot.newExercise(), size = 48.dp)
    Text(v.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    if (v.name != ex.title) Text(ex.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.outline)
    Figures.all[ex.figure]?.let { StickFigure(it, Modifier.fillMaxWidth().height(190.dp)) }
    Text(v.how, style = MaterialTheme.typography.bodyLarge)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Form", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            (ex.cues + v.cues).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            ex.equipment?.let { Text("Needs: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${es.sets} sets × ${es.target} ${ex.unit}$side",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                if (ex.measure == Measure.REPS) {
                    "Rest ${ex.rest} s between sets. End each set when you could still do 1 or 2 more good reps, not at total failure."
                } else {
                    "Rest ${ex.rest} s between sets. Hold for the target time; stop early if your form breaks, and log what you held."
                },
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }

    LastTime(ex, es, previous)
    Text(
        if (ex.measure == Measure.REPS) "Sets: adjust each to what you did" else "Sets: set the time, then tap Start",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    sets.forEachIndexed { i, value ->
        if (i > 0) RestRow(ex.rest)
        SetRow(i + 1, value, ex.measure, step = if (ex.measure == Measure.REPS) 1 else 5, onChange = { sets[i] = it })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = unclear, onCheckedChange = { unclear = it })
        Text("The instructions were unclear", style = MaterialTheme.typography.bodyMedium)
    }
    Text("How did it feel?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EffortButton("Easy", Good, Modifier.weight(1f)) { onLog(sets.toList(), Effort.EASY, unclear) }
        EffortButton("OK", Brand, Modifier.weight(1f)) { onLog(sets.toList(), Effort.OK, unclear) }
        EffortButton("Hard", Color(0xFFFFB066), Modifier.weight(1f)) { onLog(sets.toList(), Effort.HARD, unclear) }
    }
    OutlinedButton(onClick = { onLog(emptyList(), Effort.SKIPPED, unclear) }, modifier = Modifier.fillMaxWidth()) {
        Text("Skip / couldn't do it")
    }
    Text(
        "Stop if anything hurts. Burning muscles are fine; joint or sharp pain is not.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(24.dp))
}

/** One set: a stepper for reps, or for seconds with a countdown that beeps 2 and 1 seconds before the end and at the end. */
@Composable
private fun SetRow(number: Int, value: Int, measure: Measure, step: Int, onChange: (Int) -> Unit) {
    var running by remember { mutableStateOf(false) }
    var left by remember { mutableIntStateOf(0) }
    val change by rememberUpdatedState(onChange)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        left = value
        while (left > 0) {
            delay(1000)
            left--
            if (left in 1..2) beep(short = true)
        }
        beep(short = false)
        running = false
    }
    StepCard(highlight = running) {
        Text("Set $number", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(56.dp))
        FilledTonalButton(onClick = { onChange((value - step).coerceAtLeast(0)) }, enabled = !running) { Text("−") }
        Text(
            if (running) "$left s" else if (measure == Measure.REPS) "$value" else "$value s",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = if (running) Good else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(76.dp),
        )
        FilledTonalButton(onClick = { onChange(value + step) }, enabled = !running) { Text("+") }
        if (measure == Measure.SECONDS) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                if (running) {
                    // Stopped early: log what was held.
                    change(value - left)
                    running = false
                } else running = true
            }) { Text(if (running) "Stop" else "Start") }
        }
    }
}

/** The rest between two sets, shown like a set, with the same countdown beeps. */
@Composable
private fun RestRow(seconds: Int) {
    var running by remember { mutableStateOf(false) }
    var left by remember { mutableIntStateOf(seconds) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        left = seconds
        while (left > 0) {
            delay(1000)
            left--
            if (left in 1..2) beep(short = true)
        }
        beep(short = false)
        running = false
    }
    StepCard(highlight = running, rest = true) {
        Text("Rest", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(56.dp))
        Text(
            "${if (running) left else seconds} s",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = if (running) Good else MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = { running = !running; if (!running) left = seconds }) { Text(if (running) "Skip" else "Start") }
    }
}

@Composable
private fun StepCard(highlight: Boolean, rest: Boolean = false, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                highlight -> Good.copy(alpha = 0.18f)
                rest -> MaterialTheme.colorScheme.surfaceContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** What was done the last few times, and why today's target is what it is. */
@Composable
private fun LastTime(ex: Exercise, es: ExerciseState, previous: List<WorkoutRecord>) {
    if (previous.isEmpty()) return
    val format = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")
    val last = previous.last()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Previous sessions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            for (r in previous.takeLast(3).reversed()) {
                Row {
                    Text(
                        java.time.LocalDate.ofEpochDay(r.day).format(format) + " · " + r.variation + ": " +
                            if (r.effort == Effort.SKIPPED) "skipped" else r.done.joinToString(" / ") + " " + ex.unit,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(effortLabel(r.effort), color = effortColor(r.effort), style = MaterialTheme.typography.bodyMedium)
                }
            }
            val now = "${es.sets} × ${es.target} ${ex.unit}"
            val why = when {
                es.variation > last.variationIndex -> "You topped the range last time, so today moves up to ${ex.variation(es.variation).name}."
                es.variation < last.variationIndex -> "Last time was too much, so today steps back to ${ex.variation(es.variation).name}."
                es.sets > last.setsPlanned -> "You topped the hardest version, so today adds a set."
                last.effort == Effort.EASY -> "It felt easy last time, so today's target goes up: $now."
                last.effort == Effort.OK -> "It felt OK last time, so a small step up: $now."
                last.effort == Effort.HARD -> "It felt hard last time, so the target holds or eases: $now."
                else -> "You skipped it last time, so today is easier: $now."
            }
            Text(why, style = MaterialTheme.typography.bodyMedium, color = Brand)
        }
    }
}

private fun effortLabel(e: Effort) = when (e) {
    Effort.EASY -> "Easy"
    Effort.OK -> "OK"
    Effort.HARD -> "Hard"
    Effort.SKIPPED -> "Skipped"
}

private fun effortColor(e: Effort) = when (e) {
    Effort.EASY -> Good
    Effort.OK -> Brand
    Effort.HARD -> Color(0xFFFFB066)
    Effort.SKIPPED -> Bad
}

@Composable
private fun EffortButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color(0xFF0B1B3A)),
    ) { Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun DoneView(session: ActiveSession?, state: AppState, library: Library, onFinish: () -> Unit) {
    val day = today()
    Spacer(Modifier.height(24.dp))
    Text("Workout done", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    PipSays(
        Mood.HAPPY,
        session?.let { Mascot.sessionDone(it, library) } ?: "Nice work.",
        size = 96.dp,
    )
    Text("Streak: ${state.streak(day)} days", style = MaterialTheme.typography.titleMedium)
    Progression.overallLevel(library, state)?.let {
        Text("Overall level: %.2f".format(it), style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(12.dp))
    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Finish") }
}

/** A short beep for "about to end", a longer one at the end. */
private fun beep(short: Boolean = false) {
    runCatching {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, if (short) 150 else 600)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 800)
    }
}
