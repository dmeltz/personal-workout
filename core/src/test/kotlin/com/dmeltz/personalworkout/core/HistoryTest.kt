package com.dmeltz.personalworkout.core

import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryTest {
    private val library = LibraryParser.parse(File("../app/src/main/assets/exercises.txt").readText())
    private val monday: Day = LocalDate.of(2026, 11, 2).toEpochDay()
    private val ex = library.exercises.first()

    private fun record(before: ExerciseState, after: ExerciseState, done: List<Int>, effort: Effort, day: Day, session: Int, ts: Long) =
        WorkoutRecord(
            timestamp = ts, day = day, session = session, exerciseId = ex.id, exercise = ex.title, group = ex.group,
            variationIndex = before.variation, variation = ex.variation(before.variation).name, measure = ex.measure,
            target = before.target, setsPlanned = before.sets, done = done, effort = effort,
            levelBefore = Progression.level(ex, before), levelAfter = Progression.level(ex, after),
            nextVariation = ex.variation(after.variation).name, nextTarget = after.target, nextSets = after.sets,
        )

    /** Two workouts, a day apart, with one log each. */
    private fun twoWorkouts(): Triple<AppState, List<WorkoutRecord>, ExerciseState> {
        val s0 = AppState().initialState(ex, monday)
        val s1 = Progression.apply(ex, s0, List(s0.sets) { s0.target }, Effort.OK, monday)
        val s2 = Progression.apply(ex, s1, List(s1.sets) { s1.target }, Effort.EASY, monday + 1)
        val records = listOf(
            record(s0, s1, List(s0.sets) { s0.target }, Effort.OK, monday, 1, 1000),
            record(s1, s2, List(s1.sets) { s1.target }, Effort.EASY, monday + 1, 2, 2000),
        )
        val state = AppState(exercises = mapOf(ex.id to s2), sessionDays = listOf(monday, monday + 1), sessionsCompleted = 2)
        return Triple(state, records, s1)
    }

    @Test fun workoutsAreNewestFirst() {
        val (_, records, _) = twoWorkouts()
        assertEquals(listOf(2, 1), History.workouts(records).map { it.session })
    }

    @Test fun editingTheLatestLogRedoesTheProgress() {
        val (state, records, s1) = twoWorkouts()
        val (after, record) = History.edit(library, state, records, records[1], List(s1.sets) { s1.target }, Effort.HARD)
        val expected = Progression.apply(ex, s1, List(s1.sets) { s1.target }, Effort.HARD, monday + 1)
        assertEquals(expected.target, after.exerciseState(ex.id)!!.target)
        assertEquals(expected.variation, after.exerciseState(ex.id)!!.variation)
        assertEquals(Effort.HARD, record.effort)
        assertEquals(expected.target, record.nextTarget)
    }

    @Test fun editingAnOlderLogOnlyChangesTheLog() {
        val (state, records, _) = twoWorkouts()
        val (after, record) = History.edit(library, state, records, records[0], listOf(1, 1), Effort.HARD)
        assertEquals(state, after)
        assertEquals(listOf(1, 1), record.done)
    }

    @Test fun deletingTheLatestWorkoutGoesBack() {
        val (state, records, s1) = twoWorkouts()
        val after = History.delete(library, state, records, listOf(records[1]))
        assertEquals(s1.target, after.exerciseState(ex.id)!!.target)
        assertEquals(s1.variation, after.exerciseState(ex.id)!!.variation)
        assertEquals(listOf(monday), after.sessionDays)
        assertEquals(1, after.sessionsCompleted)
    }

    @Test fun progressSetByHandIsKept() {
        val (state, records, _) = twoWorkouts()
        val byHand = state.copy(exercises = state.exercises + (ex.id to state.exerciseState(ex.id)!!.copy(target = 99)))
        val after = History.delete(library, byHand, records, listOf(records[1]))
        assertEquals(99, after.exerciseState(ex.id)!!.target)
    }
}
