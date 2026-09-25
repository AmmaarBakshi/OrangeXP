package com.orangexp.core.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holstrom's voice: the phone's text-to-speech engine with an offline voice
 * when one is installed. Text can be queued sentence by sentence while an
 * answer is still being generated.
 */
@Singleton
class VoiceOutput @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<String>()
    private val utteranceIds = AtomicInteger()
    private val inFlight = AtomicInteger()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** Replaces anything being said with [text]. */
    fun say(text: String) {
        stop()
        enqueue(text)
    }

    /** Adds [text] after what is already being said. */
    fun enqueue(text: String) {
        val clean = clean(text)
        if (clean.isBlank()) return
        val engine = engine()
        if (!ready) {
            synchronized(pending) { pending += clean }
            return
        }
        speakNow(engine, clean)
    }

    fun stop() {
        synchronized(pending) { pending.clear() }
        tts?.stop()
        inFlight.set(0)
        _speaking.value = false
    }

    /** Frees the engine; it is created again on next use. */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun engine(): TextToSpeech = tts ?: TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            configure()
            val queued = synchronized(pending) { pending.toList().also { pending.clear() } }
            queued.forEach { speakNow(tts ?: return@TextToSpeech, it) }
        }
    }.also { tts = it }

    private fun configure() {
        val engine = tts ?: return
        val locale = Locale.getDefault()
        if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
        // An installed voice for the language that works without a network, if there is one.
        runCatching {
            engine.voices
                ?.filter { it.locale.language == engine.voice?.locale?.language && !it.isNetworkConnectionRequired }
                ?.maxByOrNull { it.quality }
                ?.let { engine.voice = it }
        }
        engine.setSpeechRate(1.0f)
        engine.setPitch(0.95f)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _speaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                if (inFlight.decrementAndGet() <= 0) {
                    inFlight.set(0)
                    _speaking.value = false
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onDone(utteranceId)
        })
    }

    private fun speakNow(engine: TextToSpeech, text: String) {
        inFlight.incrementAndGet()
        _speaking.value = true
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "holstrom-${utteranceIds.incrementAndGet()}")
    }

    /** Markdown and symbols read badly aloud. */
    private fun clean(text: String): String = text
        .replace(Regex("[*_#`>|]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
