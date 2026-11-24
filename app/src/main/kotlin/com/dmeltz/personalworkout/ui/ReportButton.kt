package com.dmeltz.personalworkout.ui

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dmeltz.personalworkout.BuildConfigInfo
import com.dmeltz.personalworkout.sync.AnswerSync
import com.dmeltz.personalworkout.sync.ReportSync
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A small button on every screen for reporting a problem or an idea: it takes a picture of the screen,
 * asks for a few words, and sends both to the private GitHub repository for Claude to read.
 * Drag it if it's in the way.
 */
@Composable
fun BoxScope.ReportButton(screen: String, details: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dragX by rememberSaveable { mutableStateOf(0f) }
    var dragY by rememberSaveable { mutableStateOf(0f) }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var open by remember { mutableStateOf(false) }
    // What the screen was when the button was tapped, kept even if the screen moves on behind the window.
    var reportedScreen by remember { mutableStateOf(screen) }
    var reportedDetails by remember { mutableStateOf(details) }

    if (!open) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 8.dp, bottom = 8.dp)
                .offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) }
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0xCC1E2A47))
                .border(1.dp, Color(0x66FFFFFF), CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        dragX += amount.x
                        dragY += amount.y
                    }
                }
                .pointerInput(screen, details) {
                    detectTapGestures {
                        reportedScreen = screen
                        reportedDetails = details
                        capture(context as Activity) { bitmap ->
                            shot = bitmap
                            open = true
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Text("🐞", fontSize = 18.sp) }
    }

    if (open) {
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { open = false; shot = null },
            title = { Text("Report an issue or idea") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    shot?.let {
                        Image(
                            it.asImageBitmap(),
                            contentDescription = "Picture of the screen",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                    Text("Screen: $reportedScreen", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("What's wrong, or what would be better?") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = note.isNotBlank(),
                    onClick = {
                        val picture = shot
                        val text = note
                        open = false
                        shot = null
                        scope.launch {
                            ReportSync.submit(
                                context, picture, text, reportedScreen, reportedDetails,
                                BuildConfigInfo.versionName(context),
                            )
                            val message = if (AnswerSync.load(context).configured) {
                                "Report saved. It's sent to GitHub as soon as there's a connection."
                            } else {
                                "Report saved on the phone. Add the GitHub token in Settings to send it."
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    },
                ) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { open = false; shot = null }) { Text("Cancel") } },
        )
    }
}

/** Takes a picture of the whole app window, scaled to at most 1080 pixels wide. Null if it can't. */
private fun capture(activity: Activity, done: (Bitmap?) -> Unit) {
    val window = activity.window
    val view = window.decorView
    if (view.width == 0 || view.height == 0) return done(null)
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    try {
        PixelCopy.request(window, bitmap, { result ->
            if (result != PixelCopy.SUCCESS) return@request done(null)
            done(
                if (bitmap.width <= 1080) bitmap
                else Bitmap.createScaledBitmap(bitmap, 1080, bitmap.height * 1080 / bitmap.width, true),
            )
        }, Handler(Looper.getMainLooper()))
    } catch (e: Exception) {
        done(null)
    }
}
