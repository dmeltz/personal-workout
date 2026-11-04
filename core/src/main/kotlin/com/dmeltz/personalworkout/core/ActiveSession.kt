package com.dmeltz.personalworkout.core

import kotlinx.serialization.Serializable

@Serializable
data class SessionEntry(val itemId: String, val retry: Boolean = false)

/**
 * A practice session in progress, saved after every answer so it can be continued later the same day.
 * [position] is the index of the next unanswered entry. Retries of wrong answers are appended to [entries].
 */
@Serializable
data class ActiveSession(
    val day: Day,
    val number: Int,
    val newRuleIds: List<String>,
    val entries: List<SessionEntry>,
    val position: Int = 0,
    val remindersShown: List<String> = emptyList(),
    val firstTryCorrect: Int = 0,
    val missedRuleIds: List<String> = emptyList(),
) {
    /** The planned exercises, not counting retries. */
    val plannedCount: Int get() = entries.count { !it.retry }

    /** Planned exercises answered so far. */
    val answeredCount: Int get() = entries.take(position).count { !it.retry }

    val finished: Boolean get() = position >= entries.size

    fun next(): SessionEntry? = entries.getOrNull(position)

    companion object {
        fun start(plan: SessionPlan, state: AppState, today: Day) = ActiveSession(
            day = today,
            number = state.sessionsCompleted + 1,
            newRuleIds = plan.newRules.map { it.id },
            entries = plan.items.map { SessionEntry(it.id) },
        )
    }
}

/** Today's unfinished session, if there is one. A session left over from an earlier day is dropped. */
fun AppState.sessionToContinue(today: Day): ActiveSession? =
    activeSession?.takeIf { it.day == today && !it.finished }

object Sessions {
    /** Starts a session: its new rules count as started from now. */
    fun start(state: AppState, plan: SessionPlan, today: Day): AppState =
        plan.newRules.fold(state) { s, r -> Progress.introduceRule(s, r.id, today) }
            .copy(activeSession = ActiveSession.start(plan, state, today))

    /** Records an answer to the next entry: scores, schedule, and the session's own position. */
    fun answer(state: AppState, item: Item, result: CheckResult, today: Day): AppState {
        val session = state.activeSession ?: return state
        val entry = session.next() ?: return state
        val updated = Progress.applyAnswer(state, item, result, today, entry.retry)
        val retryEntry = if (!result.correct && !entry.retry) listOf(SessionEntry(item.id, retry = true)) else emptyList()
        return updated.copy(
            activeSession = session.copy(
                entries = session.entries + retryEntry,
                position = session.position + 1,
                firstTryCorrect = session.firstTryCorrect + if (!entry.retry && result.correct) 1 else 0,
                missedRuleIds = session.missedRuleIds +
                    if (!entry.retry && !result.ruleCorrect) listOf(item.primaryRuleId) else emptyList(),
            ),
        )
    }

    fun reminderShown(state: AppState, ruleId: String): AppState {
        val session = state.activeSession ?: return state
        return state.copy(activeSession = session.copy(remindersShown = session.remindersShown + ruleId))
    }

    /** Finishes the session: counts the day for the streak and grade history. */
    fun finish(state: AppState, today: Day): AppState =
        Progress.completeSession(state, today).copy(activeSession = null)

    /** A session whose last answer was given but whose summary wasn't reached still counts. */
    fun settle(state: AppState): AppState {
        val session = state.activeSession ?: return state
        return if (session.finished) finish(state, session.day) else state
    }
}
