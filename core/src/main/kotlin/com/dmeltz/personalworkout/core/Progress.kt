package com.dmeltz.personalworkout.core

import kotlinx.serialization.Serializable
import kotlin.math.pow

/** Days are counted as epoch days (days since 1970-01-01) in the phone's time zone. */
typealias Day = Long

/** Spaced repetition state of one item. */
@Serializable
data class ItemState(
    val level: Int = 0,
    val due: Day = 0,
    val seen: Int = 0,
    val correct: Int = 0,
    val lastSeen: Day = -1,
)

object Scheduler {
    /** Days until the next review after reaching level 1, 2, 3... */
    val INTERVALS = intArrayOf(1, 3, 7, 16, 35, 80)

    fun next(state: ItemState?, correct: Boolean, today: Day): ItemState {
        val s = state ?: ItemState()
        val level = if (correct) minOf(s.level + 1, INTERVALS.size) else 0
        val due = today + if (level == 0) 1 else INTERVALS[level - 1]
        return ItemState(
            level = level,
            due = due,
            seen = s.seen + 1,
            correct = s.correct + if (correct) 1 else 0,
            lastSeen = today,
        )
    }
}

/**
 * Evidence about one rule. [weightedCorrect] and [weightedWrong] are answer counts in which
 * each older answer counts a little less than the one after it.
 */
@Serializable
data class RuleStats(
    val weightedCorrect: Double = 0.0,
    val weightedWrong: Double = 0.0,
    val answers: Int = 0,
    val correctAnswers: Int = 0,
    val lastPracticed: Day = -1,
    val introducedOn: Day = -1,
) {
    val introduced: Boolean get() = introducedOn >= 0
    val reliable: Boolean get() = answers >= Mastery.RELIABLE_AFTER
}

object Mastery {
    /** How much each earlier answer counts compared to the next one. */
    const val DECAY = 0.9
    const val RELIABLE_AFTER = 5

    fun update(stats: RuleStats, correct: Boolean, today: Day): RuleStats = stats.copy(
        weightedCorrect = stats.weightedCorrect * DECAY + if (correct) 1.0 else 0.0,
        weightedWrong = stats.weightedWrong * DECAY + if (correct) 0.0 else 1.0,
        answers = stats.answers + 1,
        correctAnswers = stats.correctAnswers + if (correct) 1 else 0,
        lastPracticed = today,
    )

    /**
     * Estimated chance of answering a new item on this rule correctly today.
     *
     * Starts at 50% and follows recent answers. Time since the last practice lowers it, by up
     * to 40%, more slowly the more often the rule has been answered correctly.
     */
    fun score(stats: RuleStats, today: Day): Double {
        val base = (1.0 + stats.weightedCorrect) / (2.0 + stats.weightedCorrect + stats.weightedWrong)
        if (stats.lastPracticed < 0) return base
        val days = (today - stats.lastPracticed).coerceAtLeast(0)
        val halfLife = 5.0 + 3.0 * minOf(stats.correctAnswers, 20)
        val retention = 2.0.pow(-days / halfLife)
        return base * (0.6 + 0.4 * retention)
    }
}

@Serializable
data class GradePoint(val day: Day, val grade: Int)

@Serializable
data class AppState(
    val items: Map<String, ItemState> = emptyMap(),
    val rules: Map<String, RuleStats> = emptyMap(),
    /** Days with a completed session, ascending. */
    val practiceDays: List<Day> = emptyList(),
    /** Average grade at the end of each practice day. */
    val gradeHistory: List<GradePoint> = emptyList(),
    val sessionsCompleted: Int = 0,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    val reminderEnabled: Boolean = true,
    val activeSession: ActiveSession? = null,
) {
    fun ruleStats(id: String): RuleStats = rules[id] ?: RuleStats()

    fun practicedOn(day: Day): Boolean = practiceDays.binarySearch(day) >= 0

    /** Days in a row with a session, counting today if done, otherwise up to yesterday. */
    fun streak(today: Day): Int {
        val days = practiceDays.toHashSet()
        var day = if (today in days) today else today - 1
        var count = 0
        while (day in days) { count++; day-- }
        return count
    }

    fun introducedRuleIds(): List<String> = rules.filterValues { it.introduced }.keys.toList()

    /** Average of the rule scores, over the rules started so far, as a whole percent. Null before any rule starts. */
    fun averageGrade(today: Day): Int? {
        val started = rules.values.filter { it.introduced }
        if (started.isEmpty()) return null
        return Math.round(started.map { Mastery.score(it, today) }.average() * 100).toInt()
    }
}

/** One answer, as written to the answer log. */
@Serializable
data class AnswerRecord(
    val timestamp: Long,
    val day: Day,
    val session: Int,
    val itemId: String,
    val ruleIds: List<String>,
    val type: ExerciseType,
    val prompt: String,
    val expected: String,
    val typed: String,
    val correct: Boolean,
    val ruleCorrect: Boolean,
    val mistake: MistakeKind? = null,
    /** A second try of an item already answered wrong in the same session. Doesn't change scores. */
    val retry: Boolean = false,
    /** The hint was shown before this answer. */
    val hinted: Boolean = false,
)

object Progress {

    fun introduceRule(state: AppState, ruleId: String, today: Day): AppState =
        state.copy(rules = state.rules + (ruleId to state.ruleStats(ruleId).copy(introducedOn = today)))

    /** Applies one answer to the schedule and the rule scores. Retries are logged but change nothing. */
    fun applyAnswer(state: AppState, item: Item, result: CheckResult, today: Day, retry: Boolean): AppState {
        if (retry) return state
        val itemState = Scheduler.next(state.items[item.id], result.correct, today)
        val rules = state.rules.toMutableMap()
        item.ruleIds.forEachIndexed { index, ruleId ->
            // The primary rule is credited when its focus part is right; extra tags need the whole answer.
            val ok = if (index == 0) result.ruleCorrect else result.correct
            rules[ruleId] = Mastery.update(state.ruleStats(ruleId), ok, today)
        }
        return state.copy(items = state.items + (item.id to itemState), rules = rules)
    }

    fun completeSession(state: AppState, today: Day): AppState {
        val days = if (state.practicedOn(today)) state.practiceDays else (state.practiceDays + today).sorted()
        val grade = state.averageGrade(today)
        val history = if (grade == null) state.gradeHistory
        else state.gradeHistory.filter { it.day != today } + GradePoint(today, grade)
        return state.copy(
            practiceDays = days,
            gradeHistory = history.sortedBy { it.day },
            sessionsCompleted = state.sessionsCompleted + 1,
        )
    }
}
