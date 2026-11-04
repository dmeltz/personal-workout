package com.dmeltz.personalworkout.core

enum class DayKind {
    /** A normal training day. */
    NORMAL,
    /** Capoeira today: a short upper-body and core session, and it's optional. */
    CAPOEIRA,
    /** The day after capoeira: no legs or conditioning. */
    AFTER_CAPOEIRA,
    /** A planned rest day. Training is still possible. */
    REST,
}

data class SessionPlan(
    val kind: DayKind,
    val exerciseIds: List<String>,
    /** Exercises that start today. Some may not be in today's session (the first session starts several). */
    val startIds: List<String>,
    val minutes: Int,
)

object SessionBuilder {
    /** Exercise time, not counting the warm-up (about 2 minutes). */
    const val NORMAL_SECONDS = 12 * 60
    const val LIGHT_SECONDS = 7 * 60
    const val MIN_EXERCISES = 3
    const val MAX_EXERCISES = 7
    /** Minimum days between two new exercises, and sessions before the first one. */
    const val DAYS_BETWEEN_NEW = 3
    const val SESSIONS_BEFORE_NEW = 3
    /** No new exercise while one is this many hard or skipped logs in a row. */
    const val STRUGGLING = 2
    /** Conditioning comes back after this many days. */
    const val CONDITIONING_EVERY = 3

    private val MAIN = listOf(Group.PUSH, Group.PULL, Group.LEGS)

    fun dayKind(state: AppState, today: Day): DayKind = when {
        state.isCapoeira(today) -> DayKind.CAPOEIRA
        state.isRestDay(today) -> DayKind.REST
        state.isCapoeira(today - 1) -> DayKind.AFTER_CAPOEIRA
        else -> DayKind.NORMAL
    }

    /** Estimated seconds for an exercise at its current target, including rest between sets. */
    fun seconds(ex: Exercise, s: ExerciseState): Int {
        val perSet = (if (ex.measure == Measure.REPS) s.target * 3 else s.target) * (if (ex.perSide(s.variation)) 2 else 1) + 5
        return s.sets * perSet + (s.sets - 1) * ex.rest + 30
    }

    /** Exercises that start today. The first session starts every intro 1 exercise, even ones not in it. */
    fun newExercises(library: Library, state: AppState, today: Day): List<Exercise> {
        if (state.exercises.isEmpty()) return library.exercises.filter { it.intro == 1 }
        val lastIntroduced = state.exercises.values.maxOf { it.introducedOn }
        val struggling = state.exercises.values.any { it.struggles >= STRUGGLING }
        if (today - lastIntroduced < DAYS_BETWEEN_NEW || state.sessionsCompleted < SESSIONS_BEFORE_NEW || struggling) {
            return emptyList()
        }
        return nextUnstarted(library, state, Group.entries.toSet())?.let(::listOf).orEmpty()
    }

    private fun nextUnstarted(library: Library, state: AppState, groups: Set<Group>): Exercise? =
        library.exercises.withIndex()
            .filter { state.exerciseState(it.value.id) == null && it.value.group in groups }
            .minWithOrNull(compareBy({ it.value.intro }, { it.index }))?.value

    /** Builds today's session. [kind] can override the day's own kind, as for "train anyway" on a rest day. */
    fun build(library: Library, state: AppState, today: Day, kind: DayKind = dayKind(state, today)): SessionPlan {
        val light = kind == DayKind.CAPOEIRA
        val allowed = when (kind) {
            DayKind.CAPOEIRA, DayKind.AFTER_CAPOEIRA -> setOf(Group.PUSH, Group.PULL, Group.CORE)
            else -> Group.entries.toSet()
        }
        fun lastTrained(g: Group) = state.groupLastTrained[g.name] ?: Long.MIN_VALUE
        fun rested(g: Group) = lastTrained(g) < today - 1
        // A new exercise waits for a day when its muscles are rested.
        val starting = newExercises(library, state, today)
            .filter { state.exercises.isEmpty() || (it.group in allowed && (it.group !in MAIN || rested(it.group))) }
            .toMutableList()
        fun isStarted(ex: Exercise) = state.exerciseState(ex.id) != null || ex in starting
        fun stateOf(ex: Exercise) = state.exerciseState(ex.id) ?: Progression.initial(ex, today)
        val todaysNew = if (state.exercises.isEmpty()) emptyList() else starting.toList()

        // Main groups: the ones rested longest, skipping any trained yesterday when possible.
        val mains = MAIN.filter { it in allowed }
        val restedMains = mains.filter(::rested).ifEmpty { mains }
        val chosenMains = (todaysNew.map { it.group }.filter { it in MAIN } + restedMains.sortedBy(::lastTrained))
            .distinct().take(if (light) 1 else 2)
        val groups = buildList {
            addAll(chosenMains)
            if (Group.CORE in allowed && (rested(Group.CORE) || light || todaysNew.any { it.group == Group.CORE })) add(Group.CORE)
            val conditioningDue = lastTrained(Group.CONDITIONING) <= today - CONDITIONING_EVERY
            if (!light && Group.CONDITIONING in allowed &&
                (conditioningDue || todaysNew.any { it.group == Group.CONDITIONING })
            ) add(Group.CONDITIONING)
        }
        // A chosen group with nothing started yet starts its first exercise.
        for (g in groups) {
            if (library.inGroup(g).none(::isStarted)) nextUnstarted(library, state, setOf(g))?.let { starting += it }
        }

        fun candidates(g: Group) = library.inGroup(g).filter(::isStarted)
            .sortedWith(compareBy({ state.exerciseState(it.id) != null }, { state.exerciseState(it.id)?.lastDone ?: -1L }))
        val perGroup = groups.associateWith { g ->
            candidates(g).take(if (g !in MAIN) 1 else if (chosenMains.size == 1) 3 else 2)
        }
        // Round robin across groups so the same muscles don't work twice in a row. Conditioning goes last.
        val ordered = mutableListOf<Exercise>()
        val queues = perGroup.filterKeys { it != Group.CONDITIONING }.values.map { it.toMutableList() }
        while (queues.any { it.isNotEmpty() }) for (q in queues) if (q.isNotEmpty()) ordered += q.removeAt(0)
        ordered += perGroup[Group.CONDITIONING].orEmpty()

        // Too few? Fill from the other allowed groups, core first, then those rested longest.
        val minimum = if (light) 2 else MIN_EXERCISES
        if (ordered.size < minimum) {
            val others = allowed.filter { it !in groups && (it != Group.CONDITIONING || !light) }
                .sortedWith(compareBy({ it != Group.CORE }, ::lastTrained))
            for (g in others) {
                if (ordered.size >= minimum) break
                ordered += candidates(g).firstOrNull() ?: continue
            }
        }

        val budget = if (light) LIGHT_SECONDS else NORMAL_SECONDS
        val chosen = mutableListOf<Exercise>()
        var total = 0
        for (ex in ordered) {
            val t = seconds(ex, stateOf(ex))
            // Conditioning gets in at least once a week, even on a full day.
            val conditioningOverdue = ex.group == Group.CONDITIONING && lastTrained(Group.CONDITIONING) <= today - 7
            val mustTake = ex in todaysNew || chosen.size < minimum || conditioningOverdue
            if (!mustTake && (total + t > budget || chosen.size >= MAX_EXERCISES)) continue
            chosen += ex; total += t
        }
        return SessionPlan(kind, chosen.map { it.id }, starting.map { it.id }, (total + 119) / 60 + 2)
    }
}
