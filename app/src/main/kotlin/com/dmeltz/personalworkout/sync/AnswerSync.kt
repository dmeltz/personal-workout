package com.dmeltz.personalworkout.sync

import android.content.Context
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dmeltz.personalworkout.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Where to send the history, and how the last attempt went. */
@Serializable
data class SyncSettings(
    val repo: String = AnswerSync.DEFAULT_REPO,
    val token: String = "",
    val lastSentAt: Long? = null,
    val lastError: String? = null,
) {
    val configured: Boolean get() = repo.contains('/') && token.isNotBlank()
}

/**
 * Sends the whole export (progress and every logged exercise) to a private GitHub repository, so Claude can
 * read it and adjust the app. The file is replaced each time; GitHub keeps every version in its history.
 */
object AnswerSync {
    const val DEFAULT_REPO = "dmeltz/my-practice-data"
    const val PATH = "progress/personal-workout.json"
    private const val WORK_NAME = "send-answers"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Kept out of Android backups, since it holds the token.
    private fun file(context: Context) = File(context.noBackupFilesDir, "sync.json")

    @Synchronized
    fun load(context: Context): SyncSettings = try {
        file(context).takeIf { it.exists() }?.let { json.decodeFromString<SyncSettings>(it.readText()) } ?: SyncSettings()
    } catch (e: Exception) {
        SyncSettings()
    }

    @Synchronized
    fun save(context: Context, settings: SyncSettings) {
        file(context).writeText(json.encodeToString(settings))
    }

    sealed interface Result {
        data object Sent : Result
        /** Worth trying again later: no connection, or GitHub had a problem. */
        data class Retry(val message: String) : Result
        /** Won't work until the settings change: wrong repository name or token. */
        data class Failed(val message: String) : Result
    }

    /** Sends the history now, and records the outcome in the settings. */
    suspend fun send(context: Context): Result = withContext(Dispatchers.IO) {
        val settings = load(context)
        if (!settings.configured) return@withContext Result.Failed("Not set up")
        val result = try {
            upload(settings, Repository.get(context).exportJson(), Repository.get(context).state.sessionsCompleted)
        } catch (e: Exception) {
            Result.Retry("No connection")
        }
        val latest = load(context)
        save(
            context,
            when (result) {
                Result.Sent -> latest.copy(lastSentAt = System.currentTimeMillis(), lastError = null)
                is Result.Retry -> latest.copy(lastError = result.message)
                is Result.Failed -> latest.copy(lastError = result.message)
            },
        )
        result
    }

    private fun connect(settings: SyncSettings, method: String): HttpURLConnection =
        (URL("https://api.github.com/repos/${settings.repo.trim()}/contents/$PATH").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer ${settings.token.trim()}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

    private fun failure(code: Int, repo: String): Result = when (code) {
        401 -> Result.Failed("GitHub didn't accept the token")
        403, 404 -> Result.Failed("Can't write to $repo: check the repository name and the token's access")
        in 500..599 -> Result.Retry("GitHub had a problem ($code)")
        else -> Result.Retry("GitHub answered $code")
    }

    private fun upload(settings: SyncSettings, content: String, sessions: Int): Result {
        fun fail(code: Int) = failure(code, settings.repo.trim())

        // The current file's id, needed to replace it. Missing on the first upload.
        val get = connect(settings, "GET")
        val sha = when (val code = get.responseCode) {
            200 -> Json.parseToJsonElement(get.inputStream.bufferedReader().use { it.readText() })
                .jsonObject["sha"]?.jsonPrimitive?.content
            404 -> null.also { get.errorStream?.close() }
            else -> return fail(code)
        }

        val body = buildJsonObject {
            put("message", JsonPrimitive("Personal Workout history after session $sessions"))
            put("content", JsonPrimitive(Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)))
            if (sha != null) put("sha", JsonPrimitive(sha))
        }.toString()
        val put = connect(settings, "PUT")
        put.doOutput = true
        put.setRequestProperty("Content-Type", "application/json")
        put.outputStream.use { it.write(body.toByteArray()) }
        return when (val code = put.responseCode) {
            200, 201 -> Result.Sent
            // The file changed in between (another upload); the retry reads the new id.
            409, 422 -> Result.Retry("The file changed during the upload")
            else -> fail(code)
        }
    }

    /** Sends the history in the background as soon as there is a connection, retrying if it fails. */
    fun schedule(context: Context) {
        if (!load(context).configured) return
        val request = OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        // Inside a worker, a bare "Result" means WorkManager's result, so the sync result is named in full.
        override suspend fun doWork(): Result = when (send(applicationContext)) {
            AnswerSync.Result.Sent -> Result.success()
            is AnswerSync.Result.Retry -> if (runAttemptCount < 8) Result.retry() else Result.failure()
            is AnswerSync.Result.Failed -> Result.failure()
        }
    }
}
