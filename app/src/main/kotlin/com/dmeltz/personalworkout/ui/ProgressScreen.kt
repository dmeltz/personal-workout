package com.dmeltz.personalworkout.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.layout.size
import com.dmeltz.personalworkout.core.Figures
import com.dmeltz.personalworkout.core.views
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dmeltz.personalworkout.core.AppState
import com.dmeltz.personalworkout.core.Body
import com.dmeltz.personalworkout.core.Day
import com.dmeltz.personalworkout.core.Exercise
import com.dmeltz.personalworkout.core.Group
import com.dmeltz.personalworkout.core.Library
import com.dmeltz.personalworkout.core.Mascot
import com.dmeltz.personalworkout.core.Mood
import com.dmeltz.personalworkout.core.Muscle
import com.dmeltz.personalworkout.core.Progression
import com.dmeltz.personalworkout.core.Planner
import com.dmeltz.personalworkout.core.DayKind
import androidx.compose.ui.draw.drawBehind
import com.dmeltz.personalworkout.today
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor

/** One color per muscle group, for the group lines in the overall chart. */
val groupColors = mapOf(
    Group.PUSH to Color(0xFF8FB4FF),
    Group.PULL to Color(0xFF7BD88F),
    Group.LEGS to Color(0xFFFFB066),
    Group.CORE to Color(0xFFD9A6FF),
    Group.CONDITIONING to Color(0xFFFF7A7A),
    Group.FLEXIBILITY to Color(0xFF6FD6D0),
)

@Composable
fun ProgressScreen(
    state: AppState,
    library: Library,
    onState: (AppState) -> Unit,
    onExercise: (String) -> Unit,
    onHistory: () -> Unit,
    onBack: () -> Unit,
) {
    val day = today()
    val overall = Progression.overallLevel(library, state)
    val started = library.exercises.count { state.exerciseState(it.id) != null }
    // One selection links the chart and the body map: a group lights its muscles on the map, and a
    // muscle lights the groups that train it on the chart.
    var selGroup by remember { mutableStateOf<Group?>(null) }
    var selMuscle by remember { mutableStateOf<Muscle?>(null) }
    fun pickGroup(g: Group?) { selGroup = if (g == selGroup) null else g; selMuscle = null }
    fun pickMuscle(m: Muscle?) { selMuscle = if (m == selMuscle) null else m; selGroup = null }
    val litGroups: Set<Group> = selGroup?.let { setOf(it) } ?: selMuscle?.let { groupsTraining(library, it) } ?: emptySet()
    val litMuscles: Set<Muscle> = selMuscle?.let { setOf(it) } ?: selGroup?.let { musclesOf(library, it) } ?: emptySet()
    // The exercises that mainly work the chosen muscle, lit in the list at the bottom.
    val litExercises: Set<Exercise> = selMuscle?.let { m -> library.exercises.filter { m in it.primary }.toSet() } ?: emptySet()
    Page(
        title = "My progress",
        onBack = onBack,
        actions = { TextButton(onClick = onHistory) { Text("Past workouts") } },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                PipSays(
                    if (overall == null) Mood.CALM else Mood.HAPPY,
                    Mascot.progressComment(state, library, day),
                    Modifier.padding(bottom = 12.dp),
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Overall level: ${overall?.let { "%.2f".format(it) } ?: "–"}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "$started of ${library.exercises.size} exercises started. Each exercise's level is its " +
                                "variation (1 = easiest) plus how far you are through that variation's range.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // Lines not in the selection fade.
                        fun faded(c: Color, lit: Boolean) = if (litGroups.isEmpty() || lit) c else c.copy(alpha = 0.2f)
                        val series = buildList {
                            add(LineSeries("Overall", faded(Color.White, false), state.levelHistory.map { it.day to it.overall }))
                            for (g in Group.entries) {
                                val pts = state.levelHistory.mapNotNull { p -> p.groups[g.name]?.let { p.day to it } }
                                if (pts.isNotEmpty()) add(LineSeries(g.short, faded(groupColors.getValue(g), g in litGroups), pts))
                            }
                        }
                        LevelChart(series, Modifier.fillMaxWidth().height(200.dp).padding(top = 12.dp))
                        // The groups, as buttons: tapping one lights its muscles on the body map.
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for (g in Group.entries) {
                                val lit = g in litGroups
                                val color = groupColors.getValue(g)
                                Text(
                                    "● ${g.short} ${Progression.groupLevel(library, state, g)?.let { "%.1f".format(it) } ?: ""}".trim(),
                                    color = if (litGroups.isEmpty() || lit) color else color.copy(alpha = 0.4f),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (lit) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (lit) color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .clickable { pickGroup(g) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                        Text(
                            when {
                                selGroup != null -> "${selGroup!!.label} mainly works: ${musclesOf(library, selGroup!!).joinToString { it.label.lowercase() }}. They're lit on the body map below."
                                selMuscle != null -> "${selMuscle!!.label} is trained by " +
                                    litGroups.joinToString(" and ") { it.label.lowercase() }.ifEmpty { "no exercise yet" } + ". Their lines are lit above."
                                else -> "Tap a group to see its muscles on the body map."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            item { MuscleStatus(state, library, day, selGroup, selMuscle, litMuscles, ::pickMuscle, onExercise) }
            item {
                Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val last = state.weights.lastOrNull()
                        Text(
                            "Weight: ${last?.let { "%.1f kg".format(it.kg) } ?: "–"}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Body.change(state, day, 28)?.let {
                            Text("%+.1f kg over the last 4 weeks.".format(it), style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            "With muscle building, a slow gain (up to about 0.25 kg a week) is expected and fine.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        LevelChart(
                            listOf(LineSeries("Weight", Color(0xFFDCC878), state.weights.map { it.day to it.kg })),
                            Modifier.fillMaxWidth().height(160.dp),
                        )
                        var adding by remember { mutableStateOf(false) }
                        if (adding) {
                            WeightInput(last?.kg) {
                                onState(Body.logWeight(state, day, it))
                                adding = false
                            }
                        } else {
                            TextButton(onClick = { adding = true }) { Text("Add a weigh-in") }
                        }
                    }
                }
            }
            item {
                Text(
                    "Each bar is an exercise's ladder of variations, easiest on the left. The filled part of the " +
                        "current block is how far your target is through that variation's range. Tap an exercise " +
                        "for its full history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            for (group in Group.entries) {
                val exercises = library.inGroup(group)
                item {
                    val level = Progression.groupLevel(library, state, group)
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(group.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(level?.let { "%.2f".format(it) } ?: "", color = groupColors.getValue(group), fontWeight = FontWeight.Bold)
                    }
                }
                items(exercises, key = { it.id }) { ex ->
                    ExerciseRow(ex, state, lit = ex in litExercises) { onExercise(ex.id) }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** The muscles a group's exercises mainly work. */
private fun musclesOf(library: Library, g: Group): Set<Muscle> = library.inGroup(g).flatMap { it.primary }.toSet()

/** The groups with an exercise that mainly works [m]. */
private fun groupsTraining(library: Library, m: Muscle): Set<Group> = Group.entries.filter { m in musclesOf(library, it) }.toSet()

private val MuscleLow = Color(0xFF2E6B57)
private val MuscleHigh = Color(0xFF8CF5B8)

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
)

/** The whole body, each muscle colored by its level: brighter is stronger, grey is not started yet. */
@Composable
private fun MuscleStatus(
    state: AppState,
    library: Library,
    day: Day,
    selGroup: Group?,
    selected: Muscle?,
    lit: Set<Muscle>,
    onPick: (Muscle?) -> Unit,
    onExercise: (String) -> Unit,
) {
    val levels = Muscle.entries.associateWith { Progression.muscleLevel(library, state, it) }
    val known = levels.values.filterNotNull()
    val lo = known.minOrNull() ?: 0.0
    val hi = known.maxOrNull() ?: 0.0
    fun colorOf(level: Double) = lerp(MuscleLow, MuscleHigh, if (hi > lo) ((level - lo) / (hi - lo)).toFloat() else 0.6f)
    val colors = levels.mapNotNull { (m, l) -> l?.let { m to colorOf(it) } }.toMap()
    // Tapping a muscle on the map or a row in the table selects it in both; tapping it again clears it.
    fun lastWorked(m: Muscle) = when (val d = Progression.muscleLastWorked(library, state, m)?.let { day - it }) {
        null -> "never"
        0L -> "today"
        1L -> "yesterday"
        else -> "$d days ago"
    }
    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Body map", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Each muscle's level is the average of the started exercises that mainly work it. " +
                    "Brighter is further along; grey is not started yet.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                selected?.let { m -> "${m.label}: level ${levels[m]?.let { "%.2f".format(it) } ?: "not started yet"} · last worked ${lastWorked(m)}. Its exercises are lit in the list below." }
                    ?: selGroup?.let { g -> "${g.label}: the muscles it mainly works" }
                    ?: "Tap a muscle on the map or in the table.",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (lit.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                color = if (lit.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            )
            BodyMap(
                colors,
                Modifier.padding(vertical = 6.dp),
                height = 200.dp,
                selected = lit,
                onTap = { m -> onPick(if (m == null) selected else m) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(MuscleLow, "Lowest")
                LegendDot(MuscleHigh, "Highest")
                LegendDot(NotStarted, "Not started")
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            MuscleDayGrid(state, library, levels, colors, lit, onPick, onExercise)
        }
    }
}

private val RowHeight = 52.dp
private val NameWidth = 112.dp
private val DayWidth = 56.dp
private val HeaderHeight = 40.dp
private val NotStarted = Color(0xFF55617A)
private val Missed = Color(0xFFFF8A65)
private val StretchColor = Color(0xFF6FD6D0)

/**
 * The muscle table: one row per muscle and one column per day for the next 10 days. A cell shows
 * the planned exercises that mainly work that muscle on that day; tap one to open it. A muscle
 * with no exercise in the 10 days is marked, so nothing gets missed.
 */
@Composable
private fun MuscleDayGrid(
    state: AppState,
    library: Library,
    levels: Map<Muscle, Double?>,
    colors: Map<Muscle, Color>,
    lit: Set<Muscle>,
    onPick: (Muscle?) -> Unit,
    onExercise: (String) -> Unit,
) {
    val plan = remember(state) { Planner.nextDays(library, state, today(), 10) }
    // For each muscle and day, the planned exercises that mainly work it: strength exercises first,
    // then the cooldown stretches that mainly stretch it.
    val cells: Map<Muscle, List<List<Pair<Exercise, Planner.Item>>>> = remember(plan) {
        Muscle.entries.associateWith { m ->
            plan.map { d ->
                d.items.mapNotNull { item -> library.exercise(item.exerciseId)?.takeIf { ex -> m in ex.primary }?.let { ex -> ex to item } }
                    .sortedBy { !it.first.group.strength }
            }
        }
    }
    // Only strength work counts as working a muscle; stretches don't.
    val missed = Muscle.entries.filter { m -> cells.getValue(m).all { day -> day.none { it.first.group.strength } } }
    Text(
        "Next 10 days: tap an exercise to open it. Swipe sideways for more days. A forecast that " +
            "assumes each planned exercise is done in full and rated OK.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(groupColors.getValue(Group.PUSH).copy(alpha = 0.16f)))
            Text(" Strengthening", style = MaterialTheme.typography.labelSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).border(2.dp, StretchColor, RoundedCornerShape(4.dp)))
            Text(" Stretch (cooldown)", style = MaterialTheme.typography.labelSmall, color = StretchColor)
        }
    }
    Text(
        if (missed.isEmpty()) "✓ Every muscle is worked in the next 10 days."
        else "Not worked in the next 10 days: ${missed.joinToString { it.label.lowercase() }}.",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = if (missed.isEmpty()) MuscleHigh else Missed,
    )
    val selectedBg = MaterialTheme.colorScheme.primaryContainer
    val restBg = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        // The muscle names stay put while the days scroll.
        Column(Modifier.width(NameWidth)) {
            Box(Modifier.height(HeaderHeight))
            for (m in Muscle.entries) {
                val isSelected = m in lit
                val isMissed = m in missed
                Row(
                    Modifier.height(RowHeight).fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                        .background(if (isSelected) selectedBg else Color.Transparent)
                        .clickable { onPick(m) }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).background(colors[m] ?: NotStarted, CircleShape))
                    Column(Modifier.padding(start = 6.dp)) {
                        Text(
                            m.label,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isSelected || isMissed) FontWeight.Bold else FontWeight.Normal,
                            color = if (isMissed) Missed else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            lineHeight = MaterialTheme.typography.bodySmall.fontSize,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                levels[m]?.let { "%.1f".format(it) } ?: "–",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            for (g in groupsTraining(library, m).sortedBy { it.ordinal }) {
                                Text(g.short, style = MaterialTheme.typography.labelSmall, color = groupColors.getValue(g))
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            Row(Modifier.height(HeaderHeight)) {
                for (d in plan) {
                    val date = LocalDate.ofEpochDay(d.day)
                    Column(
                        Modifier.width(DayWidth).fillMaxHeight()
                            .background(if (d.kind == DayKind.REST) restBg else Color.Transparent),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            if (d.day == today()) "Today" else date.format(weekday),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            when (d.kind) {
                                DayKind.REST -> "Rest"
                                DayKind.CAPOEIRA -> "Capo."
                                else -> date.format(dayOfMonth)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            @Composable
            fun DayCells(days: List<List<Pair<Exercise, Planner.Item>>>, isSelected: Boolean) {
                Row(
                    Modifier.height(RowHeight)
                        .background(if (isSelected) selectedBg else Color.Transparent)
                        .drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f) },
                ) {
                    days.forEachIndexed { i, here ->
                        Row(
                            Modifier.width(DayWidth).fillMaxHeight()
                                .background(if (plan[i].kind == DayKind.REST && !isSelected) restBg else Color.Transparent),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // One exercise fills the cell; two or more share it.
                            val each = if (here.size <= 1) 46.dp else (DayWidth - 4.dp) / here.size.coerceAtMost(3)
                            for ((ex, item) in here.take(3)) {
                                Figures.views(ex.figureAt(item.variation)).firstOrNull()?.let { f ->
                                    // Strengthening: a tinted tile. Stretch: no fill, a teal outline.
                                    val tile = if (ex.group.strength) {
                                        Modifier.background(groupColors.getValue(ex.group).copy(alpha = 0.16f))
                                    } else {
                                        Modifier.border(2.dp, StretchColor, RoundedCornerShape(6.dp))
                                    }
                                    Box(
                                        Modifier.size(each)
                                            .clip(RoundedCornerShape(6.dp))
                                            .then(tile)
                                            .clickable { onExercise(ex.id) },
                                    ) {
                                        StickFigure(f, Modifier.fillMaxSize(), still = true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            for (m in Muscle.entries) DayCells(cells.getValue(m), m in lit)
        }
    }
}

private val weekday = DateTimeFormatter.ofPattern("EEE")
private val dayOfMonth = DateTimeFormatter.ofPattern("d MMM")

@Composable
private fun ExerciseRow(ex: Exercise, state: AppState, lit: Boolean = false, onClick: () -> Unit) {
    val s = state.exerciseState(ex.id)
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (lit) groupColors.getValue(ex.group).copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The variation you are on (or will start with), as a small still figure.
        Figures.views(ex.figureAt(s?.variation ?: state.startLevels[ex.id]?.variation ?: ex.start)).firstOrNull()?.let { f ->
            StickFigure(f, Modifier.size(52.dp).padding(end = 6.dp), still = true)
        }
        Column(Modifier.weight(1f)) {
            Text(ex.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (s == null) "Not started yet"
                else "${s.variation + 1}/${ex.variations.size} ${ex.variation(s.variation).name} · " +
                    "${s.sets} × ${ex.amount(s.variation, s.target)} " +
                    "(range ${ex.variation(s.variation).min}–${ex.variation(s.variation).max})",
                style = MaterialTheme.typography.bodySmall,
            )
            LadderBar(ex, s, modifier = Modifier.padding(top = 6.dp))
        }
        if (s != null) {
            Text(
                "%.1f".format(Progression.level(ex, s)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = groupColors.getValue(ex.group),
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}

data class LineSeries(val name: String, val color: Color, val points: List<Pair<Day, Double>>)

@Composable
fun Legend(series: List<LineSeries>) {
    if (series.size < 2) return
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (s in series) Text("● ${s.name}", color = s.color, style = MaterialTheme.typography.labelSmall)
    }
}

/** Levels over time. The y axis fits the data, in whole levels. */
@Composable
fun LevelChart(series: List<LineSeries>, modifier: Modifier) {
    val all = series.flatMap { it.points }
    if (all.map { it.first }.distinct().size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "The graph appears after your second entry.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val lo = floor(all.minOf { it.second }).coerceAtLeast(0.0)
    val hi = ceil(all.maxOf { it.second }).coerceAtLeast(lo + 1)
    val first = all.minOf { it.first }
    val last = all.maxOf { it.first }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val format = DateTimeFormatter.ofPattern("d MMM")
    val steps = (hi - lo).toInt().coerceIn(1, 6)
    Column(modifier) {
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(28.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                for (i in steps downTo 0) Text("%.0f".format(lo + (hi - lo) * i / steps), style = labelStyle)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp)) {
                for (i in 0..steps) {
                    val y = size.height * i / steps
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                val span = (last - first).coerceAtLeast(1).toFloat()
                fun at(p: Pair<Day, Double>) = Offset(
                    size.width * (p.first - first) / span,
                    size.height * (1f - ((p.second - lo) / (hi - lo)).toFloat()),
                )
                for (s in series.reversed()) {
                    if (s.points.isEmpty()) continue
                    val main = s === series.first()
                    val path = Path().apply {
                        moveTo(at(s.points[0]).x, at(s.points[0]).y)
                        for (p in s.points.drop(1)) lineTo(at(p).x, at(p).y)
                    }
                    drawPath(path, s.color, style = Stroke(width = (if (main) 3f else 1.5f).dp.toPx()))
                    if (main && s.points.size <= 40) for (p in s.points) drawCircle(s.color, radius = 3.dp.toPx(), center = at(p))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 28.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(LocalDate.ofEpochDay(first).format(format), style = labelStyle)
            Text(LocalDate.ofEpochDay(last).format(format), style = labelStyle)
        }
    }
}
