package com.dmeltz.personalworkout.core

import kotlin.random.Random

data class SessionPlan(
    /** Rules introduced in this session, each shown as a reminder card before its items. */
    val newRules: List<Rule>,
    val items: List<Item>,
)

object SessionBuilder {
    const val SESSION_SIZE = 12
    const val NEW_RULE_ITEMS = 4
    /** At most this many due reviews per session, so missed days don't pile up. */
    const val MAX_REVIEWS = 9
    const val MAX_PER_RULE = 3
    /** Minimum days between two new rules. */
    const val DAYS_BETWEEN_NEW_RULES = 2
    /** No new rule while this many started rules score below [WEAK_SCORE]. */
    const val MAX_WEAK_RULES = 3
    const val WEAK_SCORE = 0.5
    /** The first session starts this many rules, so it isn't all one rule. */
    const val FIRST_SESSION_RULES = 2

    fun newRules(curriculum: Curriculum, state: AppState, today: Day): List<Rule> {
        val started = curriculum.rules.filter { state.ruleStats(it.id).introduced }
        val candidates = curriculum.introductionOrder.filter { !state.ruleStats(it.id).introduced }
        if (started.isEmpty()) return candidates.take(FIRST_SESSION_RULES)
        val lastIntroduced = started.maxOf { state.ruleStats(it.id).introducedOn }
        if (today - lastIntroduced < DAYS_BETWEEN_NEW_RULES) return emptyList()
        val weak = started.count {
            val s = state.ruleStats(it.id)
            s.reliable && Mastery.score(s, today) < WEAK_SCORE
        }
        return if (weak >= MAX_WEAK_RULES) emptyList() else candidates.take(1)
    }

    fun build(curriculum: Curriculum, state: AppState, today: Day, random: Random = Random.Default): SessionPlan {
        val newRules = newRules(curriculum, state, today)
        val chosen = LinkedHashSet<Item>()

        val newItems = newRules.flatMap { rule ->
            // One of each exercise type first, then the rest.
            val items = curriculum.itemsFor(rule.id).shuffled(random)
            val firstOfType = items.distinctBy { it.type }
            (firstOfType + (items - firstOfType.toSet())).take(NEW_RULE_ITEMS)
        }
        chosen += newItems

        val started = state.introducedRuleIds().toSet() + newRules.map { it.id }
        fun ruleScore(item: Item) = Mastery.score(state.ruleStats(item.primaryRuleId), today)
        val perRule = mutableMapOf<String, Int>()
        val reviews = mutableListOf<Item>()
        val budget = SESSION_SIZE - newItems.size

        fun take(candidates: List<Item>, limitPerRule: Boolean, limit: Int = budget) {
            for (item in candidates) {
                if (reviews.size >= limit) return
                if (item in chosen) continue
                val count = perRule[item.primaryRuleId] ?: 0
                if (limitPerRule && count >= MAX_PER_RULE) continue
                reviews += item; chosen += item
                perRule[item.primaryRuleId] = count + 1
            }
        }

        val pool = curriculum.items.filter { it.primaryRuleId in started }
        // 1. Items due for review, weakest rules first.
        val due = pool.filter { state.items[it.id]?.let { s -> s.due <= today } == true }
            .shuffled(random)
            .sortedWith(compareBy({ ruleScore(it) }, { state.items.getValue(it.id).due }))
        take(due, limitPerRule = true, limit = minOf(budget, MAX_REVIEWS))
        // 2. Items never seen, from the weakest rules first.
        val unseen = pool.filter { it.id !in state.items }.shuffled(random).sortedBy { ruleScore(it) }
        take(unseen, limitPerRule = true)
        // 3. Anything else, closest to its review date first.
        val rest = pool.filter { it !in chosen }.shuffled(random)
            .sortedBy { state.items[it.id]?.due ?: Long.MAX_VALUE }
        take(rest, limitPerRule = false)

        return SessionPlan(newRules, newItems + reviews.shuffled(random))
    }
}
