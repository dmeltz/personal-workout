package com.dmeltz.personalworkout.core

import kotlinx.serialization.Serializable

/**
 * A session in progress, saved after every log so it can be continued later the same day.
 * [position] is the index of the exercise on screen. It moves forward after each log and with
 * Next, and back with Back, so an exercise there may already be logged ([logged]); logging it again
 * replaces its result.
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
    /** Each exercise logged so far, once each, with what is needed to log it again after Back. */
    val undo: List<Undo> = emptyList(),
) {
    val plannedCount: Int get() = exerciseIds.size
    /** How many of the session's exercises are logged (a session saved before [undo] was kept counts its position). */
    val doneCount: Int get() = if (undo.isEmpty()) position.coerceAtMost(exerciseIds.size) else undo.count { it.exerciseId in exerciseIds }
    val finished: Boolean get() = position >= exerciseIds.size
    fun next(): String? = exerciseIds.getOrNull(position)
    /** The log of [exerciseId] in this session, if it has one. */
    fun logged(exerciseId: String): Undo? = undo.firstOrNull { it.exerciseId == exerciseId }
}

/**
 * One logged exercise: its state before the log and the day its group was last trained before
 * it, so it can be logged again from the same start; and how the log went.
 */
@Serializable
data class Undo(
    val exerciseId: String,
    val before: ExerciseState,
    val groupDayBefore: Day? = null,
    val group: String? = null,
    val effort: Effort? = null,
    val levelledUp: Boolean = false,
)

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

    /** Back: the previous exercise comes up again, and logging it replaces its result. */
    fun back(state: AppState): AppState {
        val session = state.activeSession ?: return state
        if (session.position <= 0) return state
        return state.copy(activeSession = session.copy(position = (session.position - 1).coerceAtMost(session.exerciseIds.size - 1)))
    }

    /**
     * Restarts the workout completely: every log in it is undone (each exercise back to its state
     * before, each muscle group to its last training day before), and it starts again from the
     * warm-up with the clock at zero. The app deletes the workout's log records to match.
     */
    fun restart(state: AppState, now: Long = System.currentTimeMillis()): AppState {
        val session = state.activeSession ?: return state
        var groups = state.groupLastTrained
        for (u in session.undo.asReversed()) {
            val g = u.group ?: continue
            groups = if (u.groupDayBefore == null) groups - g else groups + (g to u.groupDayBefore)
        }
        return state.copy(
            exercises = state.exercises + session.undo.associate { it.exerciseId to it.before },
            groupLastTrained = groups,
            activeSession = session.copy(
                position = 0,
                warmupDone = false,
                startedAt = now,
                undo = emptyList(),
                easy = 0,
                hard = 0,
                skipped = 0,
                levelledUp = emptyList(),
            ),
        )
    }

    /**
     * Next: the same as "Skip / couldn't do it" on an exercise not logged yet. One already logged
     * (reached with Back) keeps its result, and the workout moves on.
     */
    fun next(state: AppState, ex: Exercise, today: Day): AppState {
        val session = state.activeSession ?: return state
        if (session.logged(ex.id) != null) return state.copy(activeSession = session.copy(position = session.position + 1))
        return log(state, ex, emptyList(), Effort.SKIPPED, today)
    }

    /**
     * Logs the exercise on screen: progression, muscle group recovery, and the session position.
     * One already logged in this session is worked out again from its state before that log, so
     * the new result replaces the old one.
     */
    fun log(state: AppState, ex: Exercise, done: List<Int>, effort: Effort, today: Day): AppState {
        val session = state.activeSession ?: return state
        val earlier = session.logged(ex.id)
        val before = earlier?.before ?: state.exerciseState(ex.id) ?: state.initialState(ex, today)
        val groupDayBefore = if (earlier != null) earlier.groupDayBefore else state.groupLastTrained[ex.group.name]
        val after = Progression.apply(ex, before, done, effort, today)
        val g = ex.group.name
        val othersTrained = session.undo.any { it.exerciseId != ex.id && it.group == g && it.effort != Effort.SKIPPED }
        val trained = when {
            effort != Effort.SKIPPED -> state.groupLastTrained + (g to today)
            earlier == null || othersTrained -> state.groupLastTrained
            groupDayBefore == null -> state.groupLastTrained - g
            else -> state.groupLastTrained + (g to groupDayBefore)
        }
        val entry = Undo(ex.id, before, groupDayBefore, g, effort, after.variation > before.variation)
        val undo = if (earlier == null) session.undo + entry else session.undo.map { if (it.exerciseId == ex.id) entry else it }
        return state.copy(
            exercises = state.exercises + (ex.id to after),
            groupLastTrained = trained,
            activeSession = session.copy(position = session.position + 1, undo = undo).recounted(),
        )
    }

    /** The session's counts of easy, hard and skipped exercises and its level-ups, from its logs. */
    private fun ActiveSession.recounted(): ActiveSession {
        val logs = undo.filter { it.effort != null }
        if (logs.size != undo.size) return this // logs saved before efforts were kept: counts stay as they were
        return copy(
            easy = logs.count { it.effort == Effort.EASY },
            hard = logs.count { it.effort == Effort.HARD },
            skipped = logs.count { it.effort == Effort.SKIPPED },
            levelledUp = logs.filter { it.levelledUp }.map { it.exerciseId },
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
