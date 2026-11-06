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
        // No legs on capoeira day.
        for (d in plan.subList(1, 2)) assertTrue(d.items.none { library.exercise(it.exerciseId)!!.group == Group.LEGS })
        // Targets grow when the same exercise comes back.
        val pushups = plan.flatMap { d -> d.items.filter { it.exerciseId == plan[0].items[0].exerciseId } }
        assertTrue(pushups.size >= 2 && pushups.zipWithNext().all { (a, b) -> b.variation > a.variation || b.target >= a.target })
    }

    @Test fun aTrainedTodayStartsTomorrow() {
        val state = AppState(sessionDays = listOf(monday))
        assertEquals(monday + 1, Planner.nextDays(library, state, monday, 3).first().day)
    }

    @Test fun everyMuscleThreeTimesInEveryTenDays() {
        // Capoeira on Thursday and Sunday (the default), none, Tuesday and Thursday, and three times a week.
        val weeks = listOf(listOf(4, 7), emptyList(), listOf(2, 4), listOf(1, 3, 5))
        for (state in weeks.map { AppState(capoeiraWeekdays = it) }) {
            val plan = Planner.nextDays(library, state, monday, 60)
            for (w in plan.windowed(10)) {
                val hits = w.flatMap { d -> d.items.map { library.exercise(it.exerciseId)!! }.filter { it.group.strength }.flatMap { it.primary } }.groupingBy { it }.eachCount()
                val few = Muscle.entries.filter { (hits[it] ?: 0) < 3 }
                assertEquals(emptyList(), few, "capoeira ${state.capoeiraWeekdays} days ${w.first().day - monday}-${w.last().day - monday}")
            }
            // Every exercise still comes around once they're all started.
            val late = plan.drop(40).flatMap { d -> d.items.map { it.exerciseId } }.toSet()
            assertEquals(emptySet(), library.exercises.map { it.id }.toSet() - late)
        }
    }

    @Test fun everyWorkoutEndsWithTwoStretches() {
        val plan = Planner.nextDays(library, AppState(), monday, 21).filter { it.training }
        val stretches = library.exercises.filter { it.group == Group.FLEXIBILITY }
        for (d in plan) {
            val last = d.items.takeLast(2).map { library.exercise(it.exerciseId)!! }
            assertTrue(last.all { it.group == Group.FLEXIBILITY }, "day ${d.day - monday} ends with ${last.map { it.id }}")
            assertEquals(1, last.count { it.lowerBack }, "one lower back and pelvis stretch on day ${d.day - monday}")
            assertTrue(d.items.dropLast(2).none { library.exercise(it.exerciseId)!!.group == Group.FLEXIBILITY })
        }
        // In three weeks every stretch comes around, the lower back ones about every 6 workouts.
        val done = plan.flatMap { d -> d.items.map { it.exerciseId } }
        assertEquals(emptySet(), stretches.map { it.id }.toSet() - done.toSet())
        // Stretch holds grow when they come back.
        val folds = plan.flatMap { d -> d.items.filter { it.exerciseId == "FORWARD_FOLD" } }
        assertTrue(folds.zipWithNext().all { (a, b) -> b.variation > a.variation || b.target > a.target })
    }

    @Test fun stretchesDontCountAsMuscleWorkOrOverallLevel() {
        val fold = library.exercise("FORWARD_FOLD")!!
        val state = AppState(exercises = mapOf("FORWARD_FOLD" to Progression.initial(fold, monday).copy(lastDone = monday)))
        assertEquals(null, Progression.overallLevel(library, state))
        assertEquals(null, Progression.muscleLevel(library, state, Muscle.HAMSTRINGS))
        assertEquals(null, Progression.muscleLastWorked(library, state, Muscle.HAMSTRINGS))
        assertTrue(Progression.groupLevel(library, state, Group.FLEXIBILITY) != null)
    }
}
