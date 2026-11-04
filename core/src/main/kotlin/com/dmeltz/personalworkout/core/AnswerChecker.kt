package com.dmeltz.personalworkout.core

enum class MistakeKind {
    MISSING_LETTER,
    EXTRA_LETTER,
    WRONG_LETTER,
    SWAPPED_LETTERS,
    MISSPELLED_WORD,
    WRONG_WORD,
    MISSING_WORD,
    EXTRA_WORD,
    WORD_ORDER,
    SEVERAL,
}

data class CheckResult(
    /** The typed answer matches an accepted answer. */
    val correct: Boolean,
    /** The part the rule tests was right, even if something else in the answer was wrong. */
    val ruleCorrect: Boolean,
    /** The accepted answer closest to what was typed, without focus brackets. */
    val expected: String,
    val mistake: MistakeKind?,
)

object AnswerChecker {

    fun check(item: Item, typed: String): CheckResult {
        val typedTokens = canonicalTokens(typed)
        var best: String = item.answers.first()
        var bestDistance = Int.MAX_VALUE
        for (answer in item.answers) {
            val tokens = canonicalTokens(answer)
            if (tokens == typedTokens) {
                return CheckResult(true, true, stripFocus(answer), null)
            }
            if (item.type == ExerciseType.FIX && isFixedPart(answer, tokens, typedTokens)) {
                return CheckResult(true, true, stripFocus(answer), null)
            }
            val d = levenshtein(tokens.joinToString(" "), typedTokens.joinToString(" "))
            if (d < bestDistance) {
                bestDistance = d
                best = answer
            }
        }
        val focusOk = focusParts(best).let { parts ->
            parts.isNotEmpty() && parts.all { containsSequence(typedTokens, canonicalTokens(it)) }
        }
        return CheckResult(
            correct = false,
            ruleCorrect = focusOk,
            expected = stripFocus(best),
            mistake = classify(canonicalTokens(best), typedTokens),
        )
    }

    /**
     * For fix items, just the corrected part is enough: a run of words from the answer ("i preferred")
     * that includes every tested part. An answer with no brackets still needs the whole sentence.
     */
    private fun isFixedPart(answer: String, tokens: List<String>, typed: List<String>): Boolean {
        val parts = focusParts(answer).map { canonicalTokens(it) }
        if (parts.isEmpty() || typed.isEmpty() || typed.size > tokens.size) return false
        return (0..tokens.size - typed.size).any { start ->
            tokens.subList(start, start + typed.size) == typed
        } && parts.all { containsSequence(typed, it) }
    }

    /** Bracketed parts of an answer. An answer with no brackets has no separate focus. */
    private fun focusParts(answer: String): List<String> =
        Regex("""\[([^\]]+)]""").findAll(answer).map { it.groupValues[1] }.toList()

    private fun containsSequence(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty()) return true
        for (start in 0..haystack.size - needle.size) {
            if (haystack.subList(start, start + needle.size) == needle) return true
        }
        return false
    }

    private val contractions: List<Pair<Regex, String>> = run {
        val pronouns = "i|you|we|they|he|she|it|there|that"
        // "has" and "have" only shorten as helper verbs ("he's been"), not for owning ("he has two kids").
        val participle = "(?=\\s(?:been|got|gotten|gone|done|seen|had|known|made|just|never|already|ever|always|" +
            "still|eaten|read|heard|lost|left|met|found|taught|bought|brought|thought|caught|told|said|\\w+ed|\\w+en)\\b)"
        listOf(
            """\bi am\b""" to "i'm",
            """\b(you|we|they) are\b""" to "$1're",
            """\b(he|she|it|there|that|what|who|where) is\b""" to "$1's",
            """\b(he|she|it|there|that) has$participle""" to "$1's",
            """\b(i|you|we|they) have$participle""" to "$1've",
            """\b($pronouns) will\b""" to "$1'll",
            """\b($pronouns) (would|had)\b""" to "$1'd",
            """\bwill not\b""" to "won't",
            """\bcan ?not\b""" to "can't",
            """\bshall not\b""" to "shan't",
            """\blet us\b""" to "let's",
            """\b(do|does|did|is|are|was|were|have|has|had|should|could|would|must|need) not\b""" to "$1n't",
        ).map { (pattern, replacement) -> Regex(pattern) to replacement }
    }

    /**
     * Lowercase, typographic quotes unified, punctuation dropped, and contractions folded
     * into one form, so "I am" and "I'm" compare equal. "its" and "it's" stay different.
     */
    fun canonicalTokens(s: String): List<String> {
        var t = stripFocus(s).lowercase()
            .replace('’', '\'').replace('‘', '\'').replace('`', '\'')
            .replace(Regex("""[.,!?;:"()“”]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        for ((regex, replacement) in contractions) t = regex.replace(t, replacement)
        return if (t.isEmpty()) emptyList() else t.split(" ")
    }

    private fun classify(expected: List<String>, typed: List<String>): MistakeKind {
        if (expected.size == typed.size) {
            if (expected.sorted() == typed.sorted()) return MistakeKind.WORD_ORDER
            val differing = expected.indices.filter { expected[it] != typed[it] }
            return if (differing.size == 1) {
                classifyWord(expected[differing[0]], typed[differing[0]])
            } else {
                MistakeKind.SEVERAL
            }
        }
        if (expected.size == typed.size + 1 && isSubsequence(typed, expected)) return MistakeKind.MISSING_WORD
        if (typed.size == expected.size + 1 && isSubsequence(expected, typed)) return MistakeKind.EXTRA_WORD
        return MistakeKind.SEVERAL
    }

    fun classifyWord(expected: String, typed: String): MistakeKind {
        val d = levenshtein(expected, typed)
        return when {
            typed.length == expected.length - 1 && d == 1 -> MistakeKind.MISSING_LETTER
            typed.length == expected.length + 1 && d == 1 -> MistakeKind.EXTRA_LETTER
            typed.length == expected.length && d == 1 -> MistakeKind.WRONG_LETTER
            typed.length == expected.length && d == 2 && isAdjacentSwap(expected, typed) -> MistakeKind.SWAPPED_LETTERS
            d <= maxOf(2, expected.length / 2) -> MistakeKind.MISSPELLED_WORD
            else -> MistakeKind.WRONG_WORD
        }
    }

    private fun isAdjacentSwap(a: String, b: String): Boolean {
        val diff = a.indices.filter { a[it] != b[it] }
        return diff.size == 2 && diff[1] == diff[0] + 1 && a[diff[0]] == b[diff[1]] && a[diff[1]] == b[diff[0]]
    }

    private fun <T> isSubsequence(short: List<T>, long: List<T>): Boolean {
        var i = 0
        for (x in long) if (i < short.size && short[i] == x) i++
        return i == short.size
    }

    fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }
}

enum class DiffKind { SAME, MISSING, EXTRA }

data class DiffSegment(val text: String, val kind: DiffKind)

/** Character diff between what was typed and the expected answer, for highlighting. Case is ignored. */
object TextDiff {
    fun diff(typed: String, expected: String): List<DiffSegment> {
        val a = typed.trim()
        val b = expected.trim()
        val n = a.length
        val m = b.length
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (a[i].lowercaseChar() == b[j].lowercaseChar()) lcs[i + 1][j + 1] + 1
            else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = mutableListOf<DiffSegment>()
        fun add(c: Char, kind: DiffKind) {
            val last = out.lastOrNull()
            if (last != null && last.kind == kind) out[out.size - 1] = last.copy(text = last.text + c)
            else out += DiffSegment(c.toString(), kind)
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i].lowercaseChar() == b[j].lowercaseChar() -> { add(b[j], DiffKind.SAME); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { add(a[i], DiffKind.EXTRA); i++ }
                else -> { add(b[j], DiffKind.MISSING); j++ }
            }
        }
        while (i < n) add(a[i++], DiffKind.EXTRA)
        while (j < m) add(b[j++], DiffKind.MISSING)
        return out
    }
}
