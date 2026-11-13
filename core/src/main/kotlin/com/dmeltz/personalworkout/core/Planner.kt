package com.dmeltz.personalworkout.core

/**
 * The plan for the coming days, worked out by running the session builder forward: each planned
 * exercise is assumed done in full at its target and rated OK. Real answers move the plan, so
 * it is a forecast, not a promise.
 */
object Planner {
    data class Item(val exerciseId: String, val variation: Int, val sets: Int, val target: Int, val new: Boolean)

    /** [done]: the exercises actually logged that day (yesterday, or today's finished workout), not a forecast. */
    data class DayPlan(val day: Day, val kind: DayKind, val items: List<Item>, val minutes: Int, val done: Boolean = false) {
        val training: Boolean get() = items.isNotEmpty()
    }

    /**
     * Yesterday and today as they really went, then the forecast for the following days: [days]
     * columns in all. Yesterday and a finished workout today show what was logged (skipped
     * exercises left out); a workout in progress shows what is logged so far and what is left.
     */
    fun window(library: Library, state: AppState, records: List<WorkoutRecord>, today: Day, days: Int = 11): List<DayPlan> {
        fun logged(day: Day) = records.filter { it.day == day && it.effort != Effort.SKIPPED }
            .distinctBy { it.exerciseId }
            .map { Item(it.exerciseId, it.variationIndex, it.setsPlanned, it.target, new = false) }
        val yesterday = DayPlan(today - 1, SessionBuilder.dayKind(state, today - 1), logged(today - 1), 0, done = true)
        val forecast = nextDays(library, state, today, days - 1)
        val active = state.sessionToContinue(today)
        val todays = when {
            active != null -> {
                val done = logged(today).filter { active.logged(it.exerciseId) != null }
                val left = active.exerciseIds.filter { id -> active.logged(id) == null }.mapNotNull { id ->
                    val ex = library.exercise(id) ?: return@mapNotNull null
                    val es = state.exerciseState(id) ?: state.initialState(ex, today)
                    Item(id, es.variation, es.sets, es.target, new = es.times == 0)
                }
                DayPlan(today, active.kind, done + left, 0)
            }
            state.trainedOn(today) -> DayPlan(today, SessionBuilder.dayKind(state, today), logged(today), 0, done = true)
            else -> forecast.first { it.day == today }
        }
        return listOf(yesterday, todays) + forecast.filter { it.day > today }.take(days - 2)
    }

    /** The next [days] days from [today]; today is skipped when it is already trained. */
    fun nextDays(library: Library, state: AppState, today: Day, days: Int = 10): List<DayPlan> {
        var s = Sessions.settle(library, state).copy(activeSession = null)
        val first = if (s.trainedOn(today)) today + 1 else today
        return (first until first + days).map { day ->
            val kind = SessionBuilder.dayKind(s, day)
            if (kind == DayKind.REST) return@map DayPlan(day, kind, emptyList(), 0)
            val plan = SessionBuilder.build(library, s, day, kind)
            s = Sessions.start(library, s, plan, day)
            val items = plan.exerciseIds.mapNotNull { id ->
                val ex = library.exercise(id) ?: return@mapNotNull null
                val es = s.exerciseState(id) ?: s.initialState(ex, day)
                s = Sessions.log(s, ex, List(es.sets) { es.target }, Effort.OK, day)
                Item(id, es.variation, es.sets, es.target, new = es.times == 0)
            }
            s = Sessions.finish(library, s, day)
            DayPlan(day, kind, items, plan.minutes)
        }
    }
}
