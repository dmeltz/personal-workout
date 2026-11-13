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
        // Training every day (the default since 2026-11-13), every day but Saturday, and with weekly
        // capoeira days: on Thursday and Sunday, Tuesday and Thursday, and three times a week.
        val states = listOf(AppState(), AppState(restWeekdays = listOf(6))) +
            listOf(listOf(4, 7), listOf(2, 4), listOf(1, 3, 5)).map { AppState(capoeiraWeekdays = it) }
        for (state in states) {
            val plan = Planner.nextDays(library, state, monday, 60)
            for (w in plan.windowed(10)) {
                val hits = w.flatMap { d -> d.items.map { library.exercise(it.exerciseId)!! }.filter { it.group.strength }.flatMap { it.primary } }.groupingBy { it }.eachCount()
                val few = Muscle.entries.filter { (hits[it] ?: 0) < 3 }
                assertEquals(emptyList(), few, "capoeira ${state.capoeiraWeekdays} rest ${state.restWeekdays} days ${w.first().day - monday}-${w.last().day - monday}")
            }
            // Every exercise still comes around once they're all started. Checked without weekly
            // capoeira, which is no longer used: with it, pike push-ups can wait over 20 days.
            if (state.capoeiraWeekdays.isNotEmpty()) continue
            val late = plan.drop(40).flatMap { d -> d.items.map { it.exerciseId } }.toSet()
            assertEquals(emptySet(), library.exercises.map { it.id }.toSet() - late)
        }
    }

    @Test fun windowShowsYesterdayAndTodayAsDone() {
        // Yesterday's workout done in full, then today's half done.
        var state = AppState()
        val records = mutableListOf<WorkoutRecord>()
        fun doWorkout(day: Day, upTo: Int = Int.MAX_VALUE) {
            val plan = SessionBuilder.build(library, state, day)
            state = Sessions.warmupDone(Sessions.start(library, state, plan, day), now = 1L)
            for (id in plan.exerciseIds.take(upTo)) {
                val ex = library.exercise(id)!!
                val es = state.exerciseState(id)!!
                state = Sessions.log(state, ex, List(es.sets) { es.target }, Effort.OK, day)
                records += WorkoutRecord(
                    timestamp = day * 1000 + records.size, day = day, session = state.activeSession!!.number, exerciseId = id,
                    exercise = ex.title, group = ex.group, variationIndex = es.variation, variation = "", measure = ex.measure,
                    perSide = false, target = es.target, setsPlanned = es.sets, done = List(es.sets) { es.target }, effort = Effort.OK,
                    levelBefore = 0.0, levelAfter = 0.0, nextVariation = "", nextTarget = 0, nextSets = 0,
                )
            }
            if (state.activeSession!!.finished) state = Sessions.finish(library, state, day)
        }
        val today = monday + 1
        doWorkout(monday)
        val yesterdayIds = records.map { it.exerciseId }
        doWorkout(today, upTo = 2)
        val todayIds = state.activeSession!!.exerciseIds

        var w = Planner.window(library, state, records, today)
        assertEquals(11, w.size)
        assertEquals((monday until monday + 11).toList(), w.map { it.day })
        assertTrue(w[0].done)
        assertEquals(yesterdayIds, w[0].items.map { it.exerciseId })
        assertEquals(false, w[1].done)
        assertEquals(todayIds, w[1].items.map { it.exerciseId }, "today: what's logged, then what's left")

        // Today finished: it shows as done, and the forecast starts tomorrow.
        while (!state.activeSession!!.finished) {
            val id = state.activeSession!!.next()!!
            val es = state.exerciseState(id)!!
            state = Sessions.log(state, library.exercise(id)!!, List(es.sets) { es.target }, Effort.OK, today)
            records += records.last().copy(timestamp = records.last().timestamp + 1, exerciseId = id, day = today)
        }
        state = Sessions.finish(library, state, today)
        w = Planner.window(library, state, records, today)
        assertTrue(w[1].done)
        assertEquals(todayIds, w[1].items.map { it.exerciseId })
        assertEquals(today + 1, w[2].day)
        assertTrue(w.drop(2).all { !it.done && it.training })
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

    @Test fun removedExercisesLeaveTodaysSession() {
        assertEquals(null, library.exercise("BAG"), "bag rounds were removed")
        val session = ActiveSession(monday, 1, DayKind.NORMAL, listOf("PUSHUP", "BAG", "SQUAT"), position = 1)
        val s = Migrations.run(library, AppState(activeSession = session))
        assertEquals(listOf("PUSHUP", "SQUAT"), s.activeSession!!.exerciseIds)
    }
}
