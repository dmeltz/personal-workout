package com.dmeltz.personalworkout

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.dmeltz.personalworkout.core.AppState
import com.dmeltz.personalworkout.core.DayKind
import com.dmeltz.personalworkout.core.History
import com.dmeltz.personalworkout.core.SessionBuilder
import com.dmeltz.personalworkout.core.Sessions
import com.dmeltz.personalworkout.reminder.Reminders
import com.dmeltz.personalworkout.sync.AnswerSync
import com.dmeltz.personalworkout.ui.AppTheme
import com.dmeltz.personalworkout.ui.ExerciseScreen
import com.dmeltz.personalworkout.ui.HomeScreen
import com.dmeltz.personalworkout.ui.HistoryScreen
import com.dmeltz.personalworkout.ui.ProgressScreen
import com.dmeltz.personalworkout.ui.SessionScreen
import com.dmeltz.personalworkout.ui.SettingsScreen
import com.dmeltz.personalworkout.widget.StreakWidget

sealed interface Screen {
    data object Home : Screen
    data class Session(val openedAt: Long) : Screen
    data object Progress : Screen
    data class Exercise(val id: String) : Screen
    data object Settings : Screen
    data object History : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so the status bar always gets light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val repo = Repository.get(this)
        Reminders.schedule(this)

        setContent {
            AppTheme {
                var state by remember {
                    // A session whose last answer was given while the app was closed still counts.
                    mutableStateOf(Sessions.settle(repo.library, repo.state).also { if (it != repo.state) repo.save(it) })
                }
                var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
                val screen = stack.last()

                fun push(s: Screen) { stack = stack + s }
                fun update(newState: AppState) {
                    val finishedSession = newState.sessionsCompleted > state.sessionsCompleted
                    repo.save(newState)
                    state = newState
                    if (finishedSession) AnswerSync.schedule(this@MainActivity)
                }
                fun pop() {
                    if (stack.size <= 1) return
                    if (stack.last() is Screen.Session) {
                        update(Sessions.settle(repo.library, state))
                        StreakWidget.updateAll(this@MainActivity)
                    }
                    stack = stack.dropLast(1)
                }

                val askNotifications = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                BackHandler(enabled = stack.size > 1) { pop() }

                key(screen) { when (screen) {
                    Screen.Home -> HomeScreen(
                        state = state,
                        library = repo.library,
                        onStart = { anyway ->
                            val day = today()
                            val kind = if (anyway) DayKind.NORMAL else SessionBuilder.dayKind(state, day)
                            val plan = SessionBuilder.build(repo.library, state, day, kind)
                            update(Sessions.start(repo.library, state, plan, day))
                            push(Screen.Session(System.currentTimeMillis()))
                        },
                        onState = {
                            update(it)
                            StreakWidget.updateAll(this@MainActivity)
                        },
                        onContinue = { push(Screen.Session(System.currentTimeMillis())) },
                        onProgress = { push(Screen.Progress) },
                        onSettings = { push(Screen.Settings) },
                    )
                    is Screen.Session -> SessionScreen(
                        library = repo.library,
                        initialState = state,
                        history = remember(screen) { repo.records() },
                        onState = ::update,
                        onRecord = repo::appendRecord,
                        onRecordChanged = repo::replaceRecord,
                        onRecordsDeleted = repo::deleteRecords,
                        onFinished = {
                            StreakWidget.updateAll(this@MainActivity)
                            stack = listOf(Screen.Home)
                        },
                        onQuit = ::pop,
                    )
                    Screen.Progress -> ProgressScreen(
                        state = state,
                        library = repo.library,
                        records = remember(screen, state) { repo.records() },
                        onState = ::update,
                        onExercise = { push(Screen.Exercise(it)) },
                        onHistory = { push(Screen.History) },
                        onBack = ::pop,
                    )
                    is Screen.Exercise -> ExerciseScreen(
                        exercise = repo.library.exercise(screen.id)!!,
                        state = state,
                        library = repo.library,
                        records = remember(screen.id) { repo.records().filter { it.exerciseId == screen.id } },
                        onState = ::update,
                        onBack = ::pop,
                    )
                    Screen.History -> {
                        var records by remember { mutableStateOf(repo.records()) }
                        HistoryScreen(
                            library = repo.library,
                            records = records,
                            onEdit = { r, done, effort ->
                                val (newState, record) = History.edit(repo.library, state, records, r, done, effort)
                                repo.replaceRecord(record)
                                update(newState)
                                records = repo.records()
                            },
                            onDelete = { gone ->
                                val newState = History.delete(repo.library, state, records, gone)
                                repo.deleteRecords(gone)
                                update(newState)
                                records = repo.records()
                                StreakWidget.updateAll(this@MainActivity)
                            },
                            onBack = ::pop,
                        )
                    }
                    Screen.Settings -> SettingsScreen(
                        state = state,
                        library = repo.library,
                        version = BuildConfigInfo.versionName(this@MainActivity),
                        onState = {
                            update(it)
                            Reminders.schedule(this@MainActivity)
                        },
                        onExport = ::shareExport,
                        onBack = ::pop,
                    )
                } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        StreakWidget.updateAll(this)
    }

    private fun shareExport() {
        val file = Repository.get(this).writeExport()
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share progress file"))
    }
}
