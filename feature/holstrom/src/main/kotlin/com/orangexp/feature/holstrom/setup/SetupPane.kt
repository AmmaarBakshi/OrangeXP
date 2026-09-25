package com.orangexp.feature.holstrom.setup

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.orangexp.core.designsystem.component.OxCard
import com.orangexp.core.designsystem.component.SectionLabel
import com.orangexp.core.designsystem.theme.OxTheme
import com.orangexp.core.llm.ModelState
import com.orangexp.feature.holstrom.HolstromUiState
import com.orangexp.feature.holstrom.HolstromViewModel
import com.orangexp.feature.holstrom.R
import com.orangexp.feature.holstrom.openIntent
import com.orangexp.feature.holstrom.plans.TimePickerDialog
import com.orangexp.feature.holstrom.plans.rememberHolstromFormat
import com.orangexp.feature.holstrom.widget.HolstromWidgetReceiver
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class TimeSetting { NUDGE, DEFAULT }

@Composable
fun SetupPane(
    state: HolstromUiState,
    viewModel: HolstromViewModel,
    onRequestMic: () -> Unit,
    onOpenPolicyAccess: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val format = rememberHolstromFormat()
    var picking by remember { mutableStateOf<TimeSetting?>(null) }
    val settings = state.settings
    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importModel)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshPermissions()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            OxCard {
                SectionLabel(stringResource(R.string.setup_brain))
                Text(stringResource(R.string.setup_brain_body), style = MaterialTheme.typography.bodyMedium)
                ModelStatus(state.model)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val installed = state.model !is ModelState.NotInstalled && state.model !is ModelState.Importing
                    Button(
                        onClick = { importModel.launch(arrayOf("*/*")) },
                        enabled = state.model !is ModelState.Importing,
                    ) { Text(stringResource(if (installed) R.string.setup_model_replace else R.string.setup_model_import)) }
                    if (installed) {
                        OutlinedButton(onClick = viewModel::removeModel) { Text(stringResource(R.string.setup_model_remove)) }
                    }
                }
                Text(stringResource(R.string.setup_model_help), style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle)
                SwitchRow(stringResource(R.string.setup_use_model), null, settings.useModel) { on -> viewModel.updateSettings { it.copy(useModel = on) } }
                SwitchRow(stringResource(R.string.setup_use_gpu), stringResource(R.string.setup_use_gpu_body), settings.useGpu) { on ->
                    viewModel.updateSettings { it.copy(useGpu = on) }
                }
            }
        }
        item {
            OxCard {
                SectionLabel(stringResource(R.string.setup_voice))
                SwitchRow(stringResource(R.string.setup_speak), null, settings.speakReplies) { on -> viewModel.updateSettings { it.copy(speakReplies = on) } }
                PermissionRow(
                    title = stringResource(R.string.setup_mic),
                    body = stringResource(if (state.permissions.microphone) R.string.setup_mic_granted else R.string.setup_mic_needed),
                    granted = state.permissions.microphone,
                    onAllow = onRequestMic,
                )
            }
        }
        item {
            OxCard {
                SectionLabel(stringResource(R.string.setup_reminders))
                ClickRow(
                    stringResource(R.string.setup_nudge_time),
                    stringResource(R.string.setup_nudge_time_body, format.clockOfMinute(settings.nudgeMinute)),
                ) { picking = TimeSetting.NUDGE }
                ClickRow(
                    stringResource(R.string.setup_default_time),
                    stringResource(R.string.setup_default_time_body, format.clockOfMinute(settings.defaultMinute)),
                ) { picking = TimeSetting.DEFAULT }
            }
        }
        item {
            OxCard {
                SectionLabel(stringResource(R.string.setup_permissions))
                PermissionRow(stringResource(R.string.setup_notifications), null, state.permissions.notifications) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                PermissionRow(
                    stringResource(R.string.setup_exact_alarms),
                    stringResource(R.string.setup_exact_alarms_body),
                    state.permissions.exactAlarms,
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        openIntent(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    }
                }
                PermissionRow(
                    stringResource(R.string.setup_dnd_access),
                    stringResource(R.string.setup_dnd_access_body),
                    state.permissions.policyAccess,
                    onOpenPolicyAccess,
                )
            }
        }
        item {
            OxCard {
                SectionLabel(stringResource(R.string.setup_everywhere))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.setup_widget), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.setup_widget_body), style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle)
                    }
                    FilledTonalButton(onClick = {
                        scope.launch {
                            runCatching {
                                GlanceAppWidgetManager(context).requestPinGlanceAppWidget(HolstromWidgetReceiver::class.java)
                            }
                        }
                    }) { Text(stringResource(R.string.setup_add)) }
                }
                Text(stringResource(R.string.setup_tile_body), style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle)
            }
        }
        item {
            Text(
                stringResource(R.string.setup_about, state.engineVersion),
                style = MaterialTheme.typography.bodySmall,
                color = OxTheme.colors.subtle,
            )
        }
    }

    picking?.let { which ->
        TimePickerDialog(
            minuteOfDay = if (which == TimeSetting.NUDGE) settings.nudgeMinute else settings.defaultMinute,
            onPick = { minute ->
                viewModel.updateSettings { if (which == TimeSetting.NUDGE) it.copy(nudgeMinute = minute) else it.copy(defaultMinute = minute) }
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun ModelStatus(model: ModelState) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    when (model) {
        ModelState.NotInstalled -> Text(stringResource(R.string.setup_model_none), style = MaterialTheme.typography.titleSmall)
        is ModelState.Importing -> {
            val fraction = model.fraction
            Text(
                if (fraction != null) {
                    stringResource(R.string.setup_model_importing, (fraction * 100).roundToInt())
                } else {
                    stringResource(R.string.setup_model_importing_unknown)
                },
                style = MaterialTheme.typography.titleSmall,
            )
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is ModelState.Installed -> ModelLine(stringResource(R.string.setup_model_installed, model.model.name, size(model.model.sizeBytes)), stringResource(R.string.setup_model_idle))
        is ModelState.Loading -> {
            ModelLine(stringResource(R.string.setup_model_installed, model.model.name, size(model.model.sizeBytes)), stringResource(R.string.setup_model_loading))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is ModelState.Ready -> ModelLine(
            stringResource(R.string.setup_model_installed, model.model.name, size(model.model.sizeBytes)),
            stringResource(if (model.gpu) R.string.setup_model_ready_gpu else R.string.setup_model_ready_cpu),
        )
        is ModelState.Failed -> {
            model.model?.let { Text(stringResource(R.string.setup_model_installed, it.name, size(it.sizeBytes)), style = MaterialTheme.typography.titleSmall) }
            Text(stringResource(R.string.setup_model_failed, model.message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ModelLine(title: String, status: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SwitchRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ClickRow(title: String, body: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle)
        }
        TextButton(onClick = onClick) { Text(stringResource(R.string.plans_edit)) }
    }
}

@Composable
private fun PermissionRow(title: String, body: String?, granted: Boolean, onAllow: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle) }
        }
        if (granted) {
            Text(stringResource(R.string.setup_allowed), style = MaterialTheme.typography.labelLarge, color = OxTheme.colors.stateGood)
        } else {
            FilledTonalButton(onClick = onAllow) { Text(stringResource(R.string.setup_allow)) }
        }
    }
}
