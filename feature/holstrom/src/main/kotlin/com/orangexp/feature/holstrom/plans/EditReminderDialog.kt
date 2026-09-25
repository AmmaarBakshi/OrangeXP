package com.orangexp.feature.holstrom.plans

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.orangexp.core.common.time.epochDayOf
import com.orangexp.core.common.time.localMinuteToEpochMs
import com.orangexp.core.data.model.Reminder
import com.orangexp.core.designsystem.format.OxFormat
import com.orangexp.core.ui.DateField
import com.orangexp.core.ui.FormDialog
import com.orangexp.feature.holstrom.R
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

@Composable
internal fun EditReminderDialog(reminder: Reminder, onSave: (title: String, atMs: Long) -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val initial = reminder.atMs
    var title by remember { mutableStateOf(reminder.title) }
    var day by remember { mutableIntStateOf(epochDayOf(initial, zone)) }
    var minute by remember {
        mutableIntStateOf(LocalTime.ofInstant(Instant.ofEpochMilli(initial), zone).let { it.hour * 60 + it.minute })
    }
    var pickingTime by remember { mutableStateOf(false) }

    FormDialog(
        title = stringResource(R.string.plans_edit_title),
        confirmEnabled = title.isNotBlank(),
        onConfirm = { onSave(title, localMinuteToEpochMs(day, minute, zone)) },
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(stringResource(R.string.plans_edit_text)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            DateField(label = "", day = day, onChange = { day = it })
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { pickingTime = true }) { Text(OxFormat.clock(minute)) }
        }
    }
    if (pickingTime) {
        TimePickerDialog(minute, onPick = { minute = it; pickingTime = false }, onDismiss = { pickingTime = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickerDialog(minuteOfDay: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = minuteOfDay / 60,
        initialMinute = minuteOfDay % 60,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
