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
            assertTrue(e.primary.isNotEmpty(), "${e.id} needs its main muscles")
            assertTrue(e.variations.all { it.how.isNotBlank() }, "${e.id} has a variation without a description")
        }
        val covered = library.exercises.flatMap { it.primary }.toSet()
        assertEquals(Muscle.entries.toSet(), covered, "every muscle on the map is some exercise's main target")
        assertTrue(library.warmup.isNotEmpty())
        for (m in library.warmup) assertNotNull(m.figure?.let { Figures.all[it] }, "warm-up ${m.name} has no figure")
        for (e in library.exercises) assertEquals(2, Figures.views(e.figure).size, "${e.id} has two views")
        for (e in library.exercises) for (i in e.variations.indices) {
            assertNotNull(e.variations[i].figure, "${e.id} variation ${i + 1} has its own figure")
            assertEquals(2, Figures.views(e.figureAt(i)).size, "${e.id} variation ${i + 1}: ${e.figureAt(i)} has two views")
        }
        for ((name, f) in Figures.all) {
            assertTrue(f.frames.isNotEmpty(), name)
            for (p in f.frames) assertEquals(Pose.JOINTS * 3, p.points.size, name)
            for (pr in f.props) if (pr is Prop.Tether) assertTrue(pr.joint in 0 until Pose.JOINTS)
        }
        assertTrue(library.safety.any { "hurt" in it })
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

    @Test fun topOfTheLadderHoldsTwoSets() {
        val calf = ex("CALF_RAISE")
        val top = calf.variations.lastIndex
        val max = calf.variation(top).max
        val s = Progression.initial(calf, monday).copy(variation = top, target = max)
        val next = Progression.apply(calf, s, listOf(max + 3, max + 3), Effort.EASY, monday)
        assertEquals(top, next.variation)
        assertEquals(calf.sets, next.sets, "sets never change")
        assertEquals(max, next.target, "the target stops at the top of the range")
        assertTrue(Progression.atTop(calf, next))
        assertFalse(Progression.atTop(calf, next.copy(target = max - 1)))
    }

    @Test fun savedSetsGoBackToTheUsual() {
        val old = AppState(
            version = 1,
            exercises = mapOf("CALF_RAISE" to ExerciseState(variation = 2, target = 15, sets = 3, introducedOn = 1)),
            startLevels = mapOf("PULLUP" to StartLevel(1, 5, 3)),
        )
        val new = Migrations.run(library, old)
        assertEquals(2, new.exerciseState("CALF_RAISE")!!.sets)
        assertEquals(2, new.startLevels["PULLUP"]!!.sets)
        assertEquals(new, Migrations.run(library, new))
    }

    @Test fun levelGrowsWithProgress() {
        val pushup = ex("PUSHUP")
        val a = Progression.initial(pushup, monday)
        assertEquals(2.0, Progression.level(pushup, a))
        assertEquals(2.5, Progression.level(pushup, a.copy(target = (6 + 15) / 2 + 1)), 0.1)
        assertEquals(3.0, Progression.level(pushup, a.copy(variation = 2, target = pushup.variation(2).min)))
        assertEquals(2.0, Progression.level(pushup, a.copy(sets = 3)), 1e-9, "sets don't count in the level")
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
        assertEquals(DayKind.NORMAL, SessionBuilder.dayKind(state, monday + 2), "the day after capoeira is a normal day")
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
        val plan = SessionBuilder.build(library, state, monday + 1)
        val groups = plan.exerciseIds.map { ex(it).group }
        assertFalse(Group.LEGS in groups, "legs on capoeira day")
        assertFalse(Group.CONDITIONING in groups)
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
            // No exercise two days running.
            val yesterday = plan.exerciseIds.toSet()
            val tomorrow = SessionBuilder.build(library, state, day + 1).exerciseIds.toSet()
            if (!state.isRestDay(day + 1)) {
                assertEquals(emptySet(), yesterday intersect tomorrow, "on ${day} and ${day + 1}")
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

    @Test fun workoutRestartsOnlyBeforeAnythingIsLogged() {
        val plan = SessionBuilder.build(library, AppState(), monday)
        var state = Sessions.warmupDone(Sessions.start(library, AppState(), plan, monday, now = 1L))
        val restarted = Sessions.restart(state, now = 2L).activeSession!!
        assertEquals(false, restarted.warmupDone)
        assertEquals(2L, restarted.startedAt)
        state = Sessions.log(state, ex(plan.exerciseIds.first()), listOf(5, 5), Effort.OK, monday)
        assertEquals(state, Sessions.restart(state, now = 3L))
    }

    @Test fun sessionKeepsWhatBackNeeds() {
        val plan = SessionBuilder.build(library, AppState(), monday)
        var state = Sessions.start(library, AppState(), plan, monday)
        val first = ex(plan.exerciseIds.first())
        val before = state.exerciseState(first.id)!!
        val groupBefore = state.groupLastTrained[first.group.name]
        state = Sessions.log(state, first, listOf(5, 5), Effort.OK, monday)
        assertEquals(listOf(Undo(first.id, before, groupBefore)), state.activeSession!!.undo)
    }

    @Test fun loggedExerciseCanBeCorrected() {
        val plan = SessionBuilder.build(library, AppState(), monday)
        var state = Sessions.start(library, AppState(), plan, monday, now = 1234L)
        assertEquals(1234L, state.activeSession!!.startedAt)
        val first = ex(plan.exerciseIds.first())
        val before = state.exerciseState(first.id)!!
        val groupBefore = state.groupLastTrained[first.group.name]
        // Logged as skipped by mistake, then the rest of the session.
        state = Sessions.log(state, first, emptyList(), Effort.SKIPPED, monday)
        for (id in plan.exerciseIds.drop(1)) state = Sessions.log(state, ex(id), listOf(5, 5), Effort.OK, monday)
        state = Sessions.settle(library, state)
        val levelBefore = state.levelHistory.single().overall
        val others = plan.exerciseIds.drop(1).any { ex(it).group == first.group }
        // Corrected to an easy full session: the same result as logging it right the first time.
        val sets = List(before.sets) { before.target }
        state = Sessions.correct(library, state, first, before, groupBefore, others, sets, Effort.EASY, monday)
        assertEquals(Progression.apply(first, before, sets, Effort.EASY, monday), state.exerciseState(first.id))
        assertEquals(monday, state.groupLastTrained[first.group.name])
        assertEquals(1, state.levelHistory.size)
        assertTrue(state.levelHistory.single().overall >= levelBefore)
        // And back to skipped: the group no longer counts as trained.
        state = Sessions.correct(library, state, first, before, groupBefore, others, emptyList(), Effort.SKIPPED, monday)
        assertEquals(if (others) monday else groupBefore, state.groupLastTrained[first.group.name])
    }
}

class LevelsTest {
    private val library = LibraryParser.parse(File("../app/src/main/assets/exercises.txt").readText())
    private val day = 20000L

    @Test fun setLevelsMoveStartedAndSetStartsForTheRest() {
        val push = library.exercise("PUSHUP")!!
        val pull = library.exercise("PULLUP")!!
        var state = AppState(exercises = mapOf("PUSHUP" to Progression.initial(push, day).copy(struggles = 2)))
        state = Levels.set(library, state, mapOf(
            "PUSHUP" to StartLevel(variation = 2, target = 10, sets = 3),
            "PULLUP" to StartLevel(variation = 2, target = 99, sets = 2),
        ), day)
        val p = state.exerciseState("PUSHUP")!!
        assertEquals(listOf(2, 10, 2, 0), listOf(p.variation, p.target, p.sets, p.struggles))
        assertEquals(null, state.exerciseState("PULLUP"), "setting a level doesn't start an exercise")
        assertEquals(StartLevel(2, pull.variation(2).max, 2), state.startLevels["PULLUP"], "the target is kept inside the range")
        assertEquals(day, state.levelHistory.single().day)
        // When the pull-up starts, it starts there.
        val started = state.initialState(pull, day + 5)
        assertEquals(listOf(2, pull.variation(2).max, 2), listOf(started.variation, started.target, started.sets))
        assertEquals(StartLevel(2, pull.variation(2).max, 2), Levels.current(state, pull, day))
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

class MuscleTest {
    private val lib = LibraryParser.parse(java.io.File("../app/src/main/assets/exercises.txt").readText())

    @Test
    fun muscleStatus() {
        val push = lib.exercise("PUSHUP")!!
        var state = AppState(exercises = mapOf("PUSHUP" to Progression.initial(push, 100)))
        kotlin.test.assertNotNull(Progression.muscleLevel(lib, state, Muscle.CHEST))
        kotlin.test.assertNull(Progression.muscleLevel(lib, state, Muscle.QUADS))
        kotlin.test.assertNull(Progression.muscleLastWorked(lib, state, Muscle.ABS))
        state = state.copy(exercises = mapOf("PUSHUP" to Progression.initial(push, 100).copy(lastDone = 105)))
        kotlin.test.assertEquals(105L, Progression.muscleLastWorked(lib, state, Muscle.ABS))
        val m = Progression.musclesOf(listOf(push, lib.exercise("PLANK")!!))
        kotlin.test.assertEquals(true, m[Muscle.ABS])
        kotlin.test.assertEquals(true, m[Muscle.CHEST])
        kotlin.test.assertEquals(false, m[Muscle.OBLIQUES])
    }
}

class SidesTest {
    private val lib = LibraryParser.parse(java.io.File("../app/src/main/assets/exercises.txt").readText())

    @Test
    fun sidesAreExplicit() {
        val bug = lib.exercise("DEAD_BUG")!!
        assertEquals(Sides.ALTERNATE, bug.sidesAt(0))
        assertEquals("4 reps per side", bug.amount(0, 4))
        kotlin.test.assertTrue("8 reps in total" in bug.sidesHow(0, 4)!!)
        val push = lib.exercise("PUSHUP")!!
        assertEquals(Sides.NONE, push.sidesAt(1))
        assertEquals(Sides.ALTERNATE, push.sidesAt(push.variations.indexOfFirst { it.name.startsWith("Archer") }))
        kotlin.test.assertNull(push.sidesHow(1, 10))
        assertEquals(Sides.NONE, lib.exercise("BIRD_DOG")!!.sidesAt(2), "superman lifts both sides at once")
        assertEquals(Sides.EACH, lib.exercise("SIDE_PLANK")!!.sidesAt(0))
        // No variation text still says how to count sides: the app explains it from the sides field.
        for (e in lib.exercises) for (v in e.variations) {
            kotlin.test.assertFalse("count each" in v.how.lowercase(), "${e.id} ${v.name}")
            kotlin.test.assertFalse("each side is one rep" in (e.cues + v.cues).joinToString().lowercase(), e.id)
        }
    }

    @Test
    fun oldAlternatingTargetsHalve() {
        val bug = lib.exercise("DEAD_BUG")!!
        val old = AppState(exercises = mapOf(
            "DEAD_BUG" to ExerciseState(variation = 0, target = 11, sets = 2, introducedOn = 1),
            "SQUAT" to ExerciseState(variation = 1, target = 14, sets = 2, introducedOn = 1),
        ))
        val new = Migrations.run(lib, old)
        assertEquals(6, new.exerciseState("DEAD_BUG")!!.target)
        assertEquals(14, new.exerciseState("SQUAT")!!.target)
        assertEquals(Migrations.CURRENT, new.version)
        assertEquals(new, Migrations.run(lib, new), "running it again changes nothing")
        kotlin.test.assertTrue(bug.variations.all { it.min >= 3 })
    }
}
