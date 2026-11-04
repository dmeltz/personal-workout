package com.dmeltz.personalworkout.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dmeltz.personalworkout.Updater
import kotlinx.coroutines.launch
import java.io.File

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object Unknown : UpdateState
    data class Available(val build: Int, val url: String) : UpdateState
    data object Downloading : UpdateState
    data class Ready(val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** The last check result, kept for the app's lifetime so the home screen checks only once. */
private var lastCheck: Updater.Check? = null

/**
 * Update checking and installing.
 *
 * On the home screen ([compact]) it checks once in the background and appears only when a newer
 * build exists. In settings it shows a "Check for updates" button and every outcome.
 */
@Composable
fun UpdatePanel(compact: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember {
        mutableStateOf<UpdateState>(
            when (val c = lastCheck) {
                is Updater.Check.Available -> UpdateState.Available(c.build, c.apkUrl)
                else -> UpdateState.Idle
            },
        )
    }

    fun check() {
        state = UpdateState.Checking
        scope.launch {
            val c = Updater.check(context)
            lastCheck = c
            state = when (c) {
                is Updater.Check.Available -> UpdateState.Available(c.build, c.apkUrl)
                Updater.Check.UpToDate -> UpdateState.UpToDate
                Updater.Check.Unknown -> UpdateState.Unknown
            }
        }
    }

    fun install(apk: File) {
        if (Updater.canInstall(context)) {
            Updater.install(context, apk)
        } else {
            Updater.askInstallPermission(context)
        }
        state = UpdateState.Ready(apk)
    }

    fun download(url: String) {
        state = UpdateState.Downloading
        scope.launch {
            state = try {
                val apk = Updater.download(context, url)
                install(apk)
                UpdateState.Ready(apk)
            } catch (e: Exception) {
                UpdateState.Failed(e.message ?: "Download failed")
            }
        }
    }

    if (compact) {
        LaunchedEffect(Unit) { if (lastCheck == null) check() }
        val s = state
        if (s !is UpdateState.Available && s !is UpdateState.Downloading && s !is UpdateState.Ready &&
            s !is UpdateState.Failed
        ) return
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (val s = state) {
                UpdateState.Idle -> Button(onClick = ::check) { Text("Check for updates") }
                UpdateState.Checking -> Text("Checking for updates...")
                UpdateState.UpToDate -> {
                    Text("You have the latest version (build ${Updater.currentBuild(context)}).")
                    OutlinedButton(onClick = ::check) { Text("Check again") }
                }
                UpdateState.Unknown -> {
                    Text(
                        "The app can't check for updates by itself right now (no internet, or the " +
                            "GitHub repository is private). You can download the newest version from the release page.",
                    )
                    Button(onClick = { Updater.openReleasePage(context) }) { Text("Open download page") }
                }
                is UpdateState.Available -> {
                    Text("A new version is ready (build ${s.build}).", fontWeight = FontWeight.Bold)
                    Text("Your progress is kept.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { download(s.url) }) { Text("Download and install") }
                }
                UpdateState.Downloading -> {
                    CircularProgressIndicator()
                    Text("Downloading...")
                }
                is UpdateState.Ready -> {
                    Text(
                        if (Updater.canInstall(context)) "Downloaded. Tap Install if the installer didn't open."
                        else "Allow \"Personal Workout\" to install apps on the screen that opened, then come back and tap Install.",
                    )
                    Button(onClick = { install(s.apk) }) { Text("Install") }
                }
                is UpdateState.Failed -> {
                    Text("The download didn't work: ${s.message}")
                    Button(onClick = { Updater.openReleasePage(context) }) { Text("Open download page") }
                }
            }
        }
    }
}
