package com.orangexp.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orangexp.core.data.model.DayBreakdown
import com.orangexp.core.designsystem.component.ContributionHeatmap
import com.orangexp.core.designsystem.component.CounterBlock
import com.orangexp.core.designsystem.component.HeatmapLegend
import com.orangexp.core.designsystem.component.MetricTile
import com.orangexp.core.designsystem.component.OxCard
import com.orangexp.core.designsystem.component.SectionLabel
import com.orangexp.core.designsystem.component.StatePill
import com.orangexp.core.designsystem.format.OxFormat
import com.orangexp.core.designsystem.theme.OxTheme
import com.orangexp.core.engine.MetricKeys
import com.orangexp.core.engine.ffi.XpPool
import com.orangexp.core.ui.ScoreBreakdown
import com.orangexp.core.ui.formatFull
import com.orangexp.core.ui.formatMedium
import com.orangexp.core.ui.formatMetric
import com.orangexp.core.ui.label
import com.orangexp.core.ui.tone
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun HistoryRoute(viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HistoryScreen(state, onSelectDay = viewModel::select)
}

@Composable
fun HistoryScreen(state: HistoryUiState, onSelectDay: (Int) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { LifetimeHeader(state) }
        item { HeatmapCard(state, onSelectDay) }
        state.selectedDay?.let { day -> item { DayDetailCard(day, state.selected) } }
        item { StreakCard(state) }
        item { StatisticsCard(state) }
    }
}

@Composable
private fun LifetimeHeader(state: HistoryUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CounterBlock(
            value = state.lifetimePoints,
            caption = stringResource(R.string.history_lifetime),
            color = MaterialTheme.colorScheme.primary,
        )
        val pools = state.pools.associate { it.pool to it.points }
        Row(Modifier.fillMaxWidth()) {
            PoolTile(R.string.history_pool_study, pools[XpPool.STUDY], Modifier.weight(1f))
            PoolTile(R.string.history_pool_physical, pools[XpPool.PHYSICAL], Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            PoolTile(R.string.history_pool_sleep, pools[XpPool.SLEEP], Modifier.weight(1f))
            PoolTile(R.string.history_pool_discipline, pools[XpPool.DISCIPLINE], Modifier.weight(1f))
        }
    }
}

@Composable
private fun PoolTile(label: Int, points: Long?, modifier: Modifier) {
    MetricTile(label = stringResource(label), value = OxFormat.points(points ?: 0), modifier = modifier)
}

@Composable
private fun HeatmapCard(state: HistoryUiState, onSelectDay: (Int) -> Unit) {
    val locale = Locale.getDefault()
    val rowLabels = DayOfWeek.entries.mapIndexed { i, d -> if (i % 2 == 0) d.getDisplayName(TextStyle.SHORT, locale) else null }
    OxCard {
        SectionLabel(stringResource(R.string.history_heatmap))
        ContributionHeatmap(
            cells = state.cells,
            weekCount = state.weekCount,
            monthLabels = state.monthLabels,
            rowLabels = rowLabels,
            selectedKey = state.selectedDay,
            contentDescription = stringResource(R.string.history_heatmap_description),
            onCellClick = onSelectDay,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.history_active_days, state.activeDaysInRange),
                style = MaterialTheme.typography.bodySmall,
                color = OxTheme.colors.subtle,
                modifier = Modifier.weight(1f),
            )
            HeatmapLegend(stringResource(R.string.history_less), stringResource(R.string.history_more))
        }
        if (state.selectedDay == null) {
            Text(stringResource(R.string.history_select_hint), style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle)
        }
    }
}

@Composable
private fun DayDetailCard(day: Int, breakdown: DayBreakdown?) {
    OxCard {
        SectionLabel(day.formatFull())
        if (breakdown == null) {
            Text(stringResource(R.string.history_no_data), style = MaterialTheme.typography.bodyMedium, color = OxTheme.colors.subtle)
            return@OxCard
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(OxFormat.points(breakdown.totalPoints), style = MaterialTheme.typography.displaySmall)
            StatePill(breakdown.state.tone(), breakdown.state.label())
        }
        val shown = listOf(
            MetricKeys.SLEEP_MINUTES, MetricKeys.WALKING_METERS, MetricKeys.STUDY_MINUTES,
            MetricKeys.TOPICS_COMPLETED, MetricKeys.SCHEDULE_ADHERENCE_PERCENT,
        ).mapNotNull { key -> breakdown.metrics[key]?.let { key to it } }
        shown.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { (key, value) ->
                    MetricTile(label = metricLabel(key), value = formatMetric(key, value), modifier = Modifier.weight(1f))
                }
                if (pair.size == 1) Column(Modifier.weight(1f)) {}
            }
        }
        ScoreBreakdown(breakdown.contributions, breakdown.totalPoints)
    }
}

@Composable
private fun metricLabel(key: String): String = when (key) {
    MetricKeys.SLEEP_MINUTES -> stringResource(com.orangexp.core.ui.R.string.category_sleep)
    MetricKeys.WALKING_METERS -> stringResource(com.orangexp.core.ui.R.string.category_walking)
    MetricKeys.STUDY_MINUTES -> stringResource(com.orangexp.core.ui.R.string.category_study)
    MetricKeys.TOPICS_COMPLETED -> stringResource(com.orangexp.core.ui.R.string.category_syllabus)
    MetricKeys.SCHEDULE_ADHERENCE_PERCENT -> stringResource(com.orangexp.core.ui.R.string.category_schedule)
    else -> key
}

@Composable
private fun StreakCard(state: HistoryUiState) {
    val streak = state.streak ?: return
    OxCard {
        SectionLabel(stringResource(R.string.history_streak))
        Row(Modifier.fillMaxWidth()) {
            MetricTile(
                label = stringResource(R.string.history_streak_current),
                value = stringResource(R.string.history_days, streak.current.toInt()),
                detail = streak.currentStartDay?.formatMedium(),
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = stringResource(R.string.history_streak_longest),
                value = stringResource(R.string.history_days, streak.longest.toInt()),
                detail = streak.longestStartDay?.let { start ->
                    "${start.formatMedium()} – ${streak.longestEndDay?.formatMedium().orEmpty()}"
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatisticsCard(state: HistoryUiState) {
    val stats = state.statistics ?: return
    OxCard {
        SectionLabel(stringResource(R.string.history_stats))
        Row(Modifier.fillMaxWidth()) {
            MetricTile(stringResource(R.string.history_avg7), OxFormat.points(stats.rollingAverage7.roundToLong()), Modifier.weight(1f))
            MetricTile(stringResource(R.string.history_avg30), OxFormat.points(stats.rollingAverage30.roundToLong()), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            MetricTile(stringResource(R.string.history_consistency), OxFormat.percent(stats.consistencyPercent30), Modifier.weight(1f))
            val trend = stats.trendPerDay30.roundToLong()
            MetricTile(
                stringResource(R.string.history_trend),
                stringResource(R.string.history_trend_value, OxFormat.signedPoints(trend)),
                Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            MetricTile(stringResource(R.string.history_median), OxFormat.points(stats.medianPoints.roundToLong()), Modifier.weight(1f))
            MetricTile(
                label = stringResource(R.string.history_best),
                value = OxFormat.points(stats.bestDay?.points ?: 0),
                detail = stats.bestDay?.epochDay?.formatMedium(),
                modifier = Modifier.weight(1f),
            )
        }
        MetricTile(stringResource(R.string.history_tracked), stats.daysTracked.toString())
    }
}
