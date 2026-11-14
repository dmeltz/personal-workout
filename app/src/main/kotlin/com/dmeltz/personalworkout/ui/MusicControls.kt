package com.dmeltz.personalworkout.ui

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Controls for whatever music app is playing (YouTube Music, Spotify, ...): previous song,
 * play or pause, next song. They send the same media keys as headphone buttons, so they work with
 * the app that last played music, with no sign-in or permission.
 */
@Composable
fun MusicControls(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Music", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        TextButton(onClick = { mediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }) { Text("⏮", fontSize = 18.sp) }
        TextButton(onClick = { mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }) { Text("⏯", fontSize = 18.sp) }
        TextButton(onClick = { mediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT) }) { Text("⏭", fontSize = 18.sp) }
    }
}

private fun mediaKey(context: Context, code: Int) {
    val audio = context.getSystemService(AudioManager::class.java) ?: return
    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
}
