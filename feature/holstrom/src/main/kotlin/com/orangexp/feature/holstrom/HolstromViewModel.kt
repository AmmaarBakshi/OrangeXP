package com.orangexp.feature.holstrom

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orangexp.core.data.model.ChatMessage
import com.orangexp.core.data.model.HolstromSettings
import com.orangexp.core.data.model.Reminder
import com.orangexp.core.data.model.Speaker
import com.orangexp.core.data.repository.ConversationRepository
import com.orangexp.core.data.repository.HolstromSettingsRepository
import com.orangexp.core.data.repository.ReminderRepository
import com.orangexp.core.engine.OrangeEngine
import com.orangexp.core.engine.ffi.ParsedCommand
import com.orangexp.core.llm.LanguageModel
import com.orangexp.core.llm.ModelState
import com.orangexp.core.voice.SpeechEvent
import com.orangexp.core.voice.SpeechInput
import com.orangexp.core.voice.VoiceOutput
import com.orangexp.core.work.holstrom.DeviceActions
import com.orangexp.feature.holstrom.brain.FollowUp
import com.orangexp.feature.holstrom.brain.HolstromBrain
import com.orangexp.feature.holstrom.voice.speechFailureText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }

data class HolstromPermissions(
    val microphone: Boolean = false,
    val notifications: Boolean = true,
    val exactAlarms: Boolean = true,
    val policyAccess: Boolean = false,
)

/** What the voice/typing side is doing right now. */
data class LiveState(
    val listening: Boolean = false,
    val thinking: Boolean = false,
    val partial: String? = null,
    val level: Float = 0f,
    /** Text of the answer being generated, shown before it is stored. */
    val streaming: String? = null,
    val followUp: FollowUp? = null,
    val notice: String? = null,
)

data class HolstromUiState(
    val messages: List<ChatMessage> = emptyList(),
    val live: LiveState = LiveState(),
    val speaking: Boolean = false,
    val active: List<Reminder> = emptyList(),
    val finished: List<Reminder> = emptyList(),
    val settings: HolstromSettings = HolstromSettings(),
    val model: ModelState = ModelState.NotInstalled,
    val permissions: HolstromPermissions = HolstromPermissions(),
    val engineVersion: String = "",
) {
    val orb: OrbState
        get() = when {
            live.listening -> OrbState.LISTENING
            live.thinking || live.streaming != null -> OrbState.THINKING
            speaking -> OrbState.SPEAKING
            else -> OrbState.IDLE
        }
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class HolstromViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val brain: HolstromBrain,
    private val reminders: ReminderRepository,
    private val conversation: ConversationRepository,
    private val settingsRepository: HolstromSettingsRepository,
    private val model: LanguageModel,
    private val speech: SpeechInput,
    private val voice: VoiceOutput,
    private val deviceActions: DeviceActions,
    engine: OrangeEngine,
) : ViewModel() {

    private val live = MutableStateFlow(LiveState())
    private val permissions = MutableStateFlow(currentPermissions())
    private val engineVersion = engine.version

    val uiState: StateFlow<HolstromUiState> = combine(
        combine(conversation.messages, live, voice.speaking, ::Triple),
        combine(reminders.active, reminders.finished, ::Pair),
        combine(settingsRepository.settings, model.state, permissions, ::Triple),
    ) { (messages, liveState, speaking), (active, finished), (settings, modelState, perms) ->
        HolstromUiState(
            messages = messages,
            live = liveState,
            speaking = speaking,
            active = active,
            finished = finished,
            settings = settings,
            model = modelState,
            permissions = perms,
            engineVersion = engineVersion,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HolstromUiState())

    /** Live reading of the Plans composer. */
    val composer = MutableStateFlow("")
    val preview: StateFlow<ParsedCommand?> = composer
        .debounce(250)
        .distinctUntilChanged()
        .mapLatest { text -> if (text.isBlank()) null else runCatching { reminders.parse(text) }.getOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var turn: Job? = null
    private var listening: Job? = null

    fun send(text: String) {
        if (text.isBlank()) return
        turn?.cancel()
        brain.interrupt()
        turn = viewModelScope.launch {
            live.update { it.copy(thinking = true, followUp = null, notice = null, streaming = null) }
            try {
                brain.handle(text).collect { reply ->
                    live.update {
                        it.copy(
                            thinking = reply.thinking,
                            streaming = if (reply.done || reply.thinking) null else reply.text,
                            followUp = reply.followUp ?: it.followUp,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Holstrom failed", e)
                conversation.add(Speaker.HOLSTROM, e.message ?: context.getString(R.string.speech_other))
            } finally {
                live.update { it.copy(thinking = false, streaming = null) }
            }
        }
    }

    fun addPlan() {
        val text = composer.value
        composer.value = ""
        send(text)
    }

    /** Starts listening; the result is sent to Holstrom when speech ends. */
    fun startListening() {
        if (listening?.isActive == true) return
        brain.interrupt()
        refreshPermissions()
        listening = viewModelScope.launch {
            live.update { it.copy(listening = true, partial = null, level = 0f, notice = null, followUp = null) }
            try {
                speech.listen().collect { event ->
                    when (event) {
                        SpeechEvent.Listening -> Unit
                        is SpeechEvent.Level -> live.update { it.copy(level = event.value) }
                        is SpeechEvent.Partial -> live.update { it.copy(partial = event.text) }
                        is SpeechEvent.Final -> {
                            live.update { it.copy(listening = false, partial = null) }
                            send(event.text)
                        }
                        is SpeechEvent.Failed -> live.update {
                            it.copy(listening = false, partial = null, notice = speechFailureText(context, event.reason))
                        }
                    }
                }
            } finally {
                live.update { it.copy(listening = false, level = 0f) }
            }
        }
    }

    /** Release of hold-to-talk: finish with what was heard. */
    fun stopListening() = speech.stop()

    fun interrupt() {
        turn?.cancel()
        listening?.cancel()
        brain.interrupt()
        live.update { LiveState() }
    }

    fun stopSpeaking() = voice.stop()

    fun dismissNotice() = live.update { it.copy(notice = null, followUp = null) }

    fun clearConversation() = launch { conversation.clear() }

    fun complete(id: Long) = launch { reminders.complete(id) }

    fun snooze(id: Long) = launch { reminders.snooze(id, 60) }

    fun delete(id: Long) = launch { reminders.delete(id) }

    fun edit(id: Long, title: String, atMs: Long) = launch { reminders.edit(id, title, atMs) }

    fun clearFinished() = launch { reminders.clearFinished() }

    fun updateSettings(transform: (HolstromSettings) -> HolstromSettings) = launch { settingsRepository.update(transform) }

    fun importModel(uri: Uri) = launch { model.import(uri) }

    fun removeModel() = launch { model.remove() }

    fun refreshPermissions() {
        permissions.value = currentPermissions()
    }

    fun policyAccessIntent() = deviceActions.policyAccessSettingsIntent()

    private fun currentPermissions(): HolstromPermissions {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return HolstromPermissions(
            microphone = granted(Manifest.permission.RECORD_AUDIO),
            notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS),
            exactAlarms = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms(),
            policyAccess = deviceActions.hasPolicyAccess(),
        )
    }

    override fun onCleared() {
        voice.stop()
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "Action failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "HolstromViewModel"
    }
}
