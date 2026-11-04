package com.dmeltz.personalworkout

import android.content.Context
import com.dmeltz.personalworkout.core.AnswerRecord
import com.dmeltz.personalworkout.core.AppState
import com.dmeltz.personalworkout.core.Curriculum
import com.dmeltz.personalworkout.core.CurriculumParser
import com.dmeltz.personalworkout.core.Day
import com.dmeltz.personalworkout.core.Mastery
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

fun today(): Day = LocalDate.now().toEpochDay()

/** Loads the curriculum and keeps the progress state and the answer log in the app's private files. */
class Repository private constructor(private val context: Context) {

    val curriculum: Curriculum by lazy {
        fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }
        CurriculumParser.parse(asset("curriculum.txt"), asset("rule_details.txt"))
    }

    private val stateFile = File(context.filesDir, "state.json")
    private val logFile = File(context.filesDir, "answers.jsonl")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    var state: AppState = load()
        private set

    private fun load(): AppState = try {
        if (stateFile.exists()) json.decodeFromString<AppState>(stateFile.readText()) else AppState()
    } catch (e: Exception) {
        // Keep the unreadable file for inspection instead of overwriting it.
        stateFile.copyTo(File(context.filesDir, "state-broken-${System.currentTimeMillis()}.json"), overwrite = true)
        AppState()
    }

    @Synchronized
    fun save(newState: AppState) {
        state = newState
        val tmp = File(context.filesDir, "state.json.tmp")
        tmp.writeText(json.encodeToString(newState))
        tmp.renameTo(stateFile)
    }

    @Synchronized
    fun appendAnswer(record: AnswerRecord) {
        logFile.appendText(json.encodeToString(record) + "\n")
    }

    fun answers(): List<AnswerRecord> {
        if (!logFile.exists()) return emptyList()
        return logFile.readLines().filter { it.isNotBlank() }.mapNotNull {
            runCatching { json.decodeFromString<AnswerRecord>(it) }.getOrNull()
        }
    }

    @Serializable
    data class RuleSummary(
        val id: String,
        val title: String,
        val started: Boolean,
        val scorePercent: Int,
        val answers: Int,
        val correctAnswers: Int,
    )

    @Serializable
    data class Export(
        val exportedAt: String,
        val appVersion: String,
        val streak: Int,
        val averageGrade: Int?,
        val rules: List<RuleSummary>,
        val state: AppState,
        val answers: List<AnswerRecord>,
    )

    /** Everything (progress and every answer) as one JSON document. */
    fun exportJson(): String {
        val day = today()
        val s = state
        val export = Export(
            exportedAt = LocalDateTime.now().toString(),
            appVersion = BuildConfigInfo.versionName(context),
            streak = s.streak(day),
            averageGrade = s.averageGrade(day),
            rules = curriculum.rules.map { rule ->
                val stats = s.ruleStats(rule.id)
                RuleSummary(
                    id = rule.id,
                    title = rule.title,
                    started = stats.introduced,
                    scorePercent = Math.round(Mastery.score(stats, day) * 100).toInt(),
                    answers = stats.answers,
                    correctAnswers = stats.correctAnswers,
                )
            },
            state = s,
            answers = answers(),
        )
        return Json { encodeDefaults = true; prettyPrint = true }.encodeToString(export)
    }

    /** Writes [exportJson] to a file for sharing. */
    fun writeExport(): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "personal-workout-${LocalDate.now()}.json")
        file.writeText(exportJson())
        return file
    }

    companion object {
        @Volatile
        private var instance: Repository? = null

        fun get(context: Context): Repository =
            instance ?: synchronized(this) {
                instance ?: Repository(context.applicationContext).also { instance = it }
            }
    }
}

object BuildConfigInfo {
    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
}

/** Pip's mood right now. */
fun currentMood(state: AppState): com.dmeltz.personalworkout.core.Mood =
    com.dmeltz.personalworkout.core.Mascot.mood(state, today(), java.time.LocalTime.now().hour)
