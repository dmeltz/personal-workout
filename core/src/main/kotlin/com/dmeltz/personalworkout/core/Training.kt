package com.dmeltz.personalworkout.core

import kotlinx.serialization.Serializable

/** Days are counted as epoch days (days since 1970-01-01) in the phone's time zone. */
typealias Day = Long

/** Day of week of an epoch day, 1 = Monday ... 7 = Sunday (1970-01-01 was a Thursday). */
fun weekday(day: Day): Int = (Math.floorMod(day + 3, 7L) + 1).toInt()

enum class Effort { EASY, OK, HARD, SKIPPED }

/** Where one exercise stands: which variation, the per-set target, and how many sets. */
@Serializable
data class ExerciseState(
    val variation: Int,
    val target: Int,
    val sets: Int,
    val introducedOn: Day,
    val lastDone: Day = -1,
    val times: Int = 0,
    /** Logs in a row rated hard or skipped. */
    val struggles: Int = 0,
)

@Serializable
data class LevelPoint(val day: Day, val overall: Double, val groups: Map<String, Double> = emptyMap())

@Serializable
data class AppState(
    val exercises: Map<String, ExerciseState> = emptyMap(),
    /** Days with a finished session, ascending. */
    val sessionDays: List<Day> = emptyList(),
    /** Days marked as capoeira days, ascending. */
    val capoeiraDays: List<Day> = emptyList(),
    /** Last day each muscle group was trained, by [Group] name. */
    val groupLastTrained: Map<String, Day> = emptyMap(),
    /** Overall and per-group level at the end of each training day. */
    val levelHistory: List<LevelPoint> = emptyList(),
    val sessionsCompleted: Int = 0,
    /** Planned rest days, 1 = Monday ... 7 = Sunday. They don't break the streak. */
    val restWeekdays: List<Int> = listOf(6),
    val reminderHour: Int = 19,
    val reminderMinute: Int = 0,
    val reminderEnabled: Boolean = true,
    val safetyAccepted: Boolean = false,
    val activeSession: ActiveSession? = null,
) {
    fun exerciseState(id: String): ExerciseState? = exercises[id]

    fun trainedOn(day: Day): Boolean = sessionDays.binarySearch(day) >= 0

    fun isCapoeira(day: Day): Boolean = capoeiraDays.binarySearch(day) >= 0

    fun isRestDay(day: Day): Boolean = weekday(day) in restWeekdays

    /** A planned day is done with a session, or with capoeira. */
    fun dayDone(day: Day): Boolean = trainedOn(day) || isCapoeira(day)

    private val firstDay: Day? get() = listOfNotNull(sessionDays.firstOrNull(), capoeiraDays.firstOrNull()).minOrNull()

    /**
     * Planned days done in a row. Rest days are passed over: they neither count nor break it.
     * Today counts once it's done; until then the streak runs up to yesterday.
     */
    fun streak(today: Day): Int {
        val first = firstDay ?: return 0
        var day = if (dayDone(today)) today else today - 1
        var count = 0
        while (day >= first) {
            when {
                dayDone(day) -> count++
                isRestDay(day) -> Unit
                else -> return count
            }
            day--
        }
        return count
    }
}

/** One logged exercise, as written to the history. */
@Serializable
data class WorkoutRecord(
    val timestamp: Long,
    val day: Day,
    val session: Int,
    val exerciseId: String,
    val exercise: String,
    val group: Group,
    val variationIndex: Int,
    val variation: String,
    val measure: Measure,
    val perSide: Boolean = false,
    val target: Int,
    val setsPlanned: Int,
    /** Reps or seconds done in each set. Empty when skipped. */
    val done: List<Int>,
    val effort: Effort,
    val levelBefore: Double,
    val levelAfter: Double,
    val nextVariation: String,
    val nextTarget: Int,
    val nextSets: Int,
    val capoeiraDay: Boolean = false,
    /** The user flagged the instructions as unclear. */
    val unclear: Boolean = false,
)

object Progression {
    const val MAX_SETS = 3

    fun small(ex: Exercise) = if (ex.measure == Measure.REPS) 1 else 5
    fun big(ex: Exercise) = if (ex.measure == Measure.REPS) 2 else 10

    fun initial(ex: Exercise, today: Day): ExerciseState =
        ExerciseState(variation = ex.start, target = ex.variation(ex.start).min, sets = ex.sets, introducedOn = today)

    /**
     * Capacity on one scale across variations: the variation number (1 = easiest) plus how far the
     * target is through that variation's range. 2.5 means halfway up the second variation.
     */
    fun level(ex: Exercise, s: ExerciseState): Double {
        val v = ex.variation(s.variation)
        val within = ((s.target - v.min).toDouble() / (v.max - v.min)).coerceIn(0.0, 1.0)
        // A third set, once the ladder is at its top, counts as extra capacity.
        val extraSets = (s.sets - ex.sets).coerceAtLeast(0) * 1.0
        return s.variation + 1 + within + extraSets
    }

    /**
     * The next state after a log. Easy moves the target up two steps, OK one step, hard keeps it,
     * and a missed target or a skip moves it down. Past the top of a variation's range the next
     * variation starts at its bottom; below the bottom the previous variation resumes near its top.
     */
    fun apply(ex: Exercise, s: ExerciseState, done: List<Int>, effort: Effort, today: Day): ExerciseState {
        val v = ex.variation(s.variation)
        val achieved = done.minOrNull() ?: 0
        val hit = done.isNotEmpty() && achieved >= s.target
        val struggles = if (effort == Effort.HARD || effort == Effort.SKIPPED) s.struggles + 1 else 0

        var variation = s.variation
        var sets = s.sets
        var target = when (effort) {
            Effort.SKIPPED -> s.target - big(ex)
            Effort.HARD -> if (hit) s.target else achieved
            Effort.OK -> if (hit) maxOf(s.target + small(ex), achieved) else achieved
            Effort.EASY -> if (hit) maxOf(s.target + big(ex), achieved + small(ex)) else maxOf(s.target, achieved)
        }
        // Two hard sessions in a row: ease off a step even if the target was reached.
        if (effort == Effort.HARD && hit && struggles >= 2) target -= small(ex)

        if (target > v.max) {
            when {
                variation < ex.variations.lastIndex -> { variation++; target = ex.variation(variation).min }
                sets < MAX_SETS -> { sets++; target = v.min + (v.max - v.min) / 2 }
                else -> target = minOf(target, v.max * 2)
            }
        } else if (target < v.min) {
            when {
                sets > ex.sets -> { sets--; target = v.max }
                variation > 0 && (effort == Effort.SKIPPED || achieved < v.min) -> {
                    variation--
                    val prev = ex.variation(variation)
                    target = prev.max - (prev.max - prev.min) / 3
                }
                // The easiest variation: the target may go below its range.
                else -> Unit
            }
        }
        return s.copy(
            variation = variation,
            target = target.coerceAtLeast(1),
            sets = sets,
            lastDone = today,
            times = s.times + 1,
            struggles = struggles,
        )
    }

    /** Average level of the started exercises in a group, or null before any start. */
    fun groupLevel(library: Library, state: AppState, group: Group): Double? {
        val levels = library.inGroup(group).mapNotNull { ex -> state.exerciseState(ex.id)?.let { level(ex, it) } }
        return if (levels.isEmpty()) null else levels.average()
    }

    /** Average of the group levels, or null before any exercise starts. */
    fun overallLevel(library: Library, state: AppState): Double? {
        val groups = Group.entries.mapNotNull { groupLevel(library, state, it) }
        return if (groups.isEmpty()) null else groups.average()
    }

    fun levelPoint(library: Library, state: AppState, day: Day): LevelPoint? {
        val overall = overallLevel(library, state) ?: return null
        val groups = Group.entries.mapNotNull { g -> groupLevel(library, state, g)?.let { g.name to it } }.toMap()
        return LevelPoint(day, overall, groups)
    }
}
