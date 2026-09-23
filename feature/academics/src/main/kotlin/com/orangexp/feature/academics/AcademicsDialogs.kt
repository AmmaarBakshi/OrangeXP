package com.orangexp.feature.academics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.orangexp.core.common.time.EpochDay
import com.orangexp.core.data.model.TimetableEntry
import com.orangexp.core.designsystem.format.OxFormat
import com.orangexp.core.ui.formatMedium
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private const val MS_PER_DAY = 86_400_000L

@Composable
internal fun FormDialog(
    title: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(stringResource(R.string.dialog_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
internal fun TextInputDialog(title: String, label: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    FormDialog(title, confirmEnabled = text.isNotBlank(), onConfirm = { onConfirm(text) }, onDismiss = onDismiss) {
        OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubjectDialog(
    initialName: String,
    initialExamDay: EpochDay?,
    initialPriority: Int,
    title: String,
    onConfirm: (String, EpochDay?, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var examDay by remember { mutableStateOf(initialExamDay) }
    var priority by remember { mutableFloatStateOf(initialPriority.toFloat()) }
    var pickingDate by remember { mutableStateOf(false) }

    FormDialog(title, name.isNotBlank(), { onConfirm(name, examDay, priority.roundToInt()) }, onDismiss) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickingDate = true }) {
                Text(examDay?.formatMedium() ?: stringResource(R.string.field_set_exam_date))
            }
            if (examDay != null) TextButton(onClick = { examDay = null }) { Text(stringResource(R.string.field_clear)) }
        }
        Text(stringResource(R.string.field_priority, priority.roundToInt()), style = MaterialTheme.typography.bodyMedium)
        Slider(priority, { priority = it }, valueRange = 1f..5f, steps = 3)
    }

    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = examDay?.let { it * MS_PER_DAY })
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { examDay = (it / MS_PER_DAY).toInt() }
                    pickingDate = false
                }) { Text(stringResource(R.string.dialog_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.dialog_cancel)) } },
        ) { DatePicker(state) }
    }
}

@Composable
internal fun TopicDialog(title: String, onConfirm: (String, Int, Int) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("60") }
    var difficulty by remember { mutableFloatStateOf(3f) }
    val parsedMinutes = minutes.toIntOrNull()?.takeIf { it > 0 }
    FormDialog(title, name.isNotBlank() && parsedMinutes != null, {
        onConfirm(name, parsedMinutes ?: 60, difficulty.roundToInt())
    }, onDismiss) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_title)) }, singleLine = true)
        NumberField(minutes, { minutes = it }, stringResource(R.string.field_minutes))
        Text(stringResource(R.string.field_difficulty, difficulty.roundToInt()), style = MaterialTheme.typography.bodyMedium)
        Slider(difficulty, { difficulty = it }, valueRange = 1f..5f, steps = 3)
    }
}

@Composable
internal fun ClassDialog(title: String, onConfirm: (TimetableEntry) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var weekday by remember { mutableStateOf(DayOfWeek.MONDAY) }
    var start by remember { mutableIntStateOf(9 * 60) }
    var end by remember { mutableIntStateOf(10 * 60) }
    var location by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var travel by remember { mutableStateOf(true) }
    var travelMinutes by remember { mutableStateOf("") }
    var prepMinutes by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && end > start

    FormDialog(title, valid, {
        onConfirm(
            TimetableEntry(
                title = name,
                weekday = weekday,
                startMinute = start,
                endMinute = end,
                location = location,
                teacher = teacher,
                requiresTravel = travel,
                travelMinutes = travelMinutes.toIntOrNull()?.takeIf { it >= 0 },
                preparationMinutes = prepMinutes.toIntOrNull()?.takeIf { it >= 0 },
            ),
        )
    }, onDismiss) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_title)) }, singleLine = true)
        WeekdayPicker(weekday) { weekday = it }
        TimeRangeFields(start, end, { start = it }, { end = it })
        OutlinedTextField(location, { location = it }, label = { Text(stringResource(R.string.field_location)) }, singleLine = true)
        OutlinedTextField(teacher, { teacher = it }, label = { Text(stringResource(R.string.field_teacher)) }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.field_requires_travel), Modifier.weight(1f))
            Switch(travel, { travel = it })
        }
        if (travel) {
            NumberField(travelMinutes, { travelMinutes = it }, stringResource(R.string.field_travel_minutes))
            NumberField(prepMinutes, { prepMinutes = it }, stringResource(R.string.field_prep_minutes))
        }
    }
}

@Composable
internal fun StudyWindowDialog(title: String, onConfirm: (DayOfWeek, Int, Int) -> Unit, onDismiss: () -> Unit) {
    var weekday by remember { mutableStateOf(DayOfWeek.MONDAY) }
    var start by remember { mutableIntStateOf(18 * 60) }
    var end by remember { mutableIntStateOf(21 * 60) }
    FormDialog(title, end > start, { onConfirm(weekday, start, end) }, onDismiss) {
        WeekdayPicker(weekday) { weekday = it }
        TimeRangeFields(start, end, { start = it }, { end = it })
    }
}

@Composable
private fun WeekdayPicker(selected: DayOfWeek, onSelect: (DayOfWeek) -> Unit) {
    val locale = Locale.getDefault()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { day ->
            FilterChip(
                selected = day == selected,
                onClick = { onSelect(day) },
                label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
            )
        }
    }
}

@Composable
private fun TimeRangeFields(start: Int, end: Int, onStart: (Int) -> Unit, onEnd: (Int) -> Unit) {
    var editing by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { editing = true }) { Text(stringResource(R.string.field_start, OxFormat.clock(start))) }
        OutlinedButton(onClick = { editing = false }) { Text(stringResource(R.string.field_end, OxFormat.clock(end))) }
    }
    if (end <= start) {
        Text(stringResource(R.string.field_invalid_time), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    editing?.let { isStart ->
        TimePickerDialog(
            initialMinute = if (isStart) start else end,
            onConfirm = { if (isStart) onStart(it) else onEnd(it); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(initialMinute: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initialMinute / 60, initialMinute = initialMinute % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.dialog_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
private fun NumberField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter(Char::isDigit).take(4)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
