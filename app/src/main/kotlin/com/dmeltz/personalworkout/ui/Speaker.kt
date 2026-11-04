package com.dmeltz.personalworkout.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** Reads text aloud. Text asked for before the engine is ready is read once it is. */
class Speaker(context: Context) {
    private lateinit var tts: TextToSpeech
    @Volatile private var ready = false
    @Volatile private var pending: String? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                ready = true
                pending?.let(::speakNow)
                pending = null
            }
        }
    }

    /** Reads [text], cutting off anything still being read. */
    fun say(text: String) {
        if (ready) speakNow(text) else pending = text
    }

    /** True while something is being read, or waits to be read once the engine is ready. */
    val speaking: Boolean get() = pending != null || (ready && tts.isSpeaking)

    fun stop() {
        pending = null
        if (ready) tts.stop()
    }

    fun shutdown() = tts.shutdown()

    // "30 s" reads better as "30 seconds".
    private fun speakNow(text: String) {
        tts.speak(text.replace(Regex("""(\d+) s\b"""), "$1 seconds"), TextToSpeech.QUEUE_FLUSH, null, "say")
    }
}

/** A [Speaker] for the life of the calling screen. */
@Composable
fun rememberSpeaker(): Speaker {
    val context = LocalContext.current
    val speaker = remember { Speaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    return speaker
}
