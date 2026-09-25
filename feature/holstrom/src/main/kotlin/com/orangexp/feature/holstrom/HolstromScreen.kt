package com.orangexp.feature.holstrom

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orangexp.core.data.model.ChatMessage
import com.orangexp.core.data.model.Speaker
import com.orangexp.core.designsystem.component.OxCard
import com.orangexp.core.designsystem.theme.OxTheme
import com.orangexp.feature.holstrom.brain.FollowUp
import com.orangexp.feature.holstrom.plans.PlansPane
import com.orangexp.feature.holstrom.setup.SetupPane

private enum class HolstromTab(val label: Int) { Talk(R.string.tab_talk), Plans(R.string.tab_plans), Setup(R.string.tab_setup) }

@Composable
fun HolstromRoute(viewModel: HolstromViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(HolstromTab.Talk) }
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPermissions() }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.refreshPermissions()
        if (granted) viewModel.startListening()
    }
    val startListening = {
        if (state.permissions.microphone) viewModel.startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val openPolicyAccess = { context.startActivity(viewModel.policyAccessIntent()) }

    // Reminders are useless without notifications: ask once, when the first one exists.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshPermissions()
    }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.active.isNotEmpty(), state.permissions.notifications) {
        if (state.active.isNotEmpty() && !state.permissions.notifications && !askedNotifications &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            HolstromTab.entries.forEach { t ->
                Tab(selected = tab == t, onClick = { tab = t }, text = { Text(stringResource(t.label)) })
            }
        }
        when (tab) {
            HolstromTab.Talk -> TalkPane(
                state = state,
                onSend = viewModel::send,
                onMicStart = startListening,
                onMicStop = viewModel::stopListening,
                onStopSpeaking = viewModel::stopSpeaking,
                onClear = viewModel::clearConversation,
                onFollowUp = { followUp ->
                    viewModel.dismissNotice()
                    when (followUp) {
                        FollowUp.POLICY_ACCESS -> openPolicyAccess()
                        FollowUp.INSTALL_MODEL -> tab = HolstromTab.Setup
                    }
                },
                onDismissNotice = viewModel::dismissNotice,
            )
            HolstromTab.Plans -> PlansPane(
                state = state,
                viewModel = viewModel,
                onMicStart = startListening,
            )
            HolstromTab.Setup -> SetupPane(
                state = state,
                viewModel = viewModel,
                onRequestMic = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                onOpenPolicyAccess = openPolicyAccess,
            )
        }
    }
}

@Composable
private fun TalkPane(
    state: HolstromUiState,
    onSend: (String) -> Unit,
    onMicStart: () -> Unit,
    onMicStop: () -> Unit,
    onStopSpeaking: () -> Unit,
    onClear: () -> Unit,
    onFollowUp: (FollowUp) -> Unit,
    onDismissNotice: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val messages = state.messages.withStreaming(state.live.streaming)
    val itemCount = messages.size + if (state.live.partial != null) 1 else 0

    LaunchedEffect(itemCount, state.live.streaming?.length) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HolstromOrb(state.orb, state.live.level, size = 64.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.holstrom), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(
                        when (state.orb) {
                            OrbState.LISTENING -> R.string.talk_listening
                            OrbState.THINKING -> R.string.talk_thinking
                            OrbState.SPEAKING -> R.string.talk_speaking
                            OrbState.IDLE -> R.string.talk_idle
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = OxTheme.colors.subtle,
                )
            }
            if (state.speaking) {
                IconButton(onClick = onStopSpeaking) { Icon(Icons.Filled.VolumeOff, stringResource(R.string.talk_stop_speaking)) }
            }
            if (state.messages.isNotEmpty()) {
                IconButton(onClick = onClear) { Icon(Icons.Filled.DeleteSweep, stringResource(R.string.talk_clear), tint = OxTheme.colors.subtle) }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty() && state.live.partial == null) {
                item { EmptyTalk(onSend) }
            }
            items(messages, key = { it.id }) { Bubble(it.speaker, it.text) }
            state.live.partial?.let { partial -> item(key = "partial") { Bubble(Speaker.USER, partial, faded = true) } }
        }

        val notice = state.live.notice
        val followUp = state.live.followUp
        if (notice != null || followUp != null) {
            OxCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Row {
                    followUp?.let { f ->
                        FilledTonalButton(onClick = { onFollowUp(f) }) {
                            Text(stringResource(if (f == FollowUp.POLICY_ACCESS) R.string.talk_grant_access else R.string.talk_install_model))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismissNotice) { Text(stringResource(android.R.string.ok)) }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.talk_hint)) },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    onSend(input)
                    input = ""
                }),
            )
            Spacer(Modifier.size(8.dp))
            if (input.isNotBlank()) {
                IconButton(onClick = {
                    onSend(input)
                    input = ""
                }) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.talk_send), tint = MaterialTheme.colorScheme.primary) }
            } else {
                MicButton(listening = state.live.listening, onStart = onMicStart, onStop = onMicStop)
            }
        }
    }
}

/** Shows an answer while it is being generated, before the stored copy catches up. */
private fun List<ChatMessage>.withStreaming(streaming: String?): List<ChatMessage> {
    if (streaming == null) return this
    val last = lastOrNull() ?: return this
    return if (last.speaker == Speaker.HOLSTROM) dropLast(1) + last.copy(text = streaming) else this
}

@Composable
private fun Bubble(speaker: Speaker, text: String, faded: Boolean = false) {
    val mine = speaker == Speaker.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Text(
            text = text.ifEmpty { "…" },
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (mine) 18.dp else 4.dp,
                        bottomEnd = if (mine) 4.dp else 18.dp,
                    ),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (mine) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }.let { if (faded) it.copy(alpha = 0.6f) else it },
        )
    }
}

@Composable
private fun EmptyTalk(onSend: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.talk_empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(6.dp))
        Text(stringResource(R.string.talk_empty_body), style = MaterialTheme.typography.bodyMedium, color = OxTheme.colors.subtle)
        Spacer(Modifier.size(12.dp))
        SUGGESTIONS.forEach { suggestion ->
            AssistChip(onClick = { onSend(suggestion) }, label = { Text(suggestion) })
        }
    }
}

private val SUGGESTIONS = listOf("How am I doing?", "What's next today?", "How did I sleep?", "What are my reminders?")

internal fun openIntent(context: android.content.Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
