package com.dmeltz.personalworkout

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub for a newer build and installs it. Each CI build is released as "build-N" with
 * versionCode N, so a higher N is newer. The GitHub API only answers for a public repository;
 * otherwise the release page is opened in the browser instead.
 */
object Updater {
    private const val REPO = "dmeltz/personal-workout"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    sealed interface Check {
        data class Available(val build: Int, val apkUrl: String) : Check
        data object UpToDate : Check
        /** The API can't be reached or read (no internet, or a private repository). */
        data object Unknown : Check
    }

    fun currentBuild(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        }

    suspend fun check(context: Context): Check = withContext(Dispatchers.IO) {
        try {
            val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return@withContext Check.Unknown
            val release = Json.parseToJsonElement(conn.inputStream.bufferedReader().use { it.readText() }).jsonObject
            val build = release["tag_name"]?.jsonPrimitive?.content?.removePrefix("build-")?.toIntOrNull()
                ?: return@withContext Check.Unknown
            val apk = release["assets"]?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                ?.get("browser_download_url")?.jsonPrimitive?.content
                ?: return@withContext Check.Unknown
            if (build > currentBuild(context)) Check.Available(build, apk) else Check.UpToDate
        } catch (e: Exception) {
            Check.Unknown
        }
    }

    /** Downloads the APK into the app's cache. */
    suspend fun download(context: Context, url: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "personal-workout.apk")
        var conn = URL(url).openConnection() as HttpURLConnection
        // GitHub redirects release downloads to another host; follow it by hand to be safe.
        repeat(5) {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            val code = conn.responseCode
            if (code in 300..399) {
                conn = URL(conn.getHeaderField("Location")).openConnection() as HttpURLConnection
            } else {
                if (code != 200) error("Download failed ($code)")
                conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                return@withContext file
            }
        }
        error("Too many redirects")
    }

    /** True if Android already lets this app install updates. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows this app to install updates. */
    fun askInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openReleasePage(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }
}
