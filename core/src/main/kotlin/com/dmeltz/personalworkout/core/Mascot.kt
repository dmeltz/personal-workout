package com.dmeltz.personalworkout.core

import kotlin.random.Random

/** Pip, the teacher character. Calm in the morning, angrier as the day goes by without practice. */
enum class Mood { HAPPY, CALM, WAITING, ANNOYED, ANGRY }

object Mascot {
    const val NAME = "Pip"

    /** Pip's mood at [hour] (0–23) on [today], given the state and the reminder time. */
    fun mood(state: AppState, today: Day, hour: Int): Mood {
        if (state.practicedOn(today)) return Mood.HAPPY
        val angryFrom = state.reminderHour.coerceIn(15, 23)
        return when {
            hour >= angryFrom -> Mood.ANGRY
            hour >= angryFrom - 3 -> Mood.ANNOYED
            hour >= 12 -> Mood.WAITING
            else -> Mood.CALM
        }
    }

    /** One line, picked the same way for the whole day so it doesn't change on every redraw. */
    private fun pick(lines: List<String>, seed: Long): String = lines[Random(seed).nextInt(lines.size)]

    fun greeting(mood: Mood, streak: Int, today: Day): String = pick(
        when (mood) {
            Mood.HAPPY -> listOf(
                "Done for today. Nice work!",
                "That's today's practice done. See you tomorrow!",
                "Good job today. Your brain thanks you.",
            )
            Mood.CALM -> listOf(
                "Good morning! Five minutes of English?",
                "Morning! A quick practice now and the day is yours.",
                "Hi! Ready for today's practice?",
            )
            Mood.WAITING -> listOf(
                "Still waiting for today's practice...",
                "It's afternoon already. Five minutes?",
                "I've got a few questions ready for you.",
            )
            Mood.ANNOYED -> listOf(
                "Hey. The day is almost over. Practice time!",
                "I'm getting impatient. Just 5 minutes!",
                if (streak > 0) "Don't lose your $streak day streak!" else "Come on, let's start a streak today.",
            )
            Mood.ANGRY -> listOf(
                if (streak > 0) "Your $streak day streak is in danger! NOW, please!" else "It's late and we haven't practiced. NOW!",
                "I am NOT happy. Five minutes. Go!",
                "Grrr. Practice. Right now.",
            )
        },
        today * 7 + mood.ordinal,
    )

    fun teaching(isNew: Boolean): String =
        if (isNew) "New rule! Read this, then I'll test you on it." else "Here's the rule again."

    fun correct(seed: Long): String = pick(
        listOf("Correct!", "Exactly right.", "Perfect!", "Yes! Well done.", "Spot on.", "Great, that's it."),
        seed,
    )

    fun wrong(ruleCorrect: Boolean, seed: Long): String =
        if (ruleCorrect) "Close! You got the rule right, just a slip somewhere else."
        else pick(
            listOf(
                "Not quite. Look at the difference.",
                "Almost. Here's the right way.",
                "That's a tricky one. Let's look.",
                "No worries, that's how we learn.",
            ),
            seed,
        )

    fun explaining(): String = "Let me explain this one properly, with examples."

    fun retry(): String = "Let's try this one again."

    fun sessionDone(correct: Int, total: Int, weakestRule: Rule?): String {
        val ratio = if (total == 0) 1.0 else correct.toDouble() / total
        val verdict = when {
            ratio >= 0.9 -> "Excellent! $correct of $total right."
            ratio >= 0.7 -> "Good work. $correct of $total right."
            ratio >= 0.5 -> "$correct of $total. We're getting there."
            else -> "$correct of $total. Tough day, but every mistake teaches you something."
        }
        return if (weakestRule != null && ratio < 1.0) {
            "$verdict I'll bring back \"${weakestRule.title}\" soon."
        } else verdict
    }

    /** A comment on overall performance for the progress screen. */
    fun progressComment(state: AppState, curriculum: Curriculum, today: Day): String {
        val grade = state.averageGrade(today) ?: return "Do your first practice and I'll start tracking how you're doing."
        val history = state.gradeHistory
        val weekAgo = history.lastOrNull { it.day <= today - 7 }
        val trend = when {
            weekAgo == null -> ""
            grade - weekAgo.grade >= 3 -> " Up ${grade - weekAgo.grade} points from a week ago. Keep it up!"
            weekAgo.grade - grade >= 3 -> " Down ${weekAgo.grade - grade} points from a week ago. Let's push back up."
            else -> " About the same as a week ago."
        }
        val started = curriculum.rules.filter { state.ruleStats(it.id).let { s -> s.introduced && s.reliable } }
        val weakest = started.minByOrNull { Mastery.score(state.ruleStats(it.id), today) }
        val focus = if (weakest != null && Mastery.score(state.ruleStats(weakest.id), today) < 0.7) {
            " Your weakest rule is \"${weakest.title}\"."
        } else ""
        return "Your average grade is $grade%.$trend$focus"
    }
}
