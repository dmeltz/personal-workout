package com.dmeltz.personalworkout.core

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoreTest {
    private val sample = """
        @rule SP01
        category: spelling
        title: Doubling
        reminder: Double it.
        example: run → running
        T: הם הפסיקו לדבר = They [stopped] talking.
        L: [beginning]
        F: הוא רץ | He is runing. = He is [running]. #GR04

        @rule GR04
        category: grammar
        title: Present continuous
        reminder: Now: am / is / are + -ing.
        T: אני עובד עכשיו = I am working now. || I am working at the moment.
    """.trimIndent()

    private val curriculum = CurriculumParser.parse(sample)

    @Test fun parsesRulesAndItems() {
        assertEquals(listOf("SP01", "GR04"), curriculum.rules.map { it.id })
        assertEquals(4, curriculum.items.size)
        val fix = curriculum.item("SP01-03")!!
        assertEquals(ExerciseType.FIX, fix.type)
        assertEquals("He is runing.", fix.wrong)
        assertEquals(listOf("SP01", "GR04"), fix.ruleIds)
        assertEquals("He is running.", fix.displayAnswer)
        assertEquals(2, curriculum.item("GR04-01")!!.answers.size)
    }

    @Test fun acceptsCaseContractionsAndPunctuation() {
        val item = curriculum.item("GR04-01")!!
        assertTrue(AnswerChecker.check(item, "i'm working now").correct)
        assertTrue(AnswerChecker.check(item, "I’m working at the moment!").correct)
        assertFalse(AnswerChecker.check(item, "I working now").correct)
    }

    @Test fun keepsItsAndItsDifferent() {
        val a = AnswerChecker.canonicalTokens("It is too late.")
        val b = AnswerChecker.canonicalTokens("Its too late")
        assertTrue(a != b)
        assertEquals(a, AnswerChecker.canonicalTokens("It's too late"))
        assertEquals(AnswerChecker.canonicalTokens("I have lived here"), AnswerChecker.canonicalTokens("I've lived here"))
        assertTrue(AnswerChecker.canonicalTokens("He has two kids") != AnswerChecker.canonicalTokens("He's two kids"))
    }

    @Test fun focusPartDecidesRuleCredit() {
        val item = curriculum.item("SP01-01")!!
        val typo = AnswerChecker.check(item, "They stopped talkng")
        assertFalse(typo.correct)
        assertTrue(typo.ruleCorrect)
        val wrong = AnswerChecker.check(item, "They stoped talking")
        assertFalse(wrong.ruleCorrect)
        assertEquals(MistakeKind.MISSING_LETTER, wrong.mistake)
    }

    @Test fun classifiesMistakes() {
        assertEquals(MistakeKind.SWAPPED_LETTERS, AnswerChecker.classifyWord("receive", "recieve"))
        assertEquals(MistakeKind.EXTRA_LETTER, AnswerChecker.classifyWord("writing", "writting"))
        assertEquals(MistakeKind.WRONG_LETTER, AnswerChecker.classifyWord("separate", "seperate"))
        assertEquals(MistakeKind.WRONG_WORD, AnswerChecker.classifyWord("bought", "go"))
        val order = AnswerChecker.check(
            CurriculumParser.parse("@rule GR13\ncategory: grammar\ntitle: x\nT: x = I like it very much.").items[0],
            "I like very much it",
        )
        assertEquals(MistakeKind.WORD_ORDER, order.mistake)
    }

    @Test fun diffMarksMissingAndExtra() {
        val d = TextDiff.diff("recieve", "receive")
        assertEquals("receive", d.filter { it.kind != DiffKind.EXTRA }.joinToString("") { it.text })
        assertEquals("recieve", d.filter { it.kind != DiffKind.MISSING }.joinToString("") { it.text })
    }

    @Test fun schedulerIntervals() {
        var s = Scheduler.next(null, true, 100)
        assertEquals(101, s.due)
        s = Scheduler.next(s, true, 101)
        assertEquals(104, s.due)
        s = Scheduler.next(s, false, 104)
        assertEquals(0, s.level)
        assertEquals(105, s.due)
    }

    @Test fun masteryMovesAndDecays() {
        var stats = RuleStats(introducedOn = 0)
        assertEquals(0.5, Mastery.score(stats, 0), 1e-9)
        repeat(6) { stats = Mastery.update(stats, true, 0) }
        val fresh = Mastery.score(stats, 0)
        assertTrue(fresh > 0.8)
        assertTrue(Mastery.score(stats, 60) < fresh)
        assertTrue(Mastery.score(stats, 1000) >= fresh * 0.6 - 1e-9)
        stats = Mastery.update(stats, false, 0)
        assertTrue(Mastery.score(stats, 0) < fresh)
    }

    @Test fun streakAndGrade() {
        var state = AppState()
        state = Progress.introduceRule(state, "SP01", 10)
        state = Progress.completeSession(state, 10)
        state = Progress.completeSession(state.copy(), 11)
        assertEquals(2, state.streak(11))
        assertEquals(2, state.streak(12))
        assertEquals(0, state.streak(13))
        assertEquals(50, state.averageGrade(11))
        assertEquals(listOf(10L, 11L), state.gradeHistory.map { it.day })
    }

    @Test fun firstSessionStartsTwoRulesAndFillsUp() {
        val text = buildString {
            for (r in listOf("SP01", "SP02", "GR01")) {
                appendLine("@rule $r\ncategory: ${if (r.startsWith("SP")) "spelling" else "grammar"}\ntitle: $r")
                repeat(10) { appendLine("L: word$r$it") }
            }
        }
        val c = CurriculumParser.parse(text)
        val plan = SessionBuilder.build(c, AppState(), 0, Random(1))
        assertEquals(listOf("SP01", "GR01"), plan.newRules.map { it.id })
        assertEquals(SessionBuilder.SESSION_SIZE, plan.items.size)
        assertEquals(plan.items.size, plan.items.toSet().size)

        var state = plan.newRules.fold(AppState()) { s, r -> Progress.introduceRule(s, r.id, 0) }
        assertTrue(SessionBuilder.newRules(c, state, 1).isEmpty())
        assertEquals(listOf("SP02"), SessionBuilder.newRules(c, state, 2).map { it.id })
        state = Progress.completeSession(state, 0)
        assertEquals(SessionBuilder.SESSION_SIZE, SessionBuilder.build(c, state, 1, Random(2)).items.size)
    }

    @Test fun bundledCurriculumIsValid() {
        val file = File("../app/src/main/assets/curriculum.txt")
        if (!file.exists()) return
        val c = CurriculumParser.parse(file.readText(), File("../app/src/main/assets/rule_details.txt").readText())
        assertEquals(33, c.rules.size)
        for (rule in c.rules) {
            val d = c.details(rule.id)
            assertNotNull(d, "${rule.id} has no details")
            assertTrue(d.paragraphs.size >= 2 && d.good.size >= 4 && d.bad.size >= 4, "${rule.id} details too short")
        }
        for (rule in c.rules) {
            val items = c.itemsFor(rule.id)
            assertTrue(items.size >= 12, "${rule.id} has only ${items.size} items")
            assertTrue(rule.reminder.isNotBlank(), "${rule.id} has no reminder")
        }
        val problems = mutableListOf<String>()
        for (item in c.items) {
            // Every accepted answer must be accepted by the checker as itself.
            for (answer in item.answers) {
                if (!AnswerChecker.check(item, stripFocus(answer)).correct) problems += "${item.id}: not accepted: $answer"
                // A focus part must survive contraction folding, or a typo elsewhere could never be told apart.
                val tokens = AnswerChecker.canonicalTokens(answer)
                for (focus in Regex("""\[([^\]]+)]""").findAll(answer).map { it.groupValues[1] }) {
                    val f = AnswerChecker.canonicalTokens(focus)
                    if (tokens.windowed(f.size).none { it == f }) problems += "${item.id}: focus [$focus] in: $answer"
                }
            }
            // A hint must not give away a tested part.
            item.hint?.let { hint ->
                for (focus in Regex("""\[([^\]]+)]""").findAll(item.answers.first()).map { it.groupValues[1] }) {
                    if (Regex("""\b${Regex.escape(focus.lowercase())}\b""").containsMatchIn(hint.lowercase())) {
                        problems += "${item.id}: hint gives away [$focus]"
                    }
                }
            }
            assertNotNull(item.answers.firstOrNull())
            assertEquals(
                item.answers.first().count { it == '[' }, item.answers.first().count { it == ']' }, item.id,
            )
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test fun fixItemsAcceptJustTheCorrectedPart() {
        val c = CurriculumParser.parse(
            """
            @rule SP01
            category: spelling
            title: t
            reminder: r
            F: א | I prefered tea. = I [preferred] tea.
            F: ב | They are comeing tomorrow. = They are [coming] tomorrow.
            T: ג = I [preferred] tea.
            """.trimIndent(),
        )
        val fix = c.item("SP01-01")!!
        for (typed in listOf("I preferred tea.", "i preferred", "preferred", "preferred tea")) {
            assertTrue(AnswerChecker.check(fix, typed).correct, typed)
        }
        for (typed in listOf("i prefered", "tea", "I", "preferred coffee")) {
            assertFalse(AnswerChecker.check(fix, typed).correct, typed)
        }
        assertTrue(AnswerChecker.check(c.item("SP01-02")!!, "they are coming").correct)
        assertFalse(AnswerChecker.check(c.item("SP01-02")!!, "they are").correct)
        // Translations still need the whole sentence.
        assertFalse(AnswerChecker.check(c.item("SP01-03")!!, "preferred").correct)
    }

    @Test fun hintsAndFirstLetters() {
        val c = CurriculumParser.parse(
            """
            @rule GR01
            category: grammar
            title: t
            reminder: r
            T: היא מדברת שלוש שפות = She [speaks] three languages. ~ use: speak #GR02
            L: [doesn't]
            F: א | He don't know. = He [doesn't] know.
            @rule GR02
            category: grammar
            title: t2
            reminder: r2
            """.trimIndent(),
        )
        val t = c.item("GR01-01")!!
        assertEquals("use: speak", t.hint)
        assertEquals(listOf("GR01", "GR02"), t.ruleIds)
        assertEquals(listOf("She [speaks] three languages."), t.answers)
        assertEquals("S… s… t… l…", t.firstLetters)
        assertEquals(null, c.item("GR01-03")!!.hint)
        assertEquals("H… d… k…", c.item("GR01-03")!!.firstLetters)
    }

    @Test fun pipGetsAngrierThroughTheDay() {
        val state = AppState(reminderHour = 20)
        assertEquals(Mood.CALM, Mascot.mood(state, 5, 9))
        assertEquals(Mood.WAITING, Mascot.mood(state, 5, 13))
        assertEquals(Mood.ANNOYED, Mascot.mood(state, 5, 18))
        assertEquals(Mood.ANGRY, Mascot.mood(state, 5, 21))
        val done = Progress.completeSession(state, 5)
        assertEquals(Mood.HAPPY, Mascot.mood(done, 5, 23))
        assertEquals(Mood.CALM, Mascot.mood(done, 6, 8))
        assertEquals(Mascot.greeting(Mood.ANGRY, 3, 5), Mascot.greeting(Mood.ANGRY, 3, 5))
    }

    @Test fun sessionCanBeContinuedAndCountsProgress() {
        val plan = SessionPlan(listOf(curriculum.rule("SP01")), curriculum.itemsFor("SP01"))
        var state = Sessions.start(AppState(), plan, 3)
        assertTrue(state.ruleStats("SP01").introduced)
        assertEquals(3, state.sessionToContinue(3)!!.plannedCount)

        val first = curriculum.item(state.activeSession!!.next()!!.itemId)!!
        state = Sessions.answer(state, first, AnswerChecker.check(first, "wrong"), 3)
        val s = state.sessionToContinue(3)!!
        assertEquals(1, s.answeredCount)
        assertEquals(4, s.entries.size)
        assertTrue(s.entries.last().retry)
        assertEquals(null, state.sessionToContinue(4))

        while (state.activeSession!!.next() != null) {
            val item = curriculum.item(state.activeSession!!.next()!!.itemId)!!
            state = Sessions.answer(state, item, AnswerChecker.check(item, item.displayAnswer), 3)
        }
        assertEquals(3, state.activeSession!!.answeredCount)
        assertEquals(2, state.activeSession!!.firstTryCorrect)
        assertEquals(null, state.sessionToContinue(3))
        state = Sessions.finish(state, 3)
        assertEquals(null, state.activeSession)
        assertTrue(state.practicedOn(3))
    }
}
