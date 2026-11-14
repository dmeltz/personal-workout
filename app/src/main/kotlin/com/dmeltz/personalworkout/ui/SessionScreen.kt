package com.dmeltz.personalworkout.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.dmeltz.personalworkout.core.Group
import com.dmeltz.personalworkout.core.ExerciseState
import com.dmeltz.personalworkout.core.Library
import com.dmeltz.personalworkout.core.Mascot
import com.dmeltz.personalworkout.core.Measure
import com.dmeltz.personalworkout.core.Mood
import com.dmeltz.personalworkout.core.Progression
import com.dmeltz.personalworkout.core.SessionBuilder
import com.dmeltz.personalworkout.core.Sessions
import com.dmeltz.personalworkout.core.WarmupMove
import com.dmeltz.personalworkout.core.WorkoutRecord
import com.dmeltz.personalworkout.today
import kotlinx.coroutines.delay

/** Whether the workout is paused. Every countdown stops while it is. */
private val LocalPaused = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/** Waits one second of workout time: time doesn't pass while [paused]. */
private suspend fun tick(paused: State<Boolean>) {
    while (paused.value) delay(200)
    delay(1000)
    while (paused.value) delay(200)
}

@Composable
fun SessionScreen(
    library: Library,
    initialState: AppState,
    /** Every logged exercise before this screen opened, for "last time". */
    history: List<WorkoutRecord>,
    onState: (AppState) -> Unit,
    onRecord: (WorkoutRecord) -> Unit,
    /** A logged exercise corrected on the summary, or done again after Back. */
    onRecordChanged: (WorkoutRecord) -> Unit,
    /** Log records cleared by restarting the workout. */
    onRecordsDeleted: (List<WorkoutRecord>) -> Unit,
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
    // This session's log records by exercise, including those from before the screen opened, so an
    // exercise done again after Back replaces its record.
    val records = remember {
        mutableStateMapOf<String, WorkoutRecord>().apply {
            val s = initialState.activeSession ?: return@apply
            for (r in history) if (r.day == s.day && r.session == s.number) put(r.exerciseId, r)
        }
    }
    val speaker = rememberSpeaker()
    val paused = remember { mutableStateOf(false) }
    // "Start over" runs the current exercise again from its first set.
    var restarts by remember { mutableIntStateOf(0) }

    // The overall clock: from "Start the workout" (or skipping the warm-up) until it is finished.
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

    fun log(ex: Exercise, sets: List<Int>, effort: Effort) {
        val current = state.activeSession ?: return
        val before = current.logged(ex.id)?.before ?: state.exerciseState(ex.id) ?: state.initialState(ex, day)
        val earlier = records[ex.id]
        val next = Sessions.log(state, ex, sets, effort, day)
        val after = next.exerciseState(ex.id) ?: return
        val record = WorkoutRecord(
                timestamp = earlier?.timestamp ?: System.currentTimeMillis(),
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
        if (earlier != null) onRecordChanged(record) else onRecord(record)
        records[ex.id] = record
        lastMessage = if (after.variation > before.variation) {
            "Level up! Next time: ${ex.variation(after.variation).name}."
        } else Mascot.logged(effort, System.currentTimeMillis())
        update(next)
    }

    // The summary's log: each exercise logged in the finished session, in workout order.
    val logged: List<Logged> = finished?.let { f ->
        f.exerciseIds.mapNotNull { id ->
            val u = f.logged(id) ?: return@mapNotNull null
            val ex = library.exercise(id) ?: return@mapNotNull null
            val r = records[id] ?: return@mapNotNull null
            Logged(ex, u.before, u.groupDayBefore, r)
        }
    }.orEmpty()

    /** On the summary, re-does the progress for a logged exercise with corrected sets and feeling. */
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
        records[l.ex.id] = record
        onRecordChanged(record)
        update(next)
    }

    val shown = session ?: finished
    // The timer waits until the warm-up starts (or is skipped).
    val startedAt = shown?.startedAt?.takeIf { it > 0 }
    val planned = shown?.plannedCount ?: 0
    // The exercise on screen (all of them once finished). The counter at the top follows it,
    // backwards too: 0 during the warm-up, then its number.
    val position = session?.position ?: planned
    val counter = when {
        session == null || done -> planned
        !session.warmupDone -> 0
        else -> position + 1
    }
    var confirmRestart by remember { mutableStateOf(false) }

    // Each new exercise starts at the top of the screen, with its instructions.
    val scroll = rememberScrollState()
    LaunchedEffect(position, session?.warmupDone, restarts) { scroll.scrollTo(0) }

    Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onQuit) { Icon(Icons.Filled.Close, contentDescription = "Stop") }
            LinearProgressIndicator(
                progress = { if (planned == 0) 1f else (if (done) planned else position).toFloat() / planned },
                modifier = Modifier.weight(1f).height(10.dp),
                color = Good,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
            Text(
                "$counter / $planned",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 10.dp),
            )
            SessionClock(startedAt, endedAt, Modifier.padding(start = 12.dp))
            // Pause and continue: every countdown stops while paused.
            if (!done) {
                TextButton(onClick = {
                    paused.value = !paused.value
                    if (paused.value) speaker.stop()
                }) { Text(if (paused.value) "▶" else "⏸", fontSize = 18.sp) }
            }
            // Read-aloud on or off.
            TextButton(onClick = {
                if (state.readAloud) speaker.stop()
                update(state.copy(readAloud = !state.readAloud))
            }) { Text(if (state.readAloud) "🔊" else "🔇", fontSize = 18.sp) }
        }
        // Your music app's controls; its volume dips by itself while the app speaks.
        if (!done) MusicControls(Modifier.align(Alignment.End).padding(end = 8.dp))
        if (paused.value && !done) {
            // Paused: continue, or move around the workout.
            val inWorkout = session?.warmupDone == true
            val first = position == 0
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { paused.value = false },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Good, contentColor = Color(0xFF0B1B3A)),
                ) { Text("▶  Continue", fontWeight = FontWeight.Bold) }
                if (inWorkout) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                update(Sessions.back(state))
                                lastMessage = null
                                paused.value = false
                            },
                            enabled = !first,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) { Text("⏮ Back", maxLines = 1) }
                        OutlinedButton(
                            onClick = { restarts++; paused.value = false },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) { Text("↺ Start over", maxLines = 1) }
                        OutlinedButton(
                            onClick = {
                                exercise?.let { ex ->
                                    // Not logged yet: the same as "Skip / couldn't do it". Already
                                    // logged (reached with Back): it keeps its result.
                                    if (state.activeSession?.logged(ex.id) == null) log(ex, emptyList(), Effort.SKIPPED)
                                    else update(Sessions.next(state, ex, day))
                                }
                                lastMessage = null
                                paused.value = false
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) { Text("Next ⏭", maxLines = 1) }
                    }
                    Text(
                        "Back: the previous exercise again; doing it replaces its result. " +
                            "Start over: this exercise from its first set. Next: the same as Skip / " +
                            "couldn't do it, or, for an exercise already done, keeps its result.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                OutlinedButton(onClick = { confirmRestart = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("⟲ Restart the whole workout")
                }
            }
        }
        if (confirmRestart) {
            AlertDialog(
                onDismissRequest = { confirmRestart = false },
                title = { Text("Restart the workout?") },
                text = { Text("Everything logged in this workout is cleared, and it starts again from the warm-up with the timer at zero.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmRestart = false
                        speaker.stop()
                        onRecordsDeleted(records.values.toList())
                        records.clear()
                        lastMessage = null
                        restarts++
                        update(Sessions.restart(state))
                        paused.value = false
                    }) { Text("Restart") }
                },
                dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("Cancel") } },
            )
        }
        CompositionLocalProvider(LocalPaused provides paused) { Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                done -> DoneView(finished ?: session, state, library, if (startedAt == null) 0 else ((endedAt ?: startedAt) - startedAt) / 1000, logged, ::correct, onFinished)
                !session!!.warmupDone -> {
                    val todays = session.exerciseIds.mapNotNull { library.exercise(it) }
                    TodaysPlan(todays, library.warmup, state, day)
                    TodaysMuscles(todays)
                    key(restarts) {
                        WarmupView(
                            library.warmup,
                            speak = { if (state.readAloud) speaker.say(it) },
                            onStart = { update(Sessions.begin(state)) },
                        ) { update(Sessions.warmupDone(state)) }
                    }
                }
                else -> {
                    val ex = exercise!!
                    // An exercise reached with Back starts again from its state before its log.
                    val earlier = session.logged(ex.id)
                    val es = earlier?.before ?: state.exerciseState(ex.id) ?: state.initialState(ex, day)
                    lastMessage?.let { PipSays(Mood.HAPPY, it, size = 48.dp) }
                    if (earlier != null) {
                        Text(
                            "Done already (${earlier.effort?.let(::effortLabel)?.lowercase() ?: "logged"}). Doing it again replaces that result; Next keeps it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    key(ex.id, position, restarts) {
                        ExerciseView(
                            ex, es, position, planned, history.filter { it.exerciseId == ex.id && it.day != day },
                            again = earlier != null,
                            readAloud = state.readAloud,
                            speak = speaker::say,
                            cue = speaker::interject,
                        ) { sets, effort ->
                            speaker.stop()
                            log(ex, sets, effort)
                        }
                    }
                }
            }
        } }
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
        }
    }
}

/** Before the warm-up: today's exercises in order, with about how long each one takes. */
@Composable
private fun TodaysPlan(exercises: List<Exercise>, warmup: List<WarmupMove>, state: AppState, day: Day) {
    val rows = exercises.map { ex ->
        val es = state.exerciseState(ex.id) ?: state.initialState(ex, day)
        Triple(ex, es, SessionBuilder.seconds(ex, es))
    }
    val warmupSeconds = warmup.sumOf { it.seconds }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Today's plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "About ${aboutMinutes(warmupSeconds + rows.sumOf { it.third })}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Good,
                )
            }
            PlanRow("Warm-up", "${warmup.size} moves", warmupSeconds)
            rows.forEachIndexed { i, (ex, es, seconds) ->
                if (ex.group == Group.FLEXIBILITY && rows.getOrNull(i - 1)?.first?.group != Group.FLEXIBILITY) {
                    Text("Cooldown stretches", style = MaterialTheme.typography.labelLarge, color = groupColors.getValue(Group.FLEXIBILITY))
                }
                val amount = if (es.sets == 1) ex.amount(es.variation, es.target) + " hold" else "${es.sets} × ${ex.amount(es.variation, es.target)}"
                PlanRow("${i + 1}. ${ex.variation(es.variation).name}", amount, seconds)
            }
            Text(
                "Times include the rests between sets. Once the warm-up is done, the workout runs by itself. ⏸ at the top pauses it, and from there you can go back, start over or skip ahead. Play music from any app: it gets quieter while I speak, and the Music buttons skip or pause it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun PlanRow(name: String, detail: String, seconds: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Text(aboutMinutes(seconds), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Seconds as minutes, to the nearest half: "2½ min". */
private fun aboutMinutes(seconds: Int): String {
    val halves = ((seconds + 15) / 30).coerceAtLeast(1)
    val whole = if (halves / 2 > 0) "${halves / 2}" else ""
    return whole + (if (halves % 2 == 1) "½" else "") + " min"
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
private fun WarmupView(moves: List<WarmupMove>, speak: (String) -> Unit, onStart: () -> Unit, onDoneClick: () -> Unit) {
    val paused = LocalPaused.current
    val onDone by rememberUpdatedState(onDoneClick)
    var index by remember { mutableIntStateOf(-1) }
    var left by remember { mutableIntStateOf(0) }
    // Each move's name is read aloud as it starts, so the phone can stay on the grass.
    LaunchedEffect(index) {
        if (index !in moves.indices) return@LaunchedEffect
        left = moves[index].seconds
        speak(moves[index].name)
        while (left > 0) {
            tick(paused)
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
        Button(onClick = { onStart(); index = 0 }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Start the workout") }
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
    /** Done again after Back. */
    again: Boolean = false,
    readAloud: Boolean,
    speak: (String) -> Unit,
    /** Reads a short cue now; instructions still being read carry on after it. */
    cue: (String) -> Unit,
    onLog: (List<Int>, Effort) -> Unit,
) {
    val v = ex.variation(es.variation)
    // The instructions are read aloud as the exercise comes up, with the form cues the first time
    // at this variation.
    val spoken = remember(ex.id, es.variation) { instructions(ex, es, previous.none { it.variationIndex == es.variation }) }
    LaunchedEffect(ex.id, es.variation) {
        val intro = when {
            again -> "Again. "
            ex.group == Group.FLEXIBILITY -> "Cooldown stretch. "
            position > 0 -> "Moving to the next exercise. "
            else -> ""
        }
        if (readAloud) speak(intro + spoken)
    }
    val sets = remember(ex.id) { mutableStateListOf<Int>().apply { repeat(es.sets) { add(es.target) } } }
    // The exercise runs by itself: time to read the instructions, then each set and rest in turn,
    // then how it felt. Even steps are sets, odd ones the rests between them.
    var step by remember(ex.id) { mutableIntStateOf(INTRO) }
    val feelStep = 2 * es.sets - 1
    // About 5 seconds to say how it felt; with no answer it is logged as OK and the workout moves on.
    val paused = LocalPaused.current
    var feelLeft by remember(ex.id) { mutableIntStateOf(FEEL_SECONDS) }
    val log by rememberUpdatedState(onLog)
    LaunchedEffect(step == feelStep) {
        if (step != feelStep) return@LaunchedEffect
        feelLeft = FEEL_SECONDS
        while (feelLeft > 0) { tick(paused); feelLeft-- }
        log(sets.toList(), Effort.OK)
    }
    LaunchedEffect(step) {
        if (!readAloud) return@LaunchedEffect
        when {
            step == feelStep -> cue("Feedback")
            step >= 0 && step % 2 == 0 -> cue("Start set ${step / 2 + 1}")
            step > 0 -> cue("Rest")
        }
    }

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
    if (step == INTRO) IntroCountdown { step = 0 }
    ex.figureAt(es.variation).let { name -> key(name) { FigureViews(name, 190.dp) } }
    // How to do it and the form cues, right under the figure.
    Text(v.how, style = MaterialTheme.typography.bodyLarge)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Form", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            (ex.cues + v.cues).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            ex.equipment?.let { Text("Needs: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
    }
    TextButton(onClick = { speak(spoken) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Text("🔊 Read the instructions aloud")
    }
    ExerciseMuscles(ex)
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
        if (ex.measure == Measure.REPS) "Sets: tap Done after each one" else "Sets: each hold starts by itself",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(
        if (ex.measure == Measure.REPS) "Change a number if you did more or fewer reps." else "Stop a hold early if your form breaks; what you held is logged.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    sets.forEachIndexed { i, value ->
        if (i > 0) RestRow(ex.rest, active = step == 2 * i - 1) { step = 2 * i }
        SetRow(
            i + 1, value, ex.measure,
            step = if (ex.measure == Measure.REPS) 1 else 5,
            perSide = ex.perSide(es.variation),
            active = step == 2 * i,
            onStart = { step = 2 * i },
            onEnd = { step = 2 * i + 1 },
            onChange = { sets[i] = it },
        )
    }
    Text(
        "How did it feel?",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = if (step == feelStep) Good else MaterialTheme.colorScheme.onSurface,
    )
    // Under each button, what it does to next time, worked out from the sets as logged now.
    fun nextTime(effort: Effort): String {
        val done = if (effort == Effort.SKIPPED) emptyList() else sets.toList()
        val next = Progression.apply(ex, es, done, effort, es.lastDone)
        val amount = ex.amount(next.variation, next.target)
        return when {
            next.variation > es.variation -> "Level up! Next: ${ex.variation(next.variation).name}, $amount"
            next.variation < es.variation -> "Back to ${ex.variation(next.variation).name}, $amount"
            next.target > es.target -> "Next time up to $amount"
            next.target < es.target -> "Next time down to $amount"
            else -> "Next time the same, $amount"
        }
    }
    Row(Modifier.fillMaxWidth().showWhen(step == feelStep), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, color, effort) in listOf(
            Triple("Easy", Good, Effort.EASY),
            Triple("OK", Brand, Effort.OK),
            Triple("Hard", Color(0xFFFFB066), Effort.HARD),
        )) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                EffortButton(label, color, Modifier.fillMaxWidth()) { onLog(sets.toList(), effort) }
                Text(
                    nextTime(effort),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    if (step == feelStep) {
        Text(
            "Tap how it felt. Moving on as OK in $feelLeft s.",
            style = MaterialTheme.typography.bodyMedium,
            color = Good,
        )
    }
    OutlinedButton(onClick = { onLog(emptyList(), Effort.SKIPPED) }, modifier = Modifier.fillMaxWidth()) {
        Text("Skip / couldn't do it")
    }
    Text(
        "Skip: ${nextTime(Effort.SKIPPED).replaceFirstChar { it.lowercase() }}. Easy moves you up faster, OK a little, Hard keeps the target where you reached.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Text(
        "Stop if anything hurts. Burning muscles are fine; joint or sharp pain is not.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(24.dp))
}

/** Seconds to read the instructions before the first set. */
private const val INTRO_SECONDS = 10
private const val INTRO = -1
/** Seconds to say how an exercise felt before it is logged as OK. */
private const val FEEL_SECONDS = 5

/**
 * About 10 seconds to read the instructions, then the first set starts. Instructions still being
 * read aloud carry on during the set.
 */
@Composable
private fun IntroCountdown(onDoneClick: () -> Unit) {
    val paused = LocalPaused.current
    val onDone by rememberUpdatedState(onDoneClick)
    var left by remember { mutableIntStateOf(INTRO_SECONDS) }
    LaunchedEffect(Unit) {
        while (left > 0) { tick(paused); left-- }
        beep()
        onDone()
    }
    StepCard(highlight = true) {
        Text(
            "Read the instructions. Set 1 starts in $left s",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        TextButton(onClick = { onDone() }) { Text("Start now") }
    }
}

/** Scrolls this into view when [active] turns true. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.showWhen(active: Boolean): Modifier {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(active) { if (active) requester.bringIntoView() }
    return bringIntoViewRequester(requester)
}

/**
 * One set: a stepper for reps, or for seconds with a countdown that beeps 2 and 1 seconds before the
 * end and at the end. While [active], a hold counts down by itself and a set of reps waits for Done;
 * either way [onEnd] moves on.
 */
@Composable
private fun SetRow(
    number: Int,
    value: Int,
    measure: Measure,
    step: Int,
    perSide: Boolean,
    active: Boolean,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onChange: (Int) -> Unit,
) {
    val running = active && measure == Measure.SECONDS
    val paused = LocalPaused.current
    var left by remember { mutableIntStateOf(0) }
    // For a timed hold on each side: 1 while on the first side, 2 on the second.
    var side by remember { mutableIntStateOf(1) }
    val change by rememberUpdatedState(onChange)
    val end by rememberUpdatedState(onEnd)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val sides = if (perSide) 2 else 1
        for (s in 1..sides) {
            side = s
            left = value
            while (left > 0) {
                tick(paused)
                left--
                if (left in 1..2) beep(short = true)
            }
            beep(short = false)
            if (s < sides) {
                // A few seconds to switch sides before the second hold.
                left = 5
                side = 0
                while (left > 0) { tick(paused); left-- }
            }
        }
        side = 1
        end()
    }
    StepCard(highlight = active, modifier = Modifier.showWhen(active)) {
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
        Spacer(Modifier.weight(1f))
        if (measure == Measure.SECONDS) {
            TextButton(onClick = {
                if (running) {
                    // Stopped early: log what was held (the full time if it stopped while switching sides).
                    change(if (side == 0) value else value - left)
                    side = 1
                    onEnd()
                } else onStart()
            }) { Text(if (running) "Stop" else "Start") }
        } else if (active) {
            Button(onClick = onEnd, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { Text("Done") }
        }
    }
}

/** The rest between two sets, shown like a set, with the same countdown beeps. It runs while [active]. */
@Composable
private fun RestRow(seconds: Int, active: Boolean, onEnd: () -> Unit) {
    val paused = LocalPaused.current
    val running = active
    val end by rememberUpdatedState(onEnd)
    var left by remember { mutableIntStateOf(seconds) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        left = seconds
        while (left > 0) {
            tick(paused)
            left--
            if (left in 1..2) beep(short = true)
        }
        beep(short = false)
        end()
    }
    StepCard(highlight = running, rest = true, modifier = Modifier.showWhen(active)) {
        Text("Rest", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(56.dp))
        Text(
            "${if (running) left else seconds} s",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = if (running) Good else MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        if (running) TextButton(onClick = onEnd) { Text("Skip") } else Spacer(Modifier.width(64.dp))
    }
}

@Composable
private fun StepCard(
    highlight: Boolean,
    rest: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
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
                last.effort == Effort.EASY -> "It felt easy last time, so today's target goes up: $now."
                last.effort == Effort.OK -> "It felt OK last time, so a small step up: $now."
                last.effort == Effort.HARD -> "It felt hard last time, so the target holds or eases: $now."
                else -> "You skipped it last time, so today is easier: $now."
            }
            Text(why, style = MaterialTheme.typography.bodyMedium, color = Brand)
        }
    }
}

internal fun effortLabel(e: Effort) = when (e) {
    Effort.EASY -> "Easy"
    Effort.OK -> "OK"
    Effort.HARD -> "Hard"
    Effort.SKIPPED -> "Skipped"
}

internal fun effortColor(e: Effort) = when (e) {
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

/**
 * The time since [startedAt], ticking each second until [endedAt]; 0:00 until the workout starts.
 * It owns its ticker so the rest of the screen doesn't redraw every second.
 */
@Composable
private fun SessionClock(startedAt: Long?, endedAt: Long?, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endedAt) {
        while (endedAt == null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Text(
        clock(if (startedAt == null) 0 else ((endedAt ?: now) - startedAt).coerceAtLeast(0) / 1000),
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
        logged.forEachIndexed { i, l -> key(l.ex.id) { LoggedRow(l.ex, l.record) { sets, effort -> onCorrect(i, sets, effort) } } }
    }
    Spacer(Modifier.height(12.dp))
    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Finish") }
}

/** One logged exercise on the summary, with an editor for its sets and how it felt. */
@Composable
fun LoggedRow(ex: Exercise, r: WorkoutRecord, title: String = r.variation, onDelete: (() -> Unit)? = null, onSave: (List<Int>, Effort) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val sets = remember(editing) {
        mutableStateListOf<Int>().apply { addAll(r.done.ifEmpty { List(r.setsPlanned) { r.target } }) }
    }
    var effort by remember(editing) { mutableStateOf(r.effort) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
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
                if (onDelete != null) {
                    TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete this exercise from the log", color = Bad) }
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
