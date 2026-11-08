package com.dmeltz.personalworkout.core

import kotlinx.serialization.Serializable

/**
 * A session in progress, saved after every log so it can be continued later the same day.
 * [position] is the index of the next exercise to log.
 */
@Serializable
data class ActiveSession(
    val day: Day,
    val number: Int,
    val kind: DayKind,
    val exerciseIds: List<String>,
    val position: Int = 0,
    val warmupDone: Boolean = false,
    val easy: Int = 0,
    val hard: Int = 0,
    val skipped: Int = 0,
    /** Exercises whose variation went up during this session. */
    val levelledUp: List<String> = emptyList(),
    /** When the session started, in epoch milliseconds (0 for a session saved before this was kept). */
    val startedAt: Long = 0,
    /** For each exercise logged so far, in order, what is needed to do it again after "Back". */
    val undo: List<Undo> = emptyList(),
) {
    val plannedCount: Int get() = exerciseIds.size
    val doneCount: Int get() = position
    val finished: Boolean get() = position >= exerciseIds.size
    fun next(): String? = exerciseIds.getOrNull(position)
}

/** An exercise's state before it was logged, and the day its group was last trained before that. */
@Serializable
data class Undo(val exerciseId: String, val before: ExerciseState, val groupDayBefore: Day? = null)

/** Today's unfinished session, if there is one. A session left over from an earlier day is dropped. */
fun AppState.sessionToContinue(today: Day): ActiveSession? =
    activeSession?.takeIf { it.day == today && !it.finished }

object Sessions {
    /** Starts a session: its new exercises count as started from now. */
    fun start(library: Library, state: AppState, plan: SessionPlan, today: Day, now: Long = System.currentTimeMillis()): AppState {
        val started = plan.startIds.mapNotNull { library.exercise(it) }
            .associate { it.id to state.initialState(it, today) }
        return state.copy(
            exercises = state.exercises + started,
            activeSession = ActiveSession(
                day = today,
                number = state.sessionsCompleted + 1,
                kind = plan.kind,
                exerciseIds = plan.exerciseIds,
                startedAt = now,
            ),
        )
    }

    fun warmupDone(state: AppState): AppState =
        state.copy(activeSession = state.activeSession?.copy(warmupDone = true))

    /** Logs the next exercise: progression, muscle group recovery, and the session position. */
    fun log(state: AppState, ex: Exercise, done: List<Int>, effort: Effort, today: Day): AppState {
        val session = state.activeSession ?: return state
        val before = state.exerciseState(ex.id) ?: state.initialState(ex, today)
        val after = Progression.apply(ex, before, done, effort, today)
        val trained = if (effort == Effort.SKIPPED) state.groupLastTrained
        else state.groupLastTrained + (ex.group.name to today)
        return state.copy(
            exercises = state.exercises + (ex.id to after),
            groupLastTrained = trained,
            activeSession = session.copy(
                position = session.position + 1,
                easy = session.easy + if (effort == Effort.EASY) 1 else 0,
                hard = session.hard + if (effort == Effort.HARD) 1 else 0,
                skipped = session.skipped + if (effort == Effort.SKIPPED) 1 else 0,
                levelledUp = session.levelledUp + if (after.variation > before.variation) listOf(ex.id) else emptyList(),
                undo = session.undo + Undo(ex.id, before, state.groupLastTrained[ex.group.name]),
            ),
        )
    }

    /**
     * Corrects an exercise logged today: its progress is worked out again from [before], the state it
     * had before the log, with the corrected sets and effort. [groupDayBefore] is the day its muscle
     * group was last trained before the log, restored if the correction makes it a skip, unless
     * [groupTrainedByOthers] (another exercise of the same group was done today). Today's
     * level point is updated when the session is already finished.
     */
    fun correct(
        library: Library,
        state: AppState,
        ex: Exercise,
        before: ExerciseState,
        groupDayBefore: Day?,
        groupTrainedByOthers: Boolean,
        done: List<Int>,
        effort: Effort,
        today: Day,
    ): AppState {
        val after = Progression.apply(ex, before, done, effort, today)
        val trained = when {
            effort != Effort.SKIPPED || groupTrainedByOthers -> state.groupLastTrained + (ex.group.name to today)
            groupDayBefore == null -> state.groupLastTrained - ex.group.name
            else -> state.groupLastTrained + (ex.group.name to groupDayBefore)
        }
        val corrected = state.copy(exercises = state.exercises + (ex.id to after), groupLastTrained = trained)
        if (state.levelHistory.none { it.day == today }) return corrected
        val point = Progression.levelPoint(library, corrected, today) ?: return corrected
        return corrected.copy(levelHistory = (corrected.levelHistory.filter { it.day != today } + point).sortedBy { it.day })
    }

    /** Finishes the session: counts the day for the streak and records the levels. */
    fun finish(library: Library, state: AppState, today: Day): AppState {
        val days = if (state.trainedOn(today)) state.sessionDays else (state.sessionDays + today).sorted()
        val point = Progression.levelPoint(library, state, today)
        val history = if (point == null) state.levelHistory
        else (state.levelHistory.filter { it.day != today } + point).sortedBy { it.day }
        return state.copy(
            sessionDays = days,
            levelHistory = history,
            sessionsCompleted = state.sessionsCompleted + 1,
            activeSession = null,
        )
    }

    /** A session whose last exercise was logged but whose summary wasn't reached still counts. */
    fun settle(library: Library, state: AppState): AppState {
        val session = state.activeSession ?: return state
        return if (session.finished) finish(library, state, session.day) else state
    }

    /** Marks or unmarks [day] as a capoeira day. On a weekly capoeira day, off means none that week. */
    fun setCapoeira(state: AppState, day: Day, on: Boolean): AppState {
        val weekly = weekday(day) in state.capoeiraWeekdays
        return state.copy(
            capoeiraDays = if (on && !weekly) (state.capoeiraDays + day).distinct().sorted() else state.capoeiraDays - day,
            noCapoeiraDays = if (!on && weekly) (state.noCapoeiraDays + day).distinct().sorted() else state.noCapoeiraDays - day,
        )
    }
}
