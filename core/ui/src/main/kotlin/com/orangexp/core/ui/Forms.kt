package com.orangexp.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.orangexp.core.common.time.EpochDay

private const val MS_PER_DAY = 86_400_000L

/** A scrollable form in a dialog with Save/Cancel. */
@Composable
fun FormDialog(
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
        confirmButton = { TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(stringResource(R.string.form_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.form_cancel)) } },
    )
}

/** A button showing a date; tapping it opens a date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, day: EpochDay, onChange: (EpochDay) -> Unit, modifier: Modifier = Modifier) {
    var picking by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { picking = true }, modifier = modifier) { Text("$label: ${day.formatMedium()}") }
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = day * MS_PER_DAY)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange((it / MS_PER_DAY).toInt()) }
                    picking = false
                }) { Text(stringResource(R.string.form_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.form_cancel)) } },
        ) { DatePicker(state) }
    }
}

/** A text field accepting a non-negative decimal number. */
@Composable
fun DecimalField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isDigit() || it == '.' }.take(6)) },
        label = { Text(label) },
        singleLine = true,
        isError = value.isNotEmpty() && value.toDoubleOrNull() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}
