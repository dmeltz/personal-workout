package com.dmeltz.personalworkout.core

import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerTest {
    private val library = LibraryParser.parse(File("../app/src/main/assets/exercises.txt").readText())
    private val monday: Day = LocalDate.of(2026, 11, 2).toEpochDay()

    @Test fun tenDaysWithRestAndCapoeira() {
        val state = AppState(capoeiraDays = listOf(monday + 1))
        val plan = Planner.nextDays(library, state, monday, 10)
        assertEquals(10, plan.size)
        assertEquals((monday until monday + 10).toList(), plan.map { it.day })
        // Saturday is the default rest day.
        assertTrue(plan.filter { SessionBuilder.dayKind(state, it.day) == DayKind.REST }.all { !it.training })
        assertEquals(DayKind.CAPOEIRA, plan[1].kind)
        assertTrue(plan.filter { it.kind != DayKind.REST }.all { it.training })
        // No legs on capoeira day or the day after.
        for (d in plan.take(3).drop(1)) assertTrue(d.items.none { library.exercise(it.exerciseId)!!.group == Group.LEGS })
        // Targets grow when the same exercise comes back.
        val pushups = plan.flatMap { d -> d.items.filter { it.exerciseId == plan[0].items[0].exerciseId } }
        assertTrue(pushups.size >= 2 && pushups.zipWithNext().all { (a, b) -> b.variation > a.variation || b.target >= a.target })
    }

    @Test fun aTrainedTodayStartsTomorrow() {
        val state = AppState(sessionDays = listOf(monday))
        assertEquals(monday + 1, Planner.nextDays(library, state, monday, 3).first().day)
    }
}
