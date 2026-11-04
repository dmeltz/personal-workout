package com.dmeltz.personalworkout

import android.content.Context
import com.dmeltz.personalworkout.core.AppState
import com.dmeltz.personalworkout.core.Day
import com.dmeltz.personalworkout.core.Group
import com.dmeltz.personalworkout.core.Library
import com.dmeltz.personalworkout.core.LibraryParser
import com.dmeltz.personalworkout.core.Migrations
import com.dmeltz.personalworkout.core.Progression
import com.dmeltz.personalworkout.core.WorkoutRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

fun today(): Day = LocalDate.now().toEpochDay()

/** Loads the exercise library and keeps the progress state and the workout log in the app's private files. */
class Repository private constructor(private val context: Context) {

    val library: Library by lazy {
        LibraryParser.parse(context.assets.open("exercises.txt").bufferedReader().use { it.readText() })
    }

    private val stateFile = File(context.filesDir, "state.json")
    private val logFile = File(context.filesDir, "workouts.jsonl")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    var state: AppState = load()
        private set

    private fun load(): AppState = try {
        if (stateFile.exists()) Migrations.run(library, json.decodeFromString<AppState>(stateFile.readText()))
        else AppState(version = Migrations.CURRENT)
    } catch (e: Exception) {
        // Keep the unreadable file for inspection instead of overwriting it.
        stateFile.copyTo(File(context.filesDir, "state-broken-${System.currentTimeMillis()}.json"), overwrite = true)
        AppState(version = Migrations.CURRENT)
    }

    @Synchronized
    fun save(newState: AppState) {
        state = newState
        val tmp = File(context.filesDir, "state.json.tmp")
        tmp.writeText(json.encodeToString(newState))
        tmp.renameTo(stateFile)
    }

    @Synchronized
    fun appendRecord(record: WorkoutRecord) {
        logFile.appendText(json.encodeToString(record) + "\n")
    }

    fun records(): List<WorkoutRecord> {
        if (!logFile.exists()) return emptyList()
        return logFile.readLines().filter { it.isNotBlank() }.mapNotNull {
            runCatching { json.decodeFromString<WorkoutRecord>(it) }.getOrNull()
        }
    }

    @Serializable
    data class ExerciseSummary(
        val id: String,
        val title: String,
        val group: Group,
        val started: Boolean,
        val variation: String?,
        val target: Int?,
        val sets: Int?,
        val level: Double?,
        val times: Int,
    )

    @Serializable
    data class Export(
        val exportedAt: String,
        val appVersion: String,
        val streak: Int,
        val overallLevel: Double?,
        val groupLevels: Map<String, Double>,
        val exercises: List<ExerciseSummary>,
        val state: AppState,
        val workouts: List<WorkoutRecord>,
    )

    /** Everything (progress and every logged exercise) as one JSON document. */
    fun exportJson(): String {
        val day = today()
        val s = state
        val export = Export(
            exportedAt = LocalDateTime.now().toString(),
            appVersion = BuildConfigInfo.versionName(context),
            streak = s.streak(day),
            overallLevel = Progression.overallLevel(library, s),
            groupLevels = Group.entries.mapNotNull { g -> Progression.groupLevel(library, s, g)?.let { g.name to it } }.toMap(),
            exercises = library.exercises.map { ex ->
                val es = s.exerciseState(ex.id)
                ExerciseSummary(
                    id = ex.id,
                    title = ex.title,
                    group = ex.group,
                    started = es != null,
                    variation = es?.let { ex.variation(it.variation).name },
                    target = es?.target,
                    sets = es?.sets,
                    level = es?.let { Progression.level(ex, it) },
                    times = es?.times ?: 0,
                )
            },
            state = s,
            workouts = records(),
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
