package com.orangexp.feature.holstrom.voice

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.orangexp.core.common.holstrom.HolstromIntents
import com.orangexp.core.data.repository.HolstromSettingsRepository
import com.orangexp.core.designsystem.theme.OrangeXpTheme
import com.orangexp.core.designsystem.theme.OxTheme
import com.orangexp.core.voice.SpeechEvent
import com.orangexp.core.voice.SpeechFailure
import com.orangexp.core.voice.SpeechInput
import com.orangexp.core.voice.VoiceOutput
import com.orangexp.feature.holstrom.HolstromOrb
import com.orangexp.feature.holstrom.MicButton
import com.orangexp.feature.holstrom.OrbState
import com.orangexp.feature.holstrom.R
import com.orangexp.feature.holstrom.brain.HolstromBrain
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A small sheet over whatever is on screen, including the lock screen: it
 * listens straight away, hands what it hears to Holstrom, speaks the answer
 * and closes itself. Opened by the widget, the Quick Settings tile, the app
 * shortcut and headset/assistant buttons.
 */
@AndroidEntryPoint
class VoiceCommandActivity : ComponentActivity() {

    private val viewModel: VoiceOverlayViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        setContent {
            OrangeXpTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) viewModel.listen() else viewModel.micDenied()
                }
                val listen = {
                    if (hasMic(this)) viewModel.listen() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                LaunchedEffect(Unit) { if (savedInstanceState == null) listen() }
                LaunchedEffect(state.finished) { if (state.finished) finish() }
                OverlaySheet(
                    state = state,
                    onMicStart = listen,
                    onMicStop = viewModel::stopListening,
                    onDismiss = { finish() },
                    onOpenApp = {
                        viewModel.cancelAutoClose()
                        openAppAfterUnlock()
                    },
                )
            }
        }
    }

    /** Tapping the widget or tile again while the sheet is open starts a new question. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (hasMic(this)) viewModel.listen()
    }

    /** Over the lock screen, the app itself needs an unlock first. */
    private fun openAppAfterUnlock() {
        val open = {
            HolstromIntents.openHolstrom(this)?.let(::startActivity)
            finish()
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        open()
                    }
                },
            )
        } else {
            open()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        viewModel.cancelAutoClose()
    }

    private fun hasMic(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

data class OverlayState(
    val listening: Boolean = false,
    val thinking: Boolean = false,
    val level: Float = 0f,
    val heard: String = "",
    val reply: String = "",
    val speaking: Boolean = false,
    val finished: Boolean = false,
) {
    val orb: OrbState
        get() = when {
            listening -> OrbState.LISTENING
            thinking -> OrbState.THINKING
            speaking -> OrbState.SPEAKING
            else -> OrbState.IDLE
        }
}

@HiltViewModel
class VoiceOverlayViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val speech: SpeechInput,
    private val voice: VoiceOutput,
    private val brain: HolstromBrain,
    private val settings: HolstromSettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(OverlayState())
    val state: StateFlow<OverlayState> = _state.asStateFlow()
    private var work: Job? = null
    private var autoClose: Job? = null

    init {
        viewModelScope.launch { voice.speaking.collect { speaking -> _state.update { it.copy(speaking = speaking) } } }
    }

    fun listen() {
        cancelAutoClose()
        brain.interrupt()
        work?.cancel()
        work = viewModelScope.launch {
            _state.update { it.copy(listening = true, heard = "", reply = "", level = 0f) }
            speech.listen().collect { event ->
                when (event) {
                    SpeechEvent.Listening -> Unit
                    is SpeechEvent.Level -> _state.update { it.copy(level = event.value) }
                    is SpeechEvent.Partial -> _state.update { it.copy(heard = event.text) }
                    is SpeechEvent.Final -> {
                        _state.update { it.copy(listening = false, heard = event.text) }
                        respond(event.text)
                    }
                    is SpeechEvent.Failed -> {
                        _state.update { it.copy(listening = false, reply = speechFailureText(context, event.reason)) }
                        closeSoon()
                    }
                }
            }
            _state.update { it.copy(listening = false) }
        }
    }

    fun stopListening() = speech.stop()

    fun micDenied() {
        _state.update { it.copy(reply = speechFailureText(context, SpeechFailure.NO_PERMISSION)) }
    }

    fun cancelAutoClose() {
        autoClose?.cancel()
    }

    private suspend fun respond(text: String) {
        _state.update { it.copy(thinking = true) }
        try {
            brain.handle(text, foreground = true).collect { reply ->
                _state.update { it.copy(thinking = reply.thinking, reply = reply.text) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("VoiceOverlay", "Holstrom failed", e)
            _state.update { it.copy(reply = e.message.orEmpty()) }
        } finally {
            _state.update { it.copy(thinking = false) }
        }
        closeSoon()
    }

    /** Closes a few seconds after Holstrom has finished speaking, unless the user touches the sheet. */
    private fun closeSoon() {
        autoClose?.cancel()
        autoClose = viewModelScope.launch {
            if (settings.current().speakReplies) {
                delay(SPEECH_START_GRACE_MS)
                voice.speaking.first { !it }
            }
            delay(LINGER_MS)
            _state.update { it.copy(finished = true) }
        }
    }

    override fun onCleared() {
        speech.stop()
    }

    private companion object {
        const val SPEECH_START_GRACE_MS = 600L
        const val LINGER_MS = 4_000L
    }
}

@Composable
private fun OverlaySheet(
    state: OverlayState,
    onMicStart: () -> Unit,
    onMicStop: () -> Unit,
    onDismiss: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val noRipple = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = noRipple, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .clickable(interactionSource = noRipple, indication = null) {}
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HolstromOrb(state.orb, state.level, size = 96.dp)
            Text(
                state.heard.ifEmpty {
                    stringResource(if (state.listening) R.string.talk_listening else R.string.overlay_tap_to_talk)
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = if (state.heard.isEmpty()) OxTheme.colors.subtle else MaterialTheme.colorScheme.onSurface,
            )
            if (state.reply.isNotEmpty() || state.thinking) {
                Text(
                    state.reply.ifEmpty { stringResource(R.string.talk_thinking) },
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenApp) { Text(stringResource(R.string.overlay_open_app)) }
                Spacer(Modifier.size(24.dp))
                MicButton(listening = state.listening, onStart = onMicStart, onStop = onMicStop, size = 64.dp)
                Spacer(Modifier.size(24.dp))
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        }
    }
}
