package com.dmeltz.personalworkout.core

import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoreTest {
    private val library = LibraryParser.parse(File("../app/src/main/assets/exercises.txt").readText())
    private val monday: Day = LocalDate.of(2026, 11, 2).toEpochDay()

    private fun ex(id: String) = library.exercise(id)!!

    @Test fun libraryIsComplete() {
        assertTrue(library.exercises.size >= 15)
        for (g in Group.entries) assertTrue(library.inGroup(g).isNotEmpty(), "no exercises for $g")
        for (e in library.exercises) {
            assertNotNull(Figures.all[e.figure], "${e.id} has no figure ${e.figure}")
            assertTrue(e.cues.size >= 2, "${e.id} needs form cues")
            assertTrue(e.variations.all { it.how.isNotBlank() }, "${e.id} has a variation without a description")
        }
        assertTrue(library.warmup.isNotEmpty())
        assertTrue(library.safety.any { "hurt" in it })
        for (f in Figures.all.values) assertEquals(Pose.JOINTS * 2, f.start.points.size)
    }

    @Test fun weekdays() {
        assertEquals(1, weekday(monday))
        assertEquals(6, weekday(monday + 5))
        assertEquals(7, weekday(monday + 6))
    }

    @Test fun easyMovesUpAndThenToTheNextVariation() {
        val pushup = ex("PUSHUP")
        var s = Progression.initial(pushup, monday)
        assertEquals(1, s.variation)
        assertEquals(6, s.target)
        s = Progression.apply(pushup, s, listOf(6, 6), Effort.EASY, monday)
        assertEquals(8, s.target)
        s = Progression.apply(pushup, s, listOf(8, 8), Effort.OK, monday + 1)
        assertEquals(9, s.target)
        s = s.copy(target = 15)
        s = Progression.apply(pushup, s, listOf(15, 15), Effort.EASY, monday + 2)
        assertEquals(2, s.variation)
        assertEquals(pushup.variation(2).min, s.target)
    }

    @Test fun hardHoldsAndMissesComeDown() {
        val squat = ex("SQUAT")
        val s = Progression.initial(squat, monday).copy(target = 18)
        assertEquals(18, Progression.apply(squat, s, listOf(18, 18), Effort.HARD, monday).target)
        assertEquals(15, Progression.apply(squat, s, listOf(18, 15), Effort.HARD, monday).target)
        val twice = Progression.apply(squat, s.copy(struggles = 1), listOf(18, 18), Effort.HARD, monday)
        assertEquals(17, twice.target)
    }

    @Test fun skipsEaseOffAndDropAVariation() {
        val pullup = ex("PULLUP")
        var s = Progression.initial(pullup, monday)
        assertEquals("Negative pull-up", pullup.variation(s.variation).name)
        s = Progression.apply(pullup, s, emptyList(), Effort.SKIPPED, monday)
        assertEquals(0, s.variation)
        assertTrue(s.target in pullup.variation(0).min..pullup.variation(0).max)
        assertEquals(1, s.struggles)
    }

    @Test fun topOfTheLadderAddsASet() {
        val calf = ex("CALF_RAISE")
        val top = calf.variations.lastIndex
        val s = Progression.initial(calf, monday).copy(variation = top, target = calf.variation(top).max)
        val next = Progression.apply(calf, s, listOf(s.target, s.target), Effort.EASY, monday)
        assertEquals(top, next.variation)
        assertEquals(3, next.sets)
        assertTrue(Progression.level(calf, next) > Progression.level(calf, s))
    }

    @Test fun levelGrowsWithProgress() {
        val pushup = ex("PUSHUP")
        val a = Progression.initial(pushup, monday)
        assertEquals(2.0, Progression.level(pushup, a))
        assertEquals(2.5, Progression.level(pushup, a.copy(target = (6 + 15) / 2 + 1)), 0.1)
        assertEquals(3.0, Progression.level(pushup, a.copy(variation = 2, target = pushup.variation(2).min)))
    }

    @Test fun restDaysDontBreakTheStreak() {
        // Saturday is the default rest day.
        val state = AppState(sessionDays = listOf(monday + 3, monday + 4, monday + 6))
        assertEquals(3, state.streak(monday + 6))
        // Monday not done yet: the streak still stands.
        assertEquals(3, state.streak(monday + 7))
        // Missing Monday breaks it by Tuesday.
        assertEquals(0, state.streak(monday + 8))
    }

    @Test fun capoeiraCountsForTheStreak() {
        val state = Sessions.setCapoeira(AppState(sessionDays = listOf(monday)), monday + 1, true)
        assertEquals(2, state.streak(monday + 1))
        assertEquals(DayKind.CAPOEIRA, SessionBuilder.dayKind(state, monday + 1))
        assertEquals(DayKind.AFTER_CAPOEIRA, SessionBuilder.dayKind(state, monday + 2))
    }

    @Test fun firstSessionStartsTheBasics() {
        val plan = SessionBuilder.build(library, AppState(), monday)
        assertTrue(plan.exerciseIds.size >= SessionBuilder.MIN_EXERCISES)
        assertTrue(plan.startIds.size >= 5)
        assertTrue(plan.minutes in 8..20, "first session takes ${plan.minutes} minutes")
        val groups = plan.exerciseIds.map { ex(it).group }
        assertTrue(groups.distinct().size >= 3)
    }

    @Test fun capoeiraDaysSkipLegs() {
        var state = Sessions.start(library, AppState(), SessionBuilder.build(library, AppState(), monday), monday)
        state = Sessions.setCapoeira(state.copy(activeSession = null), monday + 1, true)
        for (day in listOf(monday + 1, monday + 2)) {
            val plan = SessionBuilder.build(library, state, day)
            val groups = plan.exerciseIds.map { ex(it).group }
            assertFalse(Group.LEGS in groups, "legs on $day")
            assertFalse(Group.CONDITIONING in groups)
        }
    }

    @Test fun weekBalancesMuscleGroups() {
        var state = AppState()
        val counts = mutableMapOf<Group, Int>()
        for (d in 0 until 14) {
            val day = monday + d
            if (state.isRestDay(day)) continue
            val plan = SessionBuilder.build(library, state, day)
            state = Sessions.start(library, state, plan, day)
            for (id in plan.exerciseIds) {
                state = Sessions.log(state, ex(id), listOf(100, 100, 100), Effort.OK, day)
                counts[ex(id).group] = (counts[ex(id).group] ?: 0) + 1
            }
            state = Sessions.finish(library, state, day)
            // No main group two days running.
            val yesterday = plan.exerciseIds.map { ex(it).group }.toSet()
            val tomorrow = SessionBuilder.build(library, state, day + 1).exerciseIds.map { ex(it).group }.toSet()
            if (!state.isRestDay(day + 1)) {
                for (g in listOf(Group.PUSH, Group.PULL, Group.LEGS)) {
                    assertFalse(g in yesterday && g in tomorrow, "$g on ${day} and ${day + 1}")
                }
            }
        }
        for (g in listOf(Group.PUSH, Group.PULL, Group.LEGS, Group.CORE)) {
            assertTrue((counts[g] ?: 0) >= 4, "$g only ${counts[g]} times in two weeks")
        }
        assertTrue(state.exercises.size > library.exercises.count { it.intro == 1 }, "no new exercises in two weeks")
        assertEquals(12, state.streak(monday + 13))
    }

    @Test fun sessionLogsAndFinishes() {
        val plan = SessionBuilder.build(library, AppState(), monday)
        var state = Sessions.start(library, AppState(), plan, monday)
        assertEquals(plan.exerciseIds.size, state.activeSession!!.plannedCount)
        for (id in plan.exerciseIds) state = Sessions.log(state, ex(id), listOf(5, 5), Effort.OK, monday)
        assertTrue(state.activeSession!!.finished)
        state = Sessions.settle(library, state)
        assertEquals(null, state.activeSession)
        assertEquals(listOf(monday), state.sessionDays)
        assertEquals(1, state.levelHistory.size)
        assertEquals(1, state.streak(monday))
    }
}

class BodyTest {
    @Test fun weighInIsWeekly() {
        var s = AppState()
        assertTrue(s.weighInDue(100))
        s = Body.snooze(s, 100)
        assertFalse(s.weighInDue(100))
        assertTrue(s.weighInDue(101))
        s = Body.logWeight(s, 101, 76.0)
        assertFalse(s.weighInDue(107))
        assertTrue(s.weighInDue(108))
        s = Body.logWeight(Body.logWeight(s, 108, 76.6), 108, 76.4)
        assertEquals(2, s.weights.size)
        assertEquals(0.4, Body.change(s, 108, 7)!!, 1e-9)
    }
}
