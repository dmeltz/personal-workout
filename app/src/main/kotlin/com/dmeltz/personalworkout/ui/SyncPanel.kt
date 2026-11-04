package com.dmeltz.personalworkout.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dmeltz.personalworkout.sync.AnswerSync
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Settings for sending the answers to a private GitHub repository after every practice. */
@Composable
fun SyncPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(AnswerSync.load(context)) }
    var repo by remember { mutableStateOf(settings.repo) }
    var token by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "After every practice, the app sends your progress and every answer to a private GitHub repository. " +
                "Claude reads it there when you ask for an update, and adjusts the practice to your mistakes.",
        )
        OutlinedTextField(
            value = repo,
            onValueChange = { repo = it },
            label = { Text("Private repository (owner/name)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(if (settings.token.isBlank()) "GitHub token" else "GitHub token (saved; type to replace)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !sending && repo.contains('/') && (token.isNotBlank() || settings.token.isNotBlank()),
                onClick = {
                    AnswerSync.save(
                        context,
                        settings.copy(repo = repo.trim(), token = token.trim().ifEmpty { settings.token }),
                    )
                    token = ""
                    sending = true
                    scope.launch {
                        AnswerSync.send(context)
                        settings = AnswerSync.load(context)
                        sending = false
                    }
                },
            ) { Text(if (sending) "Sending…" else "Save and send now") }
        }
        val status = when {
            sending -> null
            !settings.configured -> "Not set up yet."
            settings.lastError != null -> "Last try didn't work: ${settings.lastError}"
            settings.lastSentAt != null ->
                "Last sent " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(settings.lastSentAt!!)) + "."
            else -> "Saved. Answers will be sent after your next practice."
        }
        if (status != null) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
