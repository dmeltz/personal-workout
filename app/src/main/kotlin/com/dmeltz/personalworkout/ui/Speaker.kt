package com.dmeltz.personalworkout.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Reads text aloud, a sentence at a time. Text asked for before the engine is ready is read once it
 * is. A short cue such as "Start set 1" can cut in with [interject]; the sentences it interrupted
 * are read after it.
 */
class Speaker(context: Context) {
    private lateinit var tts: TextToSpeech
    @Volatile private var ready = false
    /** Sentences not read yet; the first is the one being read. */
    private val queue = ArrayDeque<String>()
    private var counter = 0

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId?.startsWith(SENTENCE) == true) synchronized(queue) { queue.removeFirstOrNull() }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = onDone(utteranceId)
                })
                ready = true
                speakQueue(flush = true)
            }
        }
    }

    /** Reads [text], cutting off anything still being read. */
    fun say(text: String) {
        synchronized(queue) {
            queue.clear()
            queue.addAll(sentences(text))
        }
        if (ready) speakQueue(flush = true)
    }

    /** Reads a short [cue] now, then goes on with whatever it interrupted, from the start of that sentence. */
    fun interject(cue: String) {
        if (!ready) return
        tts.speak(spoken(cue), TextToSpeech.QUEUE_FLUSH, null, "cue")
        speakQueue(flush = false)
    }

    /** True while something is being read, or waits to be read. */
    val speaking: Boolean get() = synchronized(queue) { queue.isNotEmpty() } || (ready && tts.isSpeaking)

    fun stop() {
        synchronized(queue) { queue.clear() }
        if (ready) tts.stop()
    }

    fun shutdown() = tts.shutdown()

    private fun speakQueue(flush: Boolean) {
        val sentences = synchronized(queue) { queue.toList() }
        if (flush) tts.stop()
        sentences.forEach { tts.speak(spoken(it), TextToSpeech.QUEUE_ADD, null, SENTENCE + counter++) }
    }

    private companion object {
        const val SENTENCE = "sentence-"

        fun sentences(text: String): List<String> =
            text.split(Regex("""(?<=[.!?])\s+""")).map { it.trim() }.filter { it.isNotEmpty() }

        // "30 s" reads better as "30 seconds".
        fun spoken(text: String) = text.replace(Regex("""(\d+) s\b"""), "$1 seconds")
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
