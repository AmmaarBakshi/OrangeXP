package com.orangexp.core.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SpeechEvent {
    /** The microphone is open. */
    data object Listening : SpeechEvent

    /** Input loudness, roughly 0..1, for the mic animation. */
    data class Level(val value: Float) : SpeechEvent

    data class Partial(val text: String) : SpeechEvent

    data class Final(val text: String) : SpeechEvent

    data class Failed(val reason: SpeechFailure) : SpeechEvent
}

enum class SpeechFailure { NO_PERMISSION, NOT_AVAILABLE, NOTHING_HEARD, NETWORK, BUSY, OTHER }

/**
 * Speech to text through Android's recognizer. The on-device recognizer is
 * used when the phone has one (Android 12+), so speech stays on the phone;
 * otherwise the system recognizer is asked to prefer offline recognition.
 */
@Singleton
class SpeechInput @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var active: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context) || onDeviceAvailable()

    /**
     * Listens once. Ends by itself after a pause in speech, or when [stop] is
     * called (release of a hold-to-talk button). Collect on any thread.
     */
    fun listen(preferOnDevice: Boolean = true): Flow<SpeechEvent> = callbackFlow {
        if (!hasPermission()) {
            trySend(SpeechEvent.Failed(SpeechFailure.NO_PERMISSION))
            close()
            awaitClose()
            return@callbackFlow
        }
        var usingOnDevice = preferOnDevice && onDeviceAvailable()
        var heardAnything = false
        lateinit var start: () -> Unit

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechEvent.Listening)
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                trySend(SpeechEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                bestOf(partialResults)?.let {
                    heardAnything = true
                    trySend(SpeechEvent.Partial(it))
                }
            }

            override fun onResults(results: Bundle?) {
                val text = bestOf(results)
                trySend(if (text.isNullOrBlank()) SpeechEvent.Failed(SpeechFailure.NOTHING_HEARD) else SpeechEvent.Final(text))
                close()
            }

            override fun onError(error: Int) {
                // The on-device recognizer may lack this language: fall back to the system one once.
                if (usingOnDevice && !heardAnything && error in ON_DEVICE_FALLBACK_ERRORS) {
                    usingOnDevice = false
                    // Not from inside the failing recognizer's own callback.
                    mainHandler.post { start() }
                    return
                }
                trySend(SpeechEvent.Failed(failureOf(error)))
                close()
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        start = {
            active?.destroy()
            val recognizer = if (usingOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            active = recognizer
            recognizer.setRecognitionListener(listener)
            recognizer.startListening(recognizerIntent())
        }
        start()

        awaitClose {
            active?.destroy()
            active = null
        }
    }.flowOn(Dispatchers.Main.immediate)

    /** Stops listening and delivers what was heard so far. */
    fun stop() {
        active?.stopListening()
    }

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

    private fun bestOf(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun failureOf(error: Int): SpeechFailure = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.NO_PERMISSION
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechFailure.NOTHING_HEARD
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> SpeechFailure.NETWORK
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> SpeechFailure.BUSY
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechFailure.NOT_AVAILABLE
        else -> SpeechFailure.OTHER
    }

    private companion object {
        val ON_DEVICE_FALLBACK_ERRORS = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
        )
    }
}
