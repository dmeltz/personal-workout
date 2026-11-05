package com.dmeltz.personalworkout.core

/**
 * Editing and deleting past logs. The log always changes. An exercise's progress changes only when
 * the log is its latest one and its progress still comes from that log (no level set by hand since).
 * Older logs are history: their next targets were already built on.
 */
object History {
    /** One workout: the logs of one session, in the order they were done. */
    data class Workout(val day: Day, val session: Int, val records: List<WorkoutRecord>)

    /** Past workouts, newest first. */
    fun workouts(records: List<WorkoutRecord>): List<Workout> =
        records.groupBy { it.day to it.session }
            .map { (k, rs) -> Workout(k.first, k.second, rs.sortedBy { it.timestamp }) }
            .sortedWith(compareByDescending<Workout> { it.day }.thenByDescending { it.session })

    private fun same(a: WorkoutRecord, b: WorkoutRecord) = a.timestamp == b.timestamp && a.exerciseId == b.exerciseId

    /** Whether [ex]'s progress still comes from [r]: it is the latest log and nothing moved it since. */
    private fun drives(ex: Exercise, state: AppState, records: List<WorkoutRecord>, r: WorkoutRecord): Boolean {
        val s = state.exerciseState(ex.id) ?: return false
        val latest = records.filter { it.exerciseId == ex.id }.maxByOrNull { it.timestamp } ?: return false
        return same(latest, r) && s.target == r.nextTarget && ex.variation(s.variation).name == r.nextVariation
    }

    /** The exercise's state just before [r] was logged, as far as the log tells. */
    private fun before(ex: Exercise, s: ExerciseState, records: List<WorkoutRecord>, r: WorkoutRecord): ExerciseState {
        val previous = records.filter { it.exerciseId == ex.id && it.timestamp < r.timestamp }.maxByOrNull { it.timestamp }
        val struggled = r.effort == Effort.HARD || r.effort == Effort.SKIPPED
        return s.copy(
            variation = r.variationIndex.coerceIn(ex.variations.indices),
            target = r.target,
            sets = r.setsPlanned,
            lastDone = previous?.day ?: -1,
            times = (s.times - 1).coerceAtLeast(0),
            struggles = if (struggled) (s.struggles - 1).coerceAtLeast(0) else 0,
        )
    }

    /**
     * Corrects a past log with new sets and feeling. Returns the new state and the corrected record;
     * the progress only changes when the log still drives it.
     */
    fun edit(library: Library, state: AppState, records: List<WorkoutRecord>, r: WorkoutRecord, done: List<Int>, effort: Effort): Pair<AppState, WorkoutRecord> {
        val ex = library.exercise(r.exerciseId) ?: return state to r.copy(done = done, effort = effort)
        val current = state.exerciseState(ex.id) ?: state.initialState(ex, r.day)
        val start = before(ex, current, records, r)
        val after = Progression.apply(ex, start, done, effort, r.day)
        val record = r.copy(
            done = if (effort == Effort.SKIPPED) emptyList() else done,
            effort = effort,
            levelAfter = Progression.level(ex, after),
            nextVariation = ex.variation(after.variation).name,
            nextTarget = after.target,
            nextSets = after.sets,
        )
        if (!drives(ex, state, records, r)) return state to record
        return state.copy(exercises = state.exercises + (ex.id to after)) to record
    }

    /**
     * Deletes logs. An exercise whose latest log goes back to where it was before it. A day with
     * no logs left no longer counts for the streak or the level chart, and each workout removed
     * entirely no longer counts as done.
     */
    fun delete(library: Library, state: AppState, records: List<WorkoutRecord>, gone: List<WorkoutRecord>): AppState {
        var s = state
        var remaining = records
        for (r in gone.sortedByDescending { it.timestamp }) {
            val ex = library.exercise(r.exerciseId)
            if (ex != null && drives(ex, s, remaining, r)) {
                s = s.copy(exercises = s.exercises + (ex.id to before(ex, s.exerciseState(ex.id)!!, remaining, r)))
            }
            remaining = remaining.filterNot { same(it, r) }
        }
        val emptyDays = gone.map { it.day }.distinct().filter { d -> remaining.none { it.day == d } }
        val removedWorkouts = gone.map { it.day to it.session }.distinct()
            .count { k -> remaining.none { (it.day to it.session) == k } }
        return s.copy(
            sessionDays = s.sessionDays - emptyDays.toSet(),
            levelHistory = s.levelHistory.filter { it.day !in emptyDays },
            sessionsCompleted = (s.sessionsCompleted - removedWorkouts).coerceAtLeast(0),
        )
    }
}
