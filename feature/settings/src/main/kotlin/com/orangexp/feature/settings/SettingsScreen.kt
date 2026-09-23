package com.orangexp.feature.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orangexp.core.designsystem.component.OxCard
import com.orangexp.core.designsystem.component.SectionLabel
import com.orangexp.core.designsystem.format.OxFormat
import com.orangexp.core.designsystem.theme.OxTheme
import com.orangexp.core.engine.MetricKeys
import com.orangexp.core.engine.ffi.DayState
import com.orangexp.core.engine.ffi.EngineConfig
import com.orangexp.core.engine.ffi.HeatmapScale
import com.orangexp.core.engine.ffi.ScoreCurve
import com.orangexp.core.engine.ffi.ScoringRule
import com.orangexp.core.engine.ffi.StateRule
import com.orangexp.core.engine.ffi.ThresholdDirection
import com.orangexp.core.sensing.TrackingPermissions
import com.orangexp.core.ui.formatMetric
import com.orangexp.core.ui.label
import kotlin.math.roundToLong

/** A numeric field in an edit dialog. */
private data class NumberInput(val label: String, val initial: String, val optional: Boolean = false)

private data class EditRequest(
    val title: String,
    val inputs: List<NumberInput>,
    val hint: String? = null,
    val onConfirm: (List<Double?>) -> Unit,
)

@Composable
fun SettingsRoute(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissions()
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel) {
        viewModel.exportedJson.collect { json ->
            val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, json)
            context.startActivity(Intent.createChooser(send, null))
        }
    }
    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    SettingsScreen(
        state = state,
        appVersion = appVersion,
        viewModel = viewModel,
        openUsageAccess = { context.startActivity(viewModel.usageAccessIntent()) },
        openAppSettings = { context.startActivity(viewModel.appSettingsIntent()) },
    )
}

@Composable
private fun SettingsScreen(
    state: SettingsUiState,
    appVersion: String,
    viewModel: SettingsViewModel,
    openUsageAccess: () -> Unit,
    openAppSettings: () -> Unit,
) {
    var edit by remember { mutableStateOf<EditRequest?>(null) }
    var importing by remember { mutableStateOf(false) }
    val config = state.config

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.permissions?.let { perms -> item { PermissionsCard(perms, openUsageAccess, openAppSettings) } }
        if (state.issues.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.config_issues, state.issues.joinToString { "${it.path}: ${it.message}" }),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (config != null) {
            item { ScoringCard(config.scoringRules, viewModel) { edit = it } }
            item { StatesCard(config.stateRules, viewModel) { edit = it } }
            item { SleepCard(config, viewModel) { edit = it } }
            item { TravelCard(config, viewModel) { edit = it } }
            item { StudyCard(config, viewModel) { edit = it } }
            item { StreakCard(config, viewModel) { edit = it } }
        }
        item {
            OxCard {
                SectionLabel(stringResource(R.string.section_config))
                OutlinedButton(onClick = viewModel::exportConfig, Modifier.fillMaxWidth()) { Text(stringResource(R.string.config_export)) }
                OutlinedButton(onClick = { importing = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.config_import)) }
                TextButton(onClick = viewModel::resetConfig, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.config_reset), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            OxCard {
                SectionLabel(stringResource(R.string.section_about))
                Text(stringResource(R.string.about_version, appVersion, state.engineVersion), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle)
            }
        }
    }

    edit?.let { request -> NumberDialog(request) { edit = null } }
    if (importing) ImportDialog(onImport = { viewModel.importConfig(it); importing = false }, onDismiss = { importing = false })
}

@Composable
private fun PermissionsCard(perms: TrackingPermissions, openUsageAccess: () -> Unit, openAppSettings: () -> Unit) {
    OxCard {
        SectionLabel(stringResource(R.string.section_tracking))
        PermissionRow(stringResource(R.string.perm_usage), stringResource(R.string.perm_usage_detail), perms.usageAccess, openUsageAccess)
        PermissionRow(
            stringResource(R.string.perm_activity),
            if (perms.stepSensorAvailable) stringResource(R.string.perm_activity_detail) else stringResource(R.string.perm_no_sensor),
            perms.activityRecognition || !perms.stepSensorAvailable,
            openAppSettings,
        )
        PermissionRow(stringResource(R.string.perm_notifications), stringResource(R.string.perm_notifications_detail), perms.notifications, openAppSettings)
    }
}

@Composable
private fun PermissionRow(title: String, detail: String, granted: Boolean, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle)
        }
        if (granted) {
            Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.perm_granted), tint = OxTheme.colors.stateGood)
        } else {
            TextButton(onClick = onOpen) { Text(stringResource(R.string.perm_open)) }
        }
    }
}

@Composable
private fun unitLabel(metric: String): String = when (metric) {
    MetricKeys.STEPS -> stringResource(R.string.unit_step)
    MetricKeys.WALKING_METERS -> stringResource(R.string.unit_meter)
    MetricKeys.ATTENDANCE_PERCENT, MetricKeys.SCHEDULE_ADHERENCE_PERCENT -> stringResource(R.string.unit_percent)
    MetricKeys.SLEEP_MINUTES, MetricKeys.AWAKE_MINUTES, MetricKeys.STUDY_MINUTES, MetricKeys.ACTIVE_MINUTES,
    MetricKeys.SCREEN_MINUTES, MetricKeys.SYLLABUS_COMPLETED_MINUTES,
    -> stringResource(R.string.unit_minutes)
    else -> stringResource(R.string.unit_each)
}

@Composable
private fun curveSummary(rule: ScoringRule): String {
    val base = when (val curve = rule.curve) {
        is ScoreCurve.Linear -> if (curve.freeUnits > 0) {
            stringResource(R.string.curve_linear_free, number(curve.pointsPerUnit), unitLabel(rule.metric), formatMetric(rule.metric, curve.freeUnits))
        } else {
            stringResource(R.string.curve_linear, number(curve.pointsPerUnit), unitLabel(rule.metric))
        }
        is ScoreCurve.Steps -> stringResource(R.string.curve_steps, curve.steps.size)
        is ScoreCurve.Piecewise -> stringResource(R.string.curve_piecewise, curve.points.size)
    }
    val limits = listOfNotNull(
        rule.maxPoints?.let { stringResource(R.string.rule_cap, OxFormat.points(it.roundToLong())) },
        rule.minPoints?.let { stringResource(R.string.rule_floor, OxFormat.points(it.roundToLong())) },
    )
    return (listOf(base) + limits).joinToString(" · ")
}

@Composable
private fun ScoringCard(rules: List<ScoringRule>, vm: SettingsViewModel, onEdit: (EditRequest) -> Unit) {
    val rateLabel = stringResource(R.string.rule_edit_rate)
    val capLabel = stringResource(R.string.rule_edit_cap)
    val curveHint = stringResource(R.string.rule_edit_curve_hint)
    OxCard {
        SectionLabel(stringResource(R.string.section_scoring))
        Text(stringResource(R.string.section_scoring_hint), style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle)
        rules.forEach { rule ->
            val curve = rule.curve
            ToggleRow(
                title = rule.label,
                detail = curveSummary(rule),
                checked = rule.enabled,
                onCheckedChange = { vm.setScoringRuleEnabled(rule.id, it) },
                onClick = {
                    onEdit(
                        if (curve is ScoreCurve.Linear) {
                            EditRequest(
                                title = rule.label,
                                inputs = listOf(
                                    NumberInput(rateLabel, number(curve.pointsPerUnit)),
                                    NumberInput(capLabel, rule.maxPoints?.let(::number).orEmpty(), optional = true),
                                ),
                            ) { values -> vm.updateLinearRule(rule.id, values[0] ?: curve.pointsPerUnit, values[1]) }
                        } else {
                            EditRequest(title = rule.label, inputs = emptyList(), hint = curveHint) {}
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun StatesCard(rules: List<StateRule>, vm: SettingsViewModel, onEdit: (EditRequest) -> Unit) {
    val labels = listOf(stringResource(R.string.field_orange), stringResource(R.string.field_red), stringResource(R.string.field_black))
    OxCard {
        SectionLabel(stringResource(R.string.section_states))
        Text(stringResource(R.string.section_states_hint), style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle)
        rules.forEach { rule ->
            val direction = stringResource(
                if (rule.direction == ThresholdDirection.HIGHER_IS_WORSE) R.string.state_higher_worse else R.string.state_lower_worse,
            )
            ToggleRow(
                title = rule.label,
                detail = stringResource(
                    R.string.state_thresholds,
                    formatMetric(rule.metric, rule.orangeAt),
                    formatMetric(rule.metric, rule.redAt),
                    formatMetric(rule.metric, rule.blackAt),
                ) + " · " + direction,
                checked = rule.enabled,
                onCheckedChange = { vm.setStateRuleEnabled(rule.id, it) },
                onClick = {
                    onEdit(
                        EditRequest(
                            title = rule.label,
                            inputs = listOf(rule.orangeAt, rule.redAt, rule.blackAt).mapIndexed { i, v -> NumberInput(labels[i], number(v)) },
                        ) { v -> vm.updateStateRule(rule.id, v[0] ?: rule.orangeAt, v[1] ?: rule.redAt, v[2] ?: rule.blackAt) },
                    )
                },
            )
        }
    }
}

@Composable
private fun SleepCard(config: EngineConfig, vm: SettingsViewModel, onEdit: (EditRequest) -> Unit) {
    val sleep = config.sleep
    val title = stringResource(R.string.section_sleep)
    val labels = listOf(
        stringResource(R.string.sleep_threshold), stringResource(R.string.sleep_awake_reset),
        stringResource(R.string.field_window_start), stringResource(R.string.field_window_end),
    )
    OxCard(onClick = {
        onEdit(
            EditRequest(
                title,
                listOf(sleep.inactivityThresholdMinutes, sleep.awakeResetMinutes, sleep.typicalWindowStartMinute, sleep.typicalWindowEndMinute)
                    .mapIndexed { i, v -> NumberInput(labels[i], v.toString()) },
            ) { v ->
                vm.updateSleep(
                    v[0]?.toInt() ?: sleep.inactivityThresholdMinutes.toInt(),
                    v[1]?.toInt() ?: sleep.awakeResetMinutes.toInt(),
                    v[2]?.toInt() ?: sleep.typicalWindowStartMinute.toInt(),
                    v[3]?.toInt() ?: sleep.typicalWindowEndMinute.toInt(),
                )
            },
        )
    }) {
        SectionLabel(title)
        ValueRow(labels[0], OxFormat.duration(sleep.inactivityThresholdMinutes.toLong()))
        ValueRow(labels[1], OxFormat.duration(sleep.awakeResetMinutes.toLong()))
        ValueRow(
            stringResource(R.string.sleep_window),
            "${OxFormat.clock(sleep.typicalWindowStartMinute.toInt())}–${OxFormat.clock(sleep.typicalWindowEndMinute.toInt())}",
        )
    }
}

@Composable
private fun TravelCard(config: EngineConfig, vm: SettingsViewModel, onEdit: (EditRequest) -> Unit) {
    val t = config.travel
    val title = stringResource(R.string.section_travel)
    val labels = listOf(
        stringResource(R.string.travel_default), stringResource(R.string.travel_prep),
        stringResource(R.string.travel_packing), stringResource(R.string.travel_buffer),
    )
    val values = listOf(t.defaultTravelMinutes, t.preparationMinutes, t.packingMinutes, t.safetyBufferMinutes)
    OxCard(onClick = {
        onEdit(
            EditRequest(title, values.mapIndexed { i, v -> NumberInput(labels[i], v.toString()) }) { v ->
                vm.updateTravel(
                    v[0]?.toInt() ?: values[0].toInt(), v[1]?.toInt() ?: values[1].toInt(),
                    v[2]?.toInt() ?: values[2].toInt(), v[3]?.toInt() ?: values[3].toInt(),
                )
            },
        )
    }) {
        SectionLabel(title)
        labels.zip(values).forEach { (label, value) -> ValueRow(label, OxFormat.duration(value.toLong())) }
    }
}

@Composable
private fun StudyCard(config: EngineConfig, vm: SettingsViewModel, onEdit: (EditRequest) -> Unit) {
    val s = config.study
    val title = stringResource(R.string.section_study)
    val labels = listOf(stringResource(R.string.study_chunk), stringResource(R.string.study_max), stringResource(R.string.study_revision))
    val values = listOf(s.chunkMinutes, s.maxMinutesPerSubjectPerDay, s.revisionDaysBeforeDeadline)
    OxCard(onClick = {
        onEdit(
            EditRequest(title, values.mapIndexed { i, v -> NumberInput(labels[i], v.toString()) }) { v ->
                vm.updateStudy(v[0]?.toInt() ?: values[0].toInt(), v[1]?.toInt() ?: values[1].toInt(), v[2]?.toInt() ?: values[2].toInt())
            },
        )
    }) {
        SectionLabel(title)
        ValueRow(labels[0], OxFormat.duration(values[0].toLong()))
        ValueRow(labels[1], OxFormat.duration(values[1].toLong()))
        ValueRow(labels[2], values[2].toString())
    }
}

@Composable
private fun StreakCard(config: EngineConfig, vm: SettingsViewModel, onEdit: (EditRequest) -> Unit) {
    val minLabel = stringResource(R.string.streak_min)
    val targetLabel = stringResource(R.string.heatmap_target)
    val target = (config.heatmap.scale as? HeatmapScale.TargetRelative)?.targetPoints
    OxCard {
        SectionLabel(stringResource(R.string.section_streak))
        ValueRow(minLabel, OxFormat.points(config.streak.minPoints)) {
            onEdit(EditRequest(minLabel, listOf(NumberInput(minLabel, config.streak.minPoints.toString()))) { v ->
                vm.updateStreak(v[0]?.toLong() ?: config.streak.minPoints, config.streak.worstAllowedState)
            })
        }
        Text(stringResource(R.string.streak_state), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayState.entries.forEach { state ->
                FilterChip(
                    selected = config.streak.worstAllowedState == state,
                    onClick = { vm.updateStreak(config.streak.minPoints, state) },
                    label = { Text(state.label()) },
                )
            }
        }
        ValueRow(targetLabel, target?.let { OxFormat.points(it) } ?: stringResource(R.string.heatmap_quantile)) {
            onEdit(EditRequest(targetLabel, listOf(NumberInput(targetLabel, target?.toString().orEmpty(), optional = true))) { v ->
                val value = v[0]?.toLong()
                if (value == null) vm.useQuantileHeatmap() else vm.updateHeatmapTarget(value)
            })
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ValueRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun NumberDialog(request: EditRequest, onDismiss: () -> Unit) {
    val values = remember(request) { mutableStateListOf(*request.inputs.map { it.initial }.toTypedArray()) }
    val parsed = values.map { it.trim().toDoubleOrNull() }
    val valid = request.inputs.indices.all { i -> parsed[i] != null || (request.inputs[i].optional && values[i].isBlank()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                request.hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                request.inputs.forEachIndexed { i, input ->
                    OutlinedTextField(
                        value = values[i],
                        onValueChange = { values[i] = it },
                        label = { Text(input.label) },
                        singleLine = true,
                        isError = parsed[i] == null && !(input.optional && values[i].isBlank()),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            }
        },
        confirmButton = {
            if (request.inputs.isNotEmpty()) {
                TextButton(enabled = valid, onClick = { request.onConfirm(parsed); onDismiss() }) { Text(stringResource(R.string.dialog_save)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
private fun ImportDialog(onImport: (String) -> Unit, onDismiss: () -> Unit) {
    var json by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.config_import)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.config_import_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(json, { json = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp))
            }
        },
        confirmButton = { TextButton(enabled = json.isNotBlank(), onClick = { onImport(json) }) { Text(stringResource(R.string.dialog_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

/** Drops a meaningless ".0" so values read as they were typed. */
private fun number(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
