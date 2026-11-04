package com.openobd.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks short sentences with the phone's text-to-speech engine. Music and navigation are lowered (ducked) while
 * it talks. The engine is created on first use and released after a while without speech.
 */
class Speaker(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<String>()
    private val ids = AtomicInteger()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()

    /** Language code of the app ("es", "de" ...); "system" follows the phone. */
    var language: String = "system"

    @Synchronized
    fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts ?: TextToSpeech(context.applicationContext) { status -> onReady(status == TextToSpeech.SUCCESS) }.also {
            it.setAudioAttributes(attributes)
            it.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (tts?.isSpeaking != true) audio?.abandonAudioFocusRequest(focus) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { audio?.abandonAudioFocusRequest(focus) }
            })
            tts = it
        }
        if (!ready) { pending += text; return }
        say(engine, text)
    }

    @Synchronized
    private fun onReady(ok: Boolean) {
        ready = ok
        val engine = tts ?: return
        if (!ok) { pending.clear(); return }
        val locale = com.openobd.ui.I18n.localeOf(language)
        if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
        pending.forEach { say(engine, it) }
        pending.clear()
    }

    private fun say(engine: TextToSpeech, text: String) {
        audio?.requestAudioFocus(focus)
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "openobd-" + ids.incrementAndGet())
    }

    @Synchronized
    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        pending.clear()
    }
}
