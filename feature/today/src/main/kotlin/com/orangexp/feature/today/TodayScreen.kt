package com.orangexp.feature.today

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orangexp.core.data.model.AttendanceStatus
import com.orangexp.core.data.model.StudyPlanItem
import com.orangexp.core.data.repository.DayDeparture
import com.orangexp.core.data.repository.TripStatus
import com.orangexp.core.designsystem.component.CounterBlock
import com.orangexp.core.designsystem.component.HeatCell
import com.orangexp.core.designsystem.component.MetricTile
import com.orangexp.core.designsystem.component.OxCard
import com.orangexp.core.designsystem.component.SectionLabel
import com.orangexp.core.designsystem.component.StatePill
import com.orangexp.core.designsystem.format.OxFormat
import com.orangexp.core.designsystem.theme.NumeralFamily
import com.orangexp.core.designsystem.theme.OxTheme
import com.orangexp.core.engine.ffi.TravelEstimateSource
import com.orangexp.core.sensing.TrackingPermissions
import com.orangexp.core.ui.ScoreBreakdown
import com.orangexp.core.ui.formatFull
import com.orangexp.core.ui.label
import com.orangexp.core.ui.tone
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

@Composable
fun TodayRoute(viewModel: TodayViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    TodayScreen(
        state = state,
        actions = TodayActions(
            openUsageAccess = { context.startActivity(viewModel.usageAccessIntent()) },
            permissionsChanged = viewModel::onResume,
            setAttendance = viewModel::setAttendance,
            setTopicCompleted = viewModel::setTopicCompleted,
            startStudy = viewModel::startStudy,
            stopStudy = viewModel::stopStudy,
            leftHome = viewModel::recordDeparture,
            arrived = viewModel::recordArrival,
        ),
    )
}

data class TodayActions(
    val openUsageAccess: () -> Unit = {},
    val permissionsChanged: () -> Unit = {},
    val setAttendance: (Long, AttendanceStatus?) -> Unit = { _, _ -> },
    val setTopicCompleted: (Long, Boolean) -> Unit = { _, _ -> },
    val startStudy: (Long) -> Unit = {},
    val stopStudy: () -> Unit = {},
    val leftHome: () -> Unit = {},
    val arrived: () -> Unit = {},
)

@Composable
fun TodayScreen(state: TodayUiState, actions: TodayActions, modifier: Modifier = Modifier) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScoreHeader(state) }
        state.permissions?.takeIf { !it.trackingReady || !it.notifications }?.let { perms ->
            item { SetupCard(perms, actions) }
        }
        item { MetricsCard(state, now) }
        state.departure?.let { departure -> item { DepartureCard(departure, state.trip, actions) } }
        item { ClassesCard(state.classes, actions) }
        item { PlanCard(state, now, actions) }
        item {
            OxCard {
                SectionLabel(stringResource(R.string.breakdown_title))
                ScoreBreakdown(state.contributions, state.totalPoints)
            }
        }
    }
}

@Composable
private fun ScoreHeader(state: TodayUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.day != 0) SectionLabel(state.day.formatFull())
        CounterBlock(value = state.totalPoints, caption = stringResource(R.string.today_points))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatePill(state.state.tone(), state.state.label())
            Icon(Icons.Filled.LocalFireDepartment, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = if (state.streakDays > 0) {
                    stringResource(R.string.today_streak, state.streakDays)
                } else {
                    stringResource(R.string.today_no_streak)
                },
                style = MaterialTheme.typography.titleSmall,
            )
        }
        state.stateReason?.let {
            Text(stringResource(R.string.today_state_reason, it), style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle)
        }
        if (state.weekStrip.isNotEmpty()) WeekStrip(state.weekStrip)
    }
}

@Composable
private fun WeekStrip(cells: List<HeatCell>) {
    val heat = OxTheme.colors.heat
    val todayOutline = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(R.string.today_this_week), Modifier.width(88.dp))
        cells.forEach { cell ->
            Canvas(Modifier.size(18.dp)) {
                val corner = CornerRadius(size.minDimension * 0.22f)
                drawRoundRect(heat[cell.level.coerceIn(0, heat.lastIndex)], cornerRadius = corner)
                if (cell.isToday) {
                    drawRoundRect(todayOutline, cornerRadius = corner, style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun SetupCard(perms: TrackingPermissions, actions: TodayActions) {
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        actions.permissionsChanged()
    }
    OxCard {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)
        if (!perms.usageAccess) {
            SetupStep(stringResource(R.string.setup_usage_body), stringResource(R.string.setup_usage_action), actions.openUsageAccess)
        }
        if (perms.stepSensorAvailable && !perms.activityRecognition) {
            SetupStep(stringResource(R.string.setup_activity_body), stringResource(R.string.setup_activity_action)) {
                permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
        if (!perms.notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SetupStep(stringResource(R.string.setup_notifications_body), stringResource(R.string.setup_notifications_action)) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
private fun SetupStep(body: String, action: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(body, style = MaterialTheme.typography.bodyMedium, color = OxTheme.colors.subtle)
        FilledTonalButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun MetricsCard(state: TodayUiState, now: Long) {
    val none = stringResource(R.string.metric_none)
    val zone = ZoneId.systemDefault()
    OxCard {
        Row(Modifier.fillMaxWidth()) {
            MetricTile(
                label = stringResource(R.string.metric_sleep),
                value = state.sleepMinutes?.let { OxFormat.duration(it.toLong()) } ?: none,
                detail = if (state.sleepMinutes != null && state.sleepConfidence != null) {
                    stringResource(R.string.metric_sleep_estimated, state.sleepConfidence)
                } else {
                    stringResource(R.string.metric_sleep_unknown)
                },
                modifier = Modifier.weight(1f),
            )
            if (state.restingSinceMs != null) {
                MetricTile(
                    label = stringResource(R.string.metric_asleep),
                    value = OxFormat.duration((now - state.restingSinceMs) / 60_000),
                    detail = stringResource(R.string.metric_asleep_since, clockOf(state.restingSinceMs, zone)),
                    modifier = Modifier.weight(1f),
                )
            } else {
                MetricTile(
                    label = stringResource(R.string.metric_awake),
                    value = state.awakeSinceMs?.let { OxFormat.duration((now - it) / 60_000) } ?: none,
                    detail = state.awakeSinceMs?.let { stringResource(R.string.metric_awake_since, clockOf(it, zone)) },
                    accent = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            MetricTile(
                label = stringResource(R.string.metric_walked),
                value = state.distanceMeters?.let { OxFormat.distance(it) } ?: none,
                detail = state.steps?.let { stringResource(R.string.metric_steps, OxFormat.points(it)) },
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = stringResource(R.string.metric_syllabus),
                value = if (state.plannedTopics > 0) "${state.plannedTopicsDone} / ${state.plannedTopics}" else none,
                detail = stringResource(R.string.metric_syllabus_detail),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DepartureCard(departure: DayDeparture, trip: TripStatus, actions: TodayActions) {
    val plan = departure.plan
    val zone = ZoneId.systemDefault()
    OxCard {
        SectionLabel(stringResource(R.string.departure_title))
        TimelineRow(OxFormat.clock(plan.startPreparingMinute), stringResource(R.string.departure_prepare))
        TimelineRow(OxFormat.clock(plan.leaveMinute), stringResource(R.string.departure_leave), highlight = true)
        TimelineRow(OxFormat.clock(plan.eventStartMinute), stringResource(R.string.departure_class))
        val source = when (plan.travelSource) {
            TravelEstimateSource.CONFIGURED -> stringResource(R.string.departure_source_configured)
            TravelEstimateSource.SLOT_OVERRIDE -> stringResource(R.string.departure_source_slot)
            TravelEstimateSource.HISTORY -> stringResource(R.string.departure_source_history)
        }
        Text(
            stringResource(R.string.departure_detail, plan.travelMinutes.toInt(), source, plan.earlyByMinutes),
            style = MaterialTheme.typography.bodySmall,
            color = OxTheme.colors.subtle,
        )
        val departed = trip.departedMs
        val arrived = trip.arrivedMs
        when {
            departed == null -> FilledTonalButton(onClick = actions.leftHome) { Text(stringResource(R.string.departure_left)) }
            arrived == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.departure_left_at, clockOf(departed, zone)), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = actions.arrived) { Text(stringResource(R.string.departure_arrived)) }
            }
            else -> Text(
                stringResource(R.string.departure_arrived_at, clockOf(departed, zone), clockOf(arrived, zone)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TimelineRow(time: String, label: String, highlight: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            time,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = NumeralFamily),
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ClassesCard(classes: List<ClassItem>, actions: TodayActions) {
    OxCard {
        SectionLabel(stringResource(R.string.classes_title))
        if (classes.isEmpty()) {
            Text(stringResource(R.string.classes_empty), style = MaterialTheme.typography.bodyMedium, color = OxTheme.colors.subtle)
        }
        classes.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TimelineRow(
                    "${OxFormat.clock(item.startMinute)}–${OxFormat.clock(item.endMinute)}",
                    listOf(item.title, item.location).filter { it.isNotBlank() }.joinToString(" · "),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AttendanceStatus.entries.forEach { status ->
                        FilterChip(
                            selected = item.attendance == status,
                            onClick = { actions.setAttendance(item.slotId, if (item.attendance == status) null else status) },
                            label = { Text(stringResource(status.labelRes())) },
                        )
                    }
                }
            }
        }
    }
}

private fun AttendanceStatus.labelRes(): Int = when (this) {
    AttendanceStatus.ATTENDED -> R.string.attendance_attended
    AttendanceStatus.MISSED -> R.string.attendance_missed
    AttendanceStatus.CANCELLED -> R.string.attendance_cancelled
}

@Composable
private fun PlanCard(state: TodayUiState, now: Long, actions: TodayActions) {
    OxCard {
        SectionLabel(stringResource(R.string.plan_title))
        state.runningStudy?.let { running ->
            Text(
                stringResource(R.string.plan_running, OxFormat.duration((now - running.startMs) / 60_000)),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (state.plan.isEmpty()) {
            Text(stringResource(R.string.plan_empty), style = MaterialTheme.typography.bodyMedium, color = OxTheme.colors.subtle)
        }
        state.plan.forEach { item -> PlanRow(item, running = state.runningStudy?.topicId == item.topicId, actions) }
    }
}

@Composable
private fun PlanRow(item: StudyPlanItem, running: Boolean, actions: TodayActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { actions.setTopicCompleted(item.topicId, !item.topicCompleted) }) {
            Icon(
                if (item.topicCompleted) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = stringResource(R.string.plan_mark_done),
                tint = if (item.topicCompleted) MaterialTheme.colorScheme.primary else OxTheme.colors.subtle,
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(item.topicTitle, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${item.subjectName} · " + stringResource(
                    R.string.plan_part, item.partIndex, item.partCount, OxFormat.duration(item.minutes.toLong()),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = OxTheme.colors.subtle,
            )
        }
        if (!item.topicCompleted) {
            IconButton(onClick = { if (running) actions.stopStudy() else actions.startStudy(item.topicId) }) {
                Icon(
                    if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (running) R.string.plan_stop else R.string.plan_start),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun clockOf(epochMs: Long, zone: ZoneId): String {
    val time = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
    return OxFormat.clock(time.hour * 60 + time.minute)
}
