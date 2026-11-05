package com.dmeltz.personalworkout.core

/**
 * The plan for the coming days, worked out by running the session builder forward: each planned
 * exercise is assumed done in full at its target and rated OK. Real answers move the plan, so
 * it is a forecast, not a promise.
 */
object Planner {
    data class Item(val exerciseId: String, val variation: Int, val sets: Int, val target: Int, val new: Boolean)

    data class DayPlan(val day: Day, val kind: DayKind, val items: List<Item>, val minutes: Int) {
        val training: Boolean get() = items.isNotEmpty()
    }

    /** The next [days] days from [today]; today is skipped when it is already trained. */
    fun nextDays(library: Library, state: AppState, today: Day, days: Int = 10): List<DayPlan> {
        var s = Sessions.settle(library, state).copy(activeSession = null)
        val first = if (s.trainedOn(today)) today + 1 else today
        return (first until first + days).map { day ->
            val kind = SessionBuilder.dayKind(s, day)
            if (kind == DayKind.REST) return@map DayPlan(day, kind, emptyList(), 0)
            val plan = SessionBuilder.build(library, s, day, kind)
            s = Sessions.start(library, s, plan, day, now = 0)
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
