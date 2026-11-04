package com.dmeltz.personalworkout

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Reads English text aloud with the phone's built-in text-to-speech. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pending: Pair<String, Boolean>? = null

    /** False when the phone has no usable English voice. */
    var available = true
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            available = false
            return
        }
        val result = tts.setLanguage(Locale.US)
        available = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        ready = true
        pending?.let { (text, slow) -> speak(text, slow) }
        pending = null
    }

    fun speak(text: String, slow: Boolean = false) {
        if (!ready) {
            pending = text to slow
            return
        }
        tts.setSpeechRate(if (slow) 0.55f else 0.9f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "item")
    }

    fun shutdown() = tts.shutdown()
}
