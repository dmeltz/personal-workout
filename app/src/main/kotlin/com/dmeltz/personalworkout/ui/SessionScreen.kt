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
import androidx.compose.runtime.mutableLongStateOf
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
import com.dmeltz.personalworkout.core.Day
import com.dmeltz.personalworkout.core.Exercise
import com.dmeltz.personalworkout.core.ExerciseState
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
    /** A logged exercise corrected on the summary. */
    onRecordChanged: (WorkoutRecord) -> Unit,
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
    // Everything logged on this screen, with what is needed to correct it on the summary.
    val logged = remember { mutableStateListOf<Logged>() }
    val speaker = rememberSpeaker()

    // The overall clock: from the session's start (or this screen's, for a session saved before
    // start times were kept) until it is finished.
    val openedAt = remember { System.currentTimeMillis() }
    var endedAt by remember { mutableStateOf<Long?>(null) }

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
            endedAt = System.currentTimeMillis()
            speaker.stop()
            update(Sessions.finish(library, state, day))
        }
    }

    fun log(ex: Exercise, before: ExerciseState, sets: List<Int>, effort: Effort) {
        val current = state.activeSession ?: return
        val next = Sessions.log(state, ex, sets, effort, day)
        val after = next.exerciseState(ex.id) ?: return
        val record = WorkoutRecord(
                timestamp = System.currentTimeMillis(),
                day = day,
                session = current.number,
                exerciseId = ex.id,
                exercise = ex.title,
                group = ex.group,
                variationIndex = before.variation,
                variation = ex.variation(before.variation).name,
                measure = ex.measure,
                perSide = ex.perSide(before.variation),
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
            )
        onRecord(record)
        logged += Logged(ex, before, state.groupLastTrained[ex.group.name], record)
        lastMessage = if (after.variation > before.variation) {
            "Level up! Next time: ${ex.variation(after.variation).name}."
        } else Mascot.logged(effort, System.currentTimeMillis())
        update(next)
    }

    /** Re-does the progress for a logged exercise with corrected sets and feeling. */
    fun correct(index: Int, sets: List<Int>, effort: Effort) {
        val l = logged[index]
        val others = logged.any { it !== l && it.ex.group == l.ex.group && it.record.effort != Effort.SKIPPED }
        val next = Sessions.correct(library, state, l.ex, l.before, l.groupDayBefore, others, sets, effort, day)
        val after = next.exerciseState(l.ex.id) ?: return
        val record = l.record.copy(
            done = if (effort == Effort.SKIPPED) emptyList() else sets,
            effort = effort,
            levelAfter = Progression.level(l.ex, after),
            nextVariation = l.ex.variation(after.variation).name,
            nextTarget = after.target,
            nextSets = after.sets,
        )
        logged[index] = l.copy(record = record)
        onRecordChanged(record)
        update(next)
    }

    val shown = session ?: finished
    val startedAt = shown?.startedAt?.takeIf { it > 0 } ?: openedAt
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
                modifier = Modifier.padding(start = 10.dp),
            )
            SessionClock(startedAt, endedAt, Modifier.padding(start = 12.dp))
            // Read-aloud on or off.
            TextButton(onClick = {
                if (state.readAloud) speaker.stop()
                update(state.copy(readAloud = !state.readAloud))
            }) { Text(if (state.readAloud) "🔊" else "🔇", fontSize = 18.sp) }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                done -> DoneView(finished ?: session, state, library, ((endedAt ?: startedAt) - startedAt) / 1000, logged, ::correct, onFinished)
                !session!!.warmupDone -> {
                    TodaysMuscles(session.exerciseIds.mapNotNull { library.exercise(it) })
                    WarmupView(library.warmup, speak = { if (state.readAloud) speaker.say(it) }) { update(Sessions.warmupDone(state)) }
                }
                else -> {
                    val ex = exercise!!
                    val es = state.exerciseState(ex.id) ?: Progression.initial(ex, day)
                    lastMessage?.let { PipSays(Mood.HAPPY, it, size = 48.dp) }
                    key(ex.id, position) {
                        ExerciseView(
                            ex, es, position, planned, history.filter { it.exerciseId == ex.id && it.day != day },
                            readAloud = state.readAloud,
                            speak = speaker::say,
                        ) { sets, effort ->
                            speaker.stop()
                            log(ex, es, sets, effort)
                        }
                    }
                }
            }
        }
    }
}

/** Before the warm-up: the muscles today's session will work, on a body map. */
@Composable
private fun TodaysMuscles(exercises: List<Exercise>) {
    val muscles = Progression.musclesOf(exercises)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Today's muscles", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            BodyMap(highlight(muscles), height = 170.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(PrimaryMuscle, "Main work")
                LegendDot(SecondaryMuscle, "Also used")
            }
            Text(
                exercises.joinToString(" · ") { it.title },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** The muscles one exercise works, as a small body map and a line of text. */
@Composable
fun ExerciseMuscles(ex: Exercise) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BodyMap(highlight(Progression.musclesOf(listOf(ex))), height = 120.dp)
            Text(musclesText(ex.primary, ex.secondary), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WarmupView(moves: List<WarmupMove>, speak: (String) -> Unit, onDoneClick: () -> Unit) {
    val onDone by rememberUpdatedState(onDoneClick)
    var index by remember { mutableIntStateOf(-1) }
    var left by remember { mutableIntStateOf(0) }
    // Each move's name is read aloud as it starts, so the phone can stay on the grass.
    LaunchedEffect(index) {
        if (index !in moves.indices) return@LaunchedEffect
        left = moves[index].seconds
        speak(moves[index].name)
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
    // The current move's figure (the first one before the timer starts).
    val shownMove = moves.getOrNull(index.coerceAtLeast(0))
    shownMove?.figure?.let { name ->
        key(name) { FigureViews(name, 170.dp) }
        Text(shownMove.name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
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
    readAloud: Boolean,
    speak: (String) -> Unit,
    onLog: (List<Int>, Effort) -> Unit,
) {
    val v = ex.variation(es.variation)
    // The instructions are read aloud as the exercise comes up, with the form cues the first time
    // at this variation.
    val spoken = remember(ex.id, es.variation) { instructions(ex, es, previous.none { it.variationIndex == es.variation }) }
    LaunchedEffect(ex.id, es.variation) { if (readAloud) speak(spoken) }
    val sets = remember(ex.id) { mutableStateListOf<Int>().apply { repeat(es.sets) { add(es.target) } } }

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
    ex.figureAt(es.variation).let { name -> key(name) { FigureViews(name, 190.dp) } }
    Text(v.how, style = MaterialTheme.typography.bodyLarge)
    TextButton(onClick = { speak(spoken) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Text("🔊 Read the instructions aloud")
    }
    ExerciseMuscles(ex)
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
                "${es.sets} sets × ${ex.amount(es.variation, es.target)}",
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
            ex.sidesHow(es.variation, es.target)?.let {
                Text(
                    "Both sides: $it Log the number per side.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
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
        SetRow(i + 1, value, ex.measure, step = if (ex.measure == Measure.REPS) 1 else 5, perSide = ex.perSide(es.variation), onChange = { sets[i] = it })
    }
    Text("How did it feel?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EffortButton("Easy", Good, Modifier.weight(1f)) { onLog(sets.toList(), Effort.EASY) }
        EffortButton("OK", Brand, Modifier.weight(1f)) { onLog(sets.toList(), Effort.OK) }
        EffortButton("Hard", Color(0xFFFFB066), Modifier.weight(1f)) { onLog(sets.toList(), Effort.HARD) }
    }
    OutlinedButton(onClick = { onLog(emptyList(), Effort.SKIPPED) }, modifier = Modifier.fillMaxWidth()) {
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
private fun SetRow(number: Int, value: Int, measure: Measure, step: Int, perSide: Boolean, onChange: (Int) -> Unit) {
    var running by remember { mutableStateOf(false) }
    var left by remember { mutableIntStateOf(0) }
    // For a timed hold on each side: 1 while on the first side, 2 on the second.
    var side by remember { mutableIntStateOf(1) }
    val change by rememberUpdatedState(onChange)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val sides = if (perSide) 2 else 1
        for (s in 1..sides) {
            side = s
            left = value
            while (left > 0) {
                delay(1000)
                left--
                if (left in 1..2) beep(short = true)
            }
            beep(short = false)
            if (s < sides) {
                // A few seconds to switch sides before the second hold.
                left = 5
                side = 0
                while (left > 0) { delay(1000); left-- }
            }
        }
        side = 1
        running = false
    }
    StepCard(highlight = running) {
        Column(Modifier.width(64.dp)) {
            Text("Set $number", style = MaterialTheme.typography.bodyLarge)
            if (perSide) {
                Text(
                    when {
                        !running -> "per side"
                        side == 0 -> "switch!"
                        else -> "side $side of 2"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (running) Good else MaterialTheme.colorScheme.outline,
                )
            }
        }
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
                    // Stopped early: log what was held (the full time if it stopped while switching sides).
                    change(if (side == 0) value else value - left)
                    running = false
                    side = 1
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
                            if (r.effort == Effort.SKIPPED) "skipped" else r.done.joinToString(" / ") + " " + ex.unit + if (r.perSide) " per side" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(effortLabel(r.effort), color = effortColor(r.effort), style = MaterialTheme.typography.bodyMedium)
                }
            }
            val now = "${es.sets} × ${ex.amount(es.variation, es.target)}"
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

/** A logged exercise, with its state and its group's last training day from before the log. */
private data class Logged(val ex: Exercise, val before: ExerciseState, val groupDayBefore: Day?, val record: WorkoutRecord)

/** The time since [startedAt], ticking each second until [endedAt]. It owns its ticker so the rest of the screen doesn't redraw every second. */
@Composable
private fun SessionClock(startedAt: Long, endedAt: Long?, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endedAt) {
        while (endedAt == null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Text(
        clock(((endedAt ?: now) - startedAt).coerceAtLeast(0) / 1000),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = if (endedAt == null) MaterialTheme.colorScheme.onSurface else Good,
        modifier = modifier,
    )
}

/** Elapsed seconds as m:ss, or h:mm:ss past an hour. */
private fun clock(seconds: Long): String {
    val h = seconds / 3600; val m = seconds / 60 % 60; val sec = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** What is read aloud when an exercise comes up. */
private fun instructions(ex: Exercise, es: ExerciseState, withCues: Boolean): String = buildString {
    val v = ex.variation(es.variation)
    append(v.name).append(". ")
    append(v.how).append(' ')
    append("${es.sets} sets of ${ex.amount(es.variation, es.target)}. ")
    ex.sidesHow(es.variation, es.target)?.let { append(it).append(' ') }
    if (withCues) (ex.cues + v.cues).forEach { append(it).append(' ') }
}

@Composable
private fun DoneView(
    session: ActiveSession?,
    state: AppState,
    library: Library,
    elapsed: Long,
    logged: List<Logged>,
    onCorrect: (Int, List<Int>, Effort) -> Unit,
    onFinish: () -> Unit,
) {
    val day = today()
    Spacer(Modifier.height(24.dp))
    Text("Workout done", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    PipSays(
        Mood.HAPPY,
        session?.let { Mascot.sessionDone(it, library) } ?: "Nice work.",
        size = 96.dp,
    )
    Text("Time: ${clock(elapsed)}", style = MaterialTheme.typography.titleMedium)
    Text("Streak: ${state.streak(day)} days", style = MaterialTheme.typography.titleMedium)
    Progression.overallLevel(library, state)?.let {
        Text("Overall level: %.2f".format(it), style = MaterialTheme.typography.titleMedium)
    }
    if (logged.isNotEmpty()) {
        Text("Your log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Logged something wrong? Tap Edit to fix the numbers or how it felt. The next targets update to match.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        logged.forEachIndexed { i, l -> key(l.ex.id) { LoggedRow(l) { sets, effort -> onCorrect(i, sets, effort) } } }
    }
    Spacer(Modifier.height(12.dp))
    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Finish") }
}

/** One logged exercise on the summary, with an editor for its sets and how it felt. */
@Composable
private fun LoggedRow(l: Logged, onSave: (List<Int>, Effort) -> Unit) {
    val ex = l.ex
    val r = l.record
    var editing by remember { mutableStateOf(false) }
    val sets = remember(editing) {
        mutableStateListOf<Int>().apply { addAll(r.done.ifEmpty { List(r.setsPlanned) { r.target } }) }
    }
    var effort by remember(editing) { mutableStateOf(r.effort) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.variation, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (r.effort == Effort.SKIPPED) "Skipped"
                        else r.done.joinToString(" / ") + " " + ex.unit + if (r.perSide) " per side" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(effortLabel(r.effort), color = effortColor(r.effort), style = MaterialTheme.typography.bodyMedium)
                if (!editing) TextButton(onClick = { editing = true }) { Text("Edit") }
            }
            Text(
                "Next time: ${r.nextVariation}, ${r.nextSets} × ${r.nextTarget} ${ex.unit}" + if (r.perSide) " per side" else "",
                style = MaterialTheme.typography.bodySmall,
                color = Brand,
            )
            if (editing) {
                if (effort != Effort.SKIPPED) {
                    val step = if (ex.measure == Measure.REPS) 1 else 5
                    sets.forEachIndexed { i, value ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Set ${i + 1}", modifier = Modifier.width(56.dp))
                            FilledTonalButton(onClick = { sets[i] = (value - step).coerceAtLeast(0) }) { Text("−") }
                            Text(
                                if (ex.measure == Measure.REPS) "$value" else "$value s",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(72.dp),
                            )
                            FilledTonalButton(onClick = { sets[i] = value + step }) { Text("+") }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (e in Effort.entries) {
                        val selected = e == effort
                        if (selected) {
                            Button(
                                onClick = {},
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = effortColor(e), contentColor = Color(0xFF0B1B3A)),
                            ) { Text(effortLabel(e), fontSize = 13.sp) }
                        } else {
                            OutlinedButton(
                                onClick = { effort = e },
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            ) { Text(effortLabel(e), fontSize = 13.sp) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editing = false }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = { onSave(sets.toList(), effort); editing = false }, modifier = Modifier.weight(1f)) { Text("Save") }
                }
            }
        }
    }
}

/** A short beep for "about to end", a longer one at the end. */
private fun beep(short: Boolean = false) {
    runCatching {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, if (short) 150 else 600)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 800)
    }
}
