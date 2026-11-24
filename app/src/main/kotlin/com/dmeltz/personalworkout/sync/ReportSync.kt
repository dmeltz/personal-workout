package com.dmeltz.personalworkout.sync

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Issue and improvement reports from the in-app Report button: a screenshot of the screen plus a short note.
 * Each report is kept on the phone until it reaches the private GitHub repository (same repository and token
 * as the history), as reports/<time>-<screen>.md and .png, where Claude reads it when asked to check a report.
 */
object ReportSync {
    const val FOLDER = "reports"
    private const val WORK_NAME = "send-reports"
    private const val NOTE = "report.md"
    private const val IMAGE = "screen.png"

    private fun queue(context: Context) = File(context.filesDir, "report-queue").apply { mkdirs() }

    /** Reports still waiting to be sent. */
    fun pending(context: Context): Int = queue(context).listFiles()?.count { it.isDirectory && File(it, NOTE).exists() } ?: 0

    /** Keeps the report on the phone, then sends it as soon as there's a connection. */
    suspend fun submit(
        context: Context,
        screenshot: Bitmap?,
        note: String,
        screen: String,
        details: String?,
        appVersion: String,
    ) = withContext(Dispatchers.IO) {
        val now = ZonedDateTime.now()
        val slug = screen.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "screen" }
        val name = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")) + "-" + slug
        val dir = File(queue(context), name).apply { mkdirs() }
        if (screenshot != null) {
            File(dir, IMAGE).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val text = buildString {
            appendLine("# Report: $screen")
            appendLine()
            appendLine("- When: ${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx"))}")
            appendLine("- App build: $appVersion")
            appendLine("- Screen: $screen")
            if (!details.isNullOrBlank()) appendLine("- Where on the screen: $details")
            appendLine()
            appendLine("## Note")
            appendLine()
            appendLine(note.trim().ifEmpty { "(no note)" })
            if (screenshot != null) {
                appendLine()
                appendLine("![Screenshot]($name.png)")
            }
        }
        // Written last: a folder with the note is a complete report.
        File(dir, NOTE).writeText(text)
        schedule(context)
    }

    sealed interface Result {
        data object Sent : Result
        data class Retry(val message: String) : Result
        data class Failed(val message: String) : Result
    }

    /** Sends every waiting report, oldest first. */
    suspend fun sendAll(context: Context): Result = withContext(Dispatchers.IO) {
        val settings = AnswerSync.load(context)
        if (!settings.configured) return@withContext Result.Failed("Not set up")
        val reports = queue(context).listFiles()?.filter { it.isDirectory && File(it, NOTE).exists() }?.sortedBy { it.name }
            ?: emptyList()
        for (dir in reports) {
            val result = try {
                sendOne(settings, dir)
            } catch (e: Exception) {
                Result.Retry("No connection")
            }
            if (result != Result.Sent) return@withContext result
            dir.deleteRecursively()
        }
        Result.Sent
    }

    private fun sendOne(settings: SyncSettings, dir: File): Result {
        val name = dir.name
        val image = File(dir, IMAGE)
        // The picture first, so the note never points at a picture that isn't there yet.
        if (image.exists()) {
            val r = put(settings, "$FOLDER/$name.png", image.readBytes(), "Report screenshot $name")
            if (r != Result.Sent) return r
        }
        return put(settings, "$FOLDER/$name.md", File(dir, NOTE).readBytes(), "Report $name")
    }

    private fun put(settings: SyncSettings, path: String, bytes: ByteArray, message: String): Result {
        val conn = (URL("https://api.github.com/repos/${settings.repo.trim()}/contents/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer ${settings.token.trim()}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Content-Type", "application/json")
        }
        val body = buildJsonObject {
            put("message", JsonPrimitive(message))
            put("content", JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)))
        }.toString()
        conn.outputStream.use { it.write(body.toByteArray()) }
        return when (val code = conn.responseCode) {
            200, 201 -> Result.Sent
            // Already there: an earlier try got it up but lost the answer.
            422 -> Result.Sent.also { conn.errorStream?.close() }
            401 -> Result.Failed("GitHub didn't accept the token")
            403, 404 -> Result.Failed("Can't write to ${settings.repo.trim()}")
            else -> Result.Retry("GitHub answered $code")
        }
    }

    /** Sends waiting reports in the background once there is a connection, retrying if it fails. */
    fun schedule(context: Context) {
        if (pending(context) == 0 || !AnswerSync.load(context).configured) return
        val request = OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = when (sendAll(applicationContext)) {
            ReportSync.Result.Sent -> Result.success()
            is ReportSync.Result.Retry -> if (runAttemptCount < 10) Result.retry() else Result.failure()
            is ReportSync.Result.Failed -> Result.failure()
        }
    }
}
