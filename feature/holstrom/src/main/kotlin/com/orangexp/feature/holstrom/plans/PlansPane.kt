package com.orangexp.feature.holstrom.plans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orangexp.core.data.model.Reminder
import com.orangexp.core.designsystem.component.EmptyState
import com.orangexp.core.designsystem.component.OxCard
import com.orangexp.core.designsystem.component.SectionLabel
import com.orangexp.core.designsystem.theme.OxTheme
import com.orangexp.core.engine.ffi.CommandKind
import com.orangexp.core.engine.ffi.ParsedCommand
import com.orangexp.core.engine.ffi.RepeatRule
import com.orangexp.feature.holstrom.HolstromUiState
import com.orangexp.feature.holstrom.HolstromViewModel
import com.orangexp.feature.holstrom.MicButton
import com.orangexp.feature.holstrom.R
import com.orangexp.feature.holstrom.brain.HolstromFormat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface FormatEntryPoint {
    fun format(): HolstromFormat
}

@Composable
internal fun rememberHolstromFormat(): HolstromFormat {
    val context = LocalContext.current.applicationContext
    return remember { EntryPointAccessors.fromApplication(context, FormatEntryPoint::class.java).format() }
}

/** The "write what you need to do" section and everything Holstrom is looking after. */
@Composable
fun PlansPane(state: HolstromUiState, viewModel: HolstromViewModel, onMicStart: () -> Unit) {
    val composer by viewModel.composer.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val format = rememberHolstromFormat()
    var editing by remember { mutableStateOf<Reminder?>(null) }

    val due = state.active.filter { it.isDue }
    val upcoming = state.active.filter { !it.isDue && it.kind != CommandKind.DEVICE_ACTION }
    val actions = state.active.filter { it.kind == CommandKind.DEVICE_ACTION }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OxCard {
                SectionLabel(stringResource(R.string.plans_composer_label))
                OutlinedTextField(
                    value = composer,
                    onValueChange = { viewModel.composer.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.plans_composer_placeholder)) },
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (composer.isNotBlank()) viewModel.addPlan() }),
                )
                preview?.takeIf { composer.isNotBlank() }?.let {
                    Text(describePreview(it, format), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MicButton(listening = state.live.listening, onStart = onMicStart, onStop = viewModel::stopListening, size = 44.dp)
                    state.live.partial?.let {
                        Text(it, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, color = OxTheme.colors.subtle)
                    } ?: androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    Button(onClick = viewModel::addPlan, enabled = composer.isNotBlank()) { Text(stringResource(R.string.plans_add)) }
                }
            }
        }

        if (state.active.isEmpty()) {
            item { EmptyState(stringResource(R.string.plans_empty_title), stringResource(R.string.plans_empty_body)) }
        }
        section(R.string.plans_due, due) { ReminderCard(it, format, viewModel, onEdit = { editing = it }) }
        section(R.string.plans_upcoming, upcoming) { ReminderCard(it, format, viewModel, onEdit = { editing = it }) }
        section(R.string.plans_actions, actions) { ActionCard(it, format, viewModel) }
        if (state.finished.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(stringResource(R.string.plans_finished), Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearFinished) { Text(stringResource(R.string.plans_clear_finished)) }
                }
            }
            items(state.finished.take(15), key = { "f${it.id}" }) {
                Text(
                    it.title.ifBlank { it.sourceText },
                    style = MaterialTheme.typography.bodyMedium,
                    color = OxTheme.colors.subtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    editing?.let { reminder ->
        EditReminderDialog(
            reminder = reminder,
            onSave = { title, atMs ->
                viewModel.edit(reminder.id, title, atMs)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

private fun LazyListScope.section(title: Int, reminders: List<Reminder>, card: @Composable (Reminder) -> Unit) {
    if (reminders.isEmpty()) return
    item(key = "h$title") { SectionLabel(stringResource(title)) }
    items(reminders, key = { it.id }) { card(it) }
}

@Composable
private fun ReminderCard(r: Reminder, format: HolstromFormat, viewModel: HolstromViewModel, onEdit: (Reminder) -> Unit) {
    OxCard {
        Text(r.title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle(r, format), style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewModel.complete(r.id) }) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Text(stringResource(R.string.plans_done), Modifier.padding(start = 6.dp))
            }
            if (r.isDue) {
                IconButton(onClick = { viewModel.snooze(r.id) }) { Icon(Icons.Filled.Snooze, stringResource(R.string.plans_snooze)) }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            IconButton(onClick = { onEdit(r) }) { Icon(Icons.Filled.Edit, stringResource(R.string.plans_edit)) }
            IconButton(onClick = { viewModel.delete(r.id) }) {
                Icon(Icons.Filled.Delete, stringResource(R.string.plans_delete), tint = OxTheme.colors.subtle)
            }
        }
    }
}

@Composable
private fun ActionCard(r: Reminder, format: HolstromFormat, viewModel: HolstromViewModel) {
    OxCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(r.sourceText, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(format.moment(r.nextFireMs ?: r.atMs), style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle)
            }
            IconButton(onClick = { viewModel.delete(r.id) }) {
                Icon(Icons.Filled.Delete, stringResource(R.string.plans_delete), tint = OxTheme.colors.subtle)
            }
        }
    }
}

@Composable
private fun subtitle(r: Reminder, format: HolstromFormat): String {
    val next = r.nextFireMs
    return when {
        r.kind == CommandKind.DEADLINE -> listOfNotNull(
            stringResource(R.string.plans_due_on, format.day(r.atMs)),
            next?.let { stringResource(R.string.plans_next, format.moment(it)) },
        ).joinToString(" · ")
        r.isDue -> stringResource(R.string.plans_went_off, format.moment(r.lastFiredMs ?: r.atMs))
        r.repeat == RepeatRule.DAILY -> "${stringResource(R.string.plans_repeats_daily)} · ${format.clock(next ?: r.atMs)}"
        r.repeat == RepeatRule.WEEKLY -> "${stringResource(R.string.plans_repeats_weekly)} · ${format.weekday(next ?: r.atMs)} ${format.clock(next ?: r.atMs)}"
        else -> format.moment(next ?: r.atMs)
    }
}

@Composable
private fun describePreview(c: ParsedCommand, format: HolstromFormat): String {
    val at = c.atMs
    return when {
        c.kind == CommandKind.QUESTION || at == null -> stringResource(R.string.plans_preview_question)
        c.kind == CommandKind.DEADLINE -> stringResource(R.string.plans_preview_deadline, "${c.title} · ${format.day(at)}")
        c.kind == CommandKind.DEVICE_ACTION -> stringResource(R.string.plans_preview_action, format.moment(at))
        c.repeat == RepeatRule.DAILY -> stringResource(R.string.plans_preview_daily, "${c.title} · ${format.clock(at)}")
        c.repeat == RepeatRule.WEEKLY -> stringResource(R.string.plans_preview_weekly, "${c.title} · ${format.weekday(at)} ${format.clock(at)}")
        else -> stringResource(R.string.plans_preview_reminder, "${c.title} · ${format.moment(at)}")
    }
}
