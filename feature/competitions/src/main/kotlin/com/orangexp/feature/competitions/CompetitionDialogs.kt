package com.orangexp.feature.competitions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orangexp.core.common.time.epochDay
import com.orangexp.core.data.repository.CompetitionItem
import com.orangexp.core.data.repository.TeamMember
import com.orangexp.core.designsystem.theme.OxTheme
import com.orangexp.core.engine.ffi.CompetitionResult
import com.orangexp.core.engine.ffi.CompetitionStatus
import com.orangexp.core.ui.DateField
import com.orangexp.core.ui.DecimalField
import com.orangexp.core.ui.FormDialog
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
internal fun CompetitionDialog(
    initial: CompetitionItem?,
    members: List<TeamMember>,
    onSave: (CompetitionItem, List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = LocalDate.now().epochDay
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var prepStart by remember { mutableIntStateOf(initial?.prepStartDay ?: today) }
    var eventStart by remember { mutableIntStateOf(initial?.eventStartDay ?: (today + 14)) }
    var eventEnd by remember { mutableIntStateOf(initial?.eventEndDay ?: (today + 14)) }
    var prepHours by remember { mutableStateOf(initial?.prepHours?.let(::hours) ?: "10") }
    var importance by remember { mutableFloatStateOf((initial?.importance ?: 3).toFloat()) }
    var registered by remember { mutableStateOf(initial?.status == CompetitionStatus.REGISTERED) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }
    var team by remember { mutableStateOf(initial?.members.orEmpty().map { it.id }.toSet()) }

    val hoursValue = prepHours.toDoubleOrNull()
    val valid = name.isNotBlank() && hoursValue != null && eventEnd >= eventStart && prepStart <= eventStart

    FormDialog(
        title = stringResource(if (initial == null) R.string.comp_add_competition else R.string.comp_edit_competition),
        confirmEnabled = valid,
        onConfirm = {
            val keepStatus = initial?.status?.takeIf { it == CompetitionStatus.COMPLETED || it == CompetitionStatus.WITHDRAWN }
            onSave(
                CompetitionItem(
                    id = initial?.id ?: 0,
                    name = name,
                    prepStartDay = prepStart,
                    eventStartDay = eventStart,
                    eventEndDay = eventEnd,
                    prepHours = hoursValue ?: 0.0,
                    importance = importance.roundToInt(),
                    status = keepStatus ?: if (registered) CompetitionStatus.REGISTERED else CompetitionStatus.PLANNED,
                    result = initial?.result,
                    notes = notes,
                ),
                team.toList(),
            )
        },
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.comp_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        DateField(stringResource(R.string.comp_field_prep_start), prepStart, { prepStart = it })
        DateField(stringResource(R.string.comp_field_event_start), eventStart, {
            eventStart = it
            if (eventEnd < it) eventEnd = it
        })
        DateField(stringResource(R.string.comp_field_event_end), eventEnd, { eventEnd = it })
        DecimalField(prepHours, { prepHours = it }, stringResource(R.string.comp_field_prep_hours))
        Text(stringResource(R.string.comp_field_importance, importance.roundToInt()), style = MaterialTheme.typography.bodyMedium)
        Slider(importance, { importance = it }, valueRange = 1f..5f, steps = 3)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.comp_field_registered), Modifier.weight(1f))
            Switch(registered, { registered = it })
        }
        Text(stringResource(R.string.comp_field_team), style = MaterialTheme.typography.bodyMedium)
        if (members.isEmpty()) {
            Text(stringResource(R.string.comp_field_team_empty), style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            members.forEach { member ->
                FilterChip(
                    selected = member.id in team,
                    onClick = { team = if (member.id in team) team - member.id else team + member.id },
                    label = { Text(member.name) },
                )
            }
        }
        OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(R.string.comp_field_notes)) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun ResultDialog(item: CompetitionItem, onResult: (CompetitionResult?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.comp_record_result_title, item.name)) },
        text = {
            Column {
                CompetitionResult.entries.forEach { result ->
                    TextButton(onClick = { onResult(result) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(result.labelRes()), Modifier.fillMaxWidth())
                    }
                }
                TextButton(onClick = { onResult(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.comp_result_withdrawn), Modifier.fillMaxWidth(), color = OxTheme.colors.subtle)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
internal fun MemberDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    FormDialog(stringResource(R.string.comp_team_add), name.isNotBlank(), { onSave(name) }, onDismiss) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.comp_field_name)) }, singleLine = true)
    }
}
