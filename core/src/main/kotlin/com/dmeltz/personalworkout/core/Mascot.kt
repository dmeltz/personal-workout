package com.dmeltz.personalworkout.core

import kotlin.random.Random

/** Pip, the coach. Calm in the morning, angrier as the day goes by without training. */
enum class Mood { HAPPY, CALM, WAITING, ANNOYED, ANGRY }

object Mascot {
    const val NAME = "Pip"

    /** Pip's mood at [hour] (0–23) on [today], given the state and the reminder time. */
    fun mood(state: AppState, today: Day, hour: Int): Mood {
        if (state.dayDone(today)) return Mood.HAPPY
        if (state.isRestDay(today)) return Mood.CALM
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

    fun greeting(mood: Mood, state: AppState, today: Day): String {
        val streak = state.streak(today)
        if (state.isCapoeira(today) && !state.trainedOn(today)) {
            return "Capoeira today: that counts for your streak. A short upper-body session is optional."
        }
        if (state.isRestDay(today) && !state.trainedOn(today)) {
            return pick(
                listOf(
                    "Rest day. Muscles grow while you recover. Your streak is safe.",
                    "Rest day today. A walk or some stretching is plenty.",
                ),
                today,
            )
        }
        return pick(
            when (mood) {
                Mood.HAPPY -> listOf(
                    "Done for today. Nice work!",
                    "That's today's training done. Rest up!",
                    "Good job today. Steady wins.",
                )
                Mood.CALM -> listOf(
                    "Good morning! Fifteen minutes of training?",
                    "Morning! A short workout now and the day is yours.",
                    "Hi! Ready for today's workout?",
                )
                Mood.WAITING -> listOf(
                    "Still waiting for today's workout...",
                    "It's afternoon already. Fifteen minutes?",
                    "Your exercises are ready when you are.",
                )
                Mood.ANNOYED -> listOf(
                    "Hey. The day is almost over. Workout time!",
                    "I'm getting impatient. Just 15 minutes!",
                    if (streak > 0) "Don't lose your $streak day streak!" else "Come on, let's start a streak today.",
                )
                Mood.ANGRY -> listOf(
                    if (streak > 0) "Your $streak day streak is in danger! Now, please!" else "It's late and we haven't trained. Now!",
                    "I am NOT happy. Fifteen minutes. Go!",
                    "Grrr. Workout. Right now.",
                )
            },
            today * 7 + mood.ordinal,
        )
    }

    fun newExercise(): String = "New exercise! Read the cues first, and start easy."

    fun logged(effort: Effort, seed: Long): String = pick(
        when (effort) {
            Effort.EASY -> listOf("Easy? Then next time is a bit harder.", "Strong! I'll raise the target.")
            Effort.OK -> listOf("Good, that's the right effort.", "Nice. A small step up next time.")
            Effort.HARD -> listOf("Hard is fine. We'll hold here until it feels OK.", "Well fought. Same target next time.")
            Effort.SKIPPED -> listOf("No problem. I'll make it easier next time.", "Skipped. I'll ease off this one.")
        },
        seed,
    )

    fun sessionDone(session: ActiveSession, library: Library): String {
        val up = session.levelledUp.mapNotNull { library.exercise(it)?.title }
        val base = when {
            up.isNotEmpty() -> "Level up on ${up.joinToString(" and ")}! Next time you'll try the harder version."
            session.skipped > 0 && session.skipped * 2 >= session.plannedCount -> "Tough day. Showing up counts, and I've eased things off."
            session.hard > session.easy -> "Solid work. Hard sessions build the most."
            else -> "Nice session. Steady progress is the goal."
        }
        return base
    }

    /** A comment on overall progress for the progress screen. */
    fun progressComment(state: AppState, library: Library, today: Day): String {
        val level = Progression.overallLevel(library, state)
            ?: return "Do your first workout and I'll start tracking how you're doing."
        val monthAgo = state.levelHistory.lastOrNull { it.day <= today - 28 }
        val trend = when {
            monthAgo == null -> " I'll show the trend after a few weeks."
            level - monthAgo.overall >= 0.1 -> " Up %.1f from a month ago.".format(level - monthAgo.overall)
            else -> " About the same as a month ago. That's fine; plateaus come and go."
        }
        val weakest = Group.entries.filter { it != Group.CONDITIONING }
            .mapNotNull { g -> Progression.groupLevel(library, state, g)?.let { g to it } }
            .minByOrNull { it.second }
        val focus = if (weakest != null) " Your weakest area is ${weakest.first.short.lowercase()}." else ""
        val weight = Body.change(state, today, 28)?.let { " Weight %+.1f kg over 4 weeks.".format(it) } ?: ""
        return "Overall level %.1f.".format(level) + trend + focus + weight
    }
}
