package com.dmeltz.personalworkout.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
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
 *
 * While it speaks, other apps' audio (music from YouTube Music, Spotify and so on) is turned down,
 * and it comes back up when it stops.
 */
class Speaker(context: Context) {
    private lateinit var tts: TextToSpeech
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { }
        .build()
    private val main = Handler(Looper.getMainLooper())
    private var ducked = false
    // The music comes back a moment after the last sentence, not between sentences.
    private val release = Runnable {
        if (!speaking) {
            audio?.abandonAudioFocusRequest(focus)
            ducked = false
        }
    }
    @Volatile private var ready = false
    /**
     * Sentences not read yet, in order; the first is the one being read. Each carries the id it was
     * last handed to the engine with, so only that utterance finishing removes it: a stale "done"
     * from an utterance that was cut off or replaced never drops a sentence that wasn't read.
     */
    private class Sentence(val text: String, var id: String = "")
    private val queue = ArrayDeque<Sentence>()
    private var counter = 0

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setAudioAttributes(attributes)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId?.startsWith(SENTENCE) == true) synchronized(queue) { queue.removeAll { it.id == utteranceId } }
                        main.removeCallbacks(release)
                        main.postDelayed(release, RELEASE_MS)
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
            queue.addAll(sentences(text).map { Sentence(it) })
        }
        if (ready) speakQueue(flush = true)
    }

    /** Reads a short [cue] now, then goes on with whatever it interrupted, from the start of that sentence. */
    fun interject(cue: String) {
        if (!ready) return
        duck()
        // New ids before the flush, so the cut-off sentence's late "done" can't drop it.
        val rest = renumbered()
        tts.speak(spoken(cue), TextToSpeech.QUEUE_FLUSH, null, "cue")
        enqueue(rest)
    }

    /** True while something is being read, or waits to be read. */
    val speaking: Boolean get() = synchronized(queue) { queue.isNotEmpty() } || (ready && tts.isSpeaking)

    fun stop() {
        synchronized(queue) { queue.clear() }
        if (ready) tts.stop()
        main.removeCallbacks(release)
        main.postDelayed(release, RELEASE_MS)
    }

    fun shutdown() {
        main.removeCallbacks(release)
        if (ducked) audio?.abandonAudioFocusRequest(focus)
        tts.shutdown()
    }

    /** Turns other apps' audio down until the speaking stops. */
    private fun duck() {
        main.removeCallbacks(release)
        if (!ducked) ducked = audio?.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun speakQueue(flush: Boolean) {
        val sentences = renumbered()
        if (sentences.isNotEmpty()) duck()
        if (flush) tts.stop()
        enqueue(sentences)
    }

    /** Gives every sentence not read yet a fresh utterance id, and returns them with it. */
    private fun renumbered(): List<Pair<String, String>> =
        synchronized(queue) { queue.onEach { it.id = SENTENCE + counter++ }.map { it.text to it.id } }

    private fun enqueue(sentences: List<Pair<String, String>>) =
        sentences.forEach { (text, id) -> tts.speak(spoken(text), TextToSpeech.QUEUE_ADD, null, id) }

    private companion object {
        const val SENTENCE = "sentence-"
        const val RELEASE_MS = 600L

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
