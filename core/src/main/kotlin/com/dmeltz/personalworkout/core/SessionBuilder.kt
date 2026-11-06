package com.dmeltz.personalworkout.core

enum class DayKind {
    /** A normal training day. */
    NORMAL,
    /** Capoeira today: a short upper-body and core session, and it's optional. */
    CAPOEIRA,
    /** No longer used (the day after capoeira is a normal day); kept so saved sessions still load. */
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
    /** Exercise time to fill with muscles that are nearly due, not counting the warm-up (about 2 minutes). Due muscles always get in. */
    const val NORMAL_SECONDS = 12 * 60
    const val LIGHT_SECONDS = 7 * 60
    const val MIN_EXERCISES = 3
    /** Minimum days between two new exercises, and sessions before the first one. */
    const val DAYS_BETWEEN_NEW = 3
    const val SESSIONS_BEFORE_NEW = 3
    /** No new exercise while one is this many hard or skipped logs in a row. */
    const val STRUGGLING = 2
    /** Conditioning comes back after this many days. */
    const val CONDITIONING_EVERY = 3
    /**
     * Every muscle is mainly worked at least every this many days, so at least 3 times in any 10.
     * A muscle is also done a day early when the next chance to train it comes too late.
     */
    const val MUSCLE_EVERY = 3

    /** The strength groups a day allows: a capoeira day has no legs or conditioning. Stretches are the cooldown. */
    fun allowedGroups(kind: DayKind): Set<Group> = when (kind) {
        DayKind.CAPOEIRA -> setOf(Group.PUSH, Group.PULL, Group.CORE)
        else -> Group.entries.filter { it.strength }.toSet()
    }

    /** Stretches at the end of every session. */
    const val COOLDOWN_STRETCHES = 2

    fun dayKind(state: AppState, today: Day): DayKind = when {
        state.isCapoeira(today) -> DayKind.CAPOEIRA
        state.isRestDay(today) -> DayKind.REST
        else -> DayKind.NORMAL
    }

    /** Estimated seconds for an exercise at its current target, including rest between sets. */
    fun seconds(ex: Exercise, s: ExerciseState): Int {
        val perSet = (if (ex.measure == Measure.REPS) s.target * 3 else s.target) * (if (ex.perSide(s.variation)) 2 else 1) + 5
        return s.sets * perSet + (s.sets - 1) * ex.rest + 30
    }

    /**
     * Exercises that start today. The first session starts every intro 1 exercise, even ones not
     * in it. After that, a muscle that no started exercise mainly works gets one right away, one a
     * day; the rest join one at a time, a few days apart.
     */
    fun newExercises(library: Library, state: AppState, today: Day, allowed: Set<Group> = allowedGroups(DayKind.NORMAL)): List<Exercise> {
        val strength = library.exercises.filter { it.group.strength }
        val started = strength.filter { state.exerciseState(it.id) != null }
        if (started.isEmpty()) return strength.filter { it.intro == 1 }
        val lastIntroduced = started.maxOf { state.exerciseState(it.id)!!.introducedOn }
        if (today <= lastIntroduced) return emptyList()
        val covered = started.flatMap { it.primary }.toSet()
        val unstarted = strength.withIndex().filter { state.exerciseState(it.value.id) == null }
        unstarted.filter { (it.value.primary - covered).isNotEmpty() && it.value.group in allowed }
            .minWithOrNull(compareBy({ it.value.intro }, { it.index }))?.let { return listOf(it.value) }
        val struggling = state.exercises.values.any { it.struggles >= STRUGGLING }
        if (today - lastIntroduced < DAYS_BETWEEN_NEW || state.sessionsCompleted < SESSIONS_BEFORE_NEW || struggling) {
            return emptyList()
        }
        return unstarted.filter { it.value.group in allowed }.minWithOrNull(compareBy({ it.value.intro }, { it.index }))?.value?.let(::listOf).orEmpty()
    }

    /** The last day an exercise that mainly works [m] was done, or never. */
    private fun lastWorked(library: Library, state: AppState, m: Muscle): Day =
        library.exercises.filter { it.group.strength && m in it.primary }
            .mapNotNull { state.exerciseState(it.id)?.lastDone?.takeIf { d -> d >= 0 } }
            .maxOrNull() ?: Long.MIN_VALUE

    /**
     * Builds today's session around the muscles: each muscle that is due (see [MUSCLE_EVERY]) gets
     * an exercise, picking the fewest exercises that cover them all. [kind] can override the day's
     * own kind, as for "train anyway" on a rest day.
     */
    fun build(library: Library, state: AppState, today: Day, kind: DayKind = dayKind(state, today)): SessionPlan {
        val light = kind == DayKind.CAPOEIRA
        val allowed = allowedGroups(kind) - (if (light) setOf(Group.CONDITIONING) else emptySet())
        fun lastTrained(g: Group) = state.groupLastTrained[g.name] ?: Long.MIN_VALUE
        val first = state.exercises.keys.none { library.exercise(it)?.group?.strength == true }
        val starting = newExercises(library, state, today, allowed).toMutableList()
        val todaysNew = if (first) emptyList() else starting
        fun isStarted(ex: Exercise) = state.exerciseState(ex.id) != null || ex in starting
        fun stateOf(ex: Exercise) = state.exerciseState(ex.id) ?: state.initialState(ex, today)
        fun lastDone(ex: Exercise) = state.exerciseState(ex.id)?.lastDone ?: -1L
        val pool = library.exercises.filter { isStarted(it) && it.group in allowed }
        val last = Muscle.entries.associateWith { lastWorked(library, state, it) }

        // The next day after today with a chance to train [m] (rest days don't count).
        fun nextChance(m: Muscle): Day = (today + 1..today + 7).firstOrNull { d ->
            val k = dayKind(state, d)
            k != DayKind.REST && library.exercises.any { m in it.primary && isStarted(it) && it.group in allowedGroups(k) }
        } ?: (today + 7)
        fun due(m: Muscle): Boolean {
            if (pool.none { m in it.primary }) return false
            val l = last.getValue(m)
            // Never worked: due, except in the first session, which is already full.
            if (l == Long.MIN_VALUE) return !first
            if (l >= today) return false
            return today - l >= MUSCLE_EVERY || nextChance(m) - l > MUSCLE_EVERY
        }
        // Worked yesterday: better left to rest when there's a choice.
        fun tired(ex: Exercise) = ex.primary.count { last.getValue(it) >= today - 1 }

        val chosen = mutableListOf<Exercise>()
        chosen += todaysNew
        val need = Muscle.entries.filter(::due).toMutableSet()
        need -= chosen.flatMap { it.primary }.toSet()
        // Cover the due muscles with as few exercises as possible.
        while (need.isNotEmpty()) {
            val best = pool.filter { it !in chosen && it.primary.any { m -> m in need } }
                .sortedWith(
                    // An exercise not done for a week gets a bonus, so every exercise comes around.
                    compareByDescending<Exercise> { it.primary.count { m -> m in need } + (if (lastDone(it) <= today - 7) 1 else 0) }
                        .thenBy(::tired)
                        .thenBy(::lastDone),
                ).firstOrNull() ?: break
            chosen += best
            need -= best.primary.toSet()
        }
        // Room left? Muscles rested 2 days or more go a day early, which spreads the work evenly
        // over the days instead of every muscle falling due on the same day.
        val budget = if (light) LIGHT_SECONDS else NORMAL_SECONDS
        var total = chosen.sumOf { seconds(it, stateOf(it)) }
        val soon = Muscle.entries.filter { m -> last.getValue(m) <= today - 2 && pool.any { m in it.primary } }.toMutableSet()
        soon -= chosen.flatMap { it.primary }.toSet()
        while (soon.isNotEmpty()) {
            val best = pool.filter { it !in chosen && it.primary.any { m -> m in soon } && tired(it) == 0 }
                .sortedWith(
                    compareBy<Exercise> { it.primary.minOf { m -> last.getValue(m) } }
                        .thenByDescending { it.primary.count { m -> m in soon } }
                        .thenBy(::lastDone),
                ).firstOrNull { total + seconds(it, stateOf(it)) <= budget } ?: break
            chosen += best
            total += seconds(best, stateOf(best))
            soon -= best.primary.toSet()
        }
        // Conditioning comes back every few days.
        if (Group.CONDITIONING in allowed && chosen.none { it.group == Group.CONDITIONING } &&
            lastTrained(Group.CONDITIONING) <= today - CONDITIONING_EVERY
        ) {
            pool.filter { it.group == Group.CONDITIONING }.minByOrNull(::lastDone)?.let { chosen += it }
        }
        // Too few? Add rested ones, those done longest ago first.
        val minimum = if (light) 2 else MIN_EXERCISES
        val rest = pool.filter { it !in chosen }.sortedWith(compareBy(::tired, ::lastDone))
        for (ex in rest) {
            if (chosen.size >= minimum) break
            chosen += ex
        }

        // Round robin across groups so the same muscles don't work twice in a row. Conditioning goes last.
        val queues = chosen.filter { it.group != Group.CONDITIONING }.groupBy { it.group }.values.map { it.toMutableList() }
        val ordered = mutableListOf<Exercise>()
        while (queues.any { it.isNotEmpty() }) for (q in queues) if (q.isNotEmpty()) ordered += q.removeAt(0)
        ordered += chosen.filter { it.group == Group.CONDITIONING }
        // Cooldown: one lower back and pelvis stretch and one other, each the one done longest ago.
        val stretches = library.exercises.filter { it.group == Group.FLEXIBILITY }
        fun stretch(lowerBack: Boolean) = stretches.filter { it.lowerBack == lowerBack }
            .minWithOrNull(compareBy(::lastDone, { stretches.indexOf(it) }))
        val cooldown = listOfNotNull(stretch(true), stretch(false)).take(COOLDOWN_STRETCHES)
        starting += cooldown.filter { state.exerciseState(it.id) == null && it !in starting }
        ordered += cooldown
        total = ordered.sumOf { seconds(it, stateOf(it)) }
        return SessionPlan(kind, ordered.map { it.id }, starting.map { it.id }, (total + 119) / 60 + 2)
    }
}
