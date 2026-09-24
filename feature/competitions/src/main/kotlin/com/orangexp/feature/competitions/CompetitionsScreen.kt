package com.orangexp.feature.competitions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orangexp.core.data.repository.CompetitionItem
import com.orangexp.core.designsystem.component.Dot
import com.orangexp.core.designsystem.component.EmptyState
import com.orangexp.core.designsystem.component.OxCard
import com.orangexp.core.designsystem.component.SectionLabel
import com.orangexp.core.designsystem.format.OxFormat
import com.orangexp.core.designsystem.theme.NumeralFamily
import com.orangexp.core.designsystem.theme.OxTheme
import com.orangexp.core.engine.ffi.CompetitionAssessment
import com.orangexp.core.engine.ffi.CompetitionResult
import com.orangexp.core.engine.ffi.CompetitionStatus
import com.orangexp.core.engine.ffi.CompetitionVerdict
import com.orangexp.core.ui.formatMedium
import kotlin.math.roundToInt

private enum class CompetitionsTab(val label: Int) { Upcoming(R.string.comp_tab_upcoming), Past(R.string.comp_tab_past), Team(R.string.comp_tab_team) }

private sealed interface Dialog {
    data class Edit(val item: CompetitionItem?) : Dialog
    data class Result(val item: CompetitionItem) : Dialog
    data object AddMember : Dialog
}

@Composable
fun CompetitionsRoute(viewModel: CompetitionsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(CompetitionsTab.Upcoming) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            CompetitionsTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(stringResource(t.label)) }) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (tab) {
                CompetitionsTab.Upcoming -> upcoming(state, viewModel) { dialog = it }
                CompetitionsTab.Past -> past(state) { dialog = it }
                CompetitionsTab.Team -> team(state, viewModel) { dialog = it }
            }
        }
    }

    val dismiss = { dialog = null }
    when (val d = dialog) {
        is Dialog.Edit -> CompetitionDialog(d.item, state.members, onSave = { item, ids -> viewModel.save(item, ids); dismiss() }, onDismiss = dismiss)
        is Dialog.Result -> ResultDialog(d.item, onResult = { viewModel.recordResult(d.item, it); dismiss() }, onDismiss = dismiss)
        Dialog.AddMember -> MemberDialog(onSave = { viewModel.saveMember(it); dismiss() }, onDismiss = dismiss)
        null -> Unit
    }
}

private fun LazyListScope.upcoming(state: CompetitionsUiState, vm: CompetitionsViewModel, open: (Dialog) -> Unit) {
    val overview = state.overview ?: return
    item { CapacityCard(state) }
    item {
        FilledTonalButton(onClick = { open(Dialog.Edit(null)) }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.comp_add_competition), Modifier.padding(start = 8.dp))
        }
    }
    if (state.upcoming.isEmpty()) item { EmptyState(stringResource(R.string.comp_tab_upcoming), stringResource(R.string.comp_empty_upcoming)) }
    items(state.upcoming, key = { it.id }) { item ->
        CompetitionCard(item, overview.assessments[item.id]) {
            if (item.status == CompetitionStatus.PLANNED) {
                TextButton(onClick = { vm.register(item) }) { Text(stringResource(R.string.comp_action_register)) }
            }
            if (item.eventStartDay <= overview.today) {
                TextButton(onClick = { open(Dialog.Result(item)) }) { Text(stringResource(R.string.comp_action_result)) }
            }
            IconButton(onClick = { open(Dialog.Edit(item)) }) { Icon(Icons.Filled.Edit, stringResource(R.string.comp_action_edit)) }
            IconButton(onClick = { vm.delete(item.id) }) { Icon(Icons.Filled.Delete, stringResource(R.string.comp_action_delete), tint = OxTheme.colors.subtle) }
        }
    }
}

@Composable
private fun CapacityCard(state: CompetitionsUiState) {
    val plan = state.overview?.plan ?: return
    val config = state.config
    OxCard {
        SectionLabel(stringResource(R.string.comp_capacity_label))
        Text(
            plan.maxParallel.toString(),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(
                R.string.comp_capacity_detail,
                plan.peakConcurrency.toInt(),
                OxFormat.duration((plan.spareHoursNext30Days * 60).roundToInt().toLong()),
                plan.additionalCapacity.toInt(),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (config != null) {
            Text(
                stringResource(R.string.comp_capacity_hint, hours(config.weekdayHours), hours(config.weekendHours)),
                style = MaterialTheme.typography.bodySmall,
                color = OxTheme.colors.subtle,
            )
        }
    }
}

@Composable
private fun CompetitionCard(item: CompetitionItem, assessment: CompetitionAssessment?, actions: @Composable RowScope.() -> Unit) {
    OxCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            assessment?.let { VerdictLabel(it.verdict) }
        }
        Text(
            if (item.eventEndDay > item.eventStartDay) {
                stringResource(R.string.comp_competition_dates_range, item.prepStartDay.formatMedium(), item.eventStartDay.formatMedium(), item.eventEndDay.formatMedium())
            } else {
                stringResource(R.string.comp_competition_dates, item.prepStartDay.formatMedium(), item.eventStartDay.formatMedium())
            },
            style = MaterialTheme.typography.bodySmall,
            color = OxTheme.colors.subtle,
        )
        assessment?.takeIf { it.verdict != CompetitionVerdict.INACTIVE }?.let {
            Text(
                stringResource(R.string.comp_competition_prep, hours(it.remainingPrepHours), hours(it.windowHours)) + " · " +
                    stringResource(R.string.comp_competition_importance, item.importance),
                style = MaterialTheme.typography.bodySmall,
                color = OxTheme.colors.subtle,
            )
        }
        if (item.members.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.members.forEach { AssistChip(onClick = {}, label = { Text(it.name) }) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
private fun VerdictLabel(verdict: CompetitionVerdict) {
    val (label, color) = when (verdict) {
        CompetitionVerdict.COMMITTED -> R.string.comp_verdict_committed to MaterialTheme.colorScheme.primary
        CompetitionVerdict.OVERCOMMITTED -> R.string.comp_verdict_overcommitted to OxTheme.colors.stateBad
        CompetitionVerdict.RECOMMENDED -> R.string.comp_verdict_recommended to OxTheme.colors.stateGood
        CompetitionVerdict.NOT_ENOUGH_TIME -> R.string.comp_verdict_not_enough_time to OxTheme.colors.stateModerate
        CompetitionVerdict.TOO_MANY_AT_ONCE -> R.string.comp_verdict_too_many to OxTheme.colors.stateModerate
        CompetitionVerdict.INACTIVE -> R.string.comp_verdict_inactive to OxTheme.colors.subtle
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dot(color)
        Text(stringResource(label).uppercase(), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private fun LazyListScope.past(state: CompetitionsUiState, open: (Dialog) -> Unit) {
    if (state.past.isEmpty()) item { EmptyState(stringResource(R.string.comp_tab_past), stringResource(R.string.comp_empty_past)) }
    items(state.past, key = { it.id }) { item ->
        CompetitionCard(item, null) {
            Text(
                resultLabel(item),
                style = MaterialTheme.typography.titleSmall,
                color = if (item.result == CompetitionResult.WON) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { open(Dialog.Result(item)) }) { Text(stringResource(R.string.comp_action_result)) }
        }
    }
}

@Composable
private fun resultLabel(item: CompetitionItem): String {
    val result = item.result
    return when {
        item.status == CompetitionStatus.WITHDRAWN -> stringResource(R.string.comp_result_withdrawn)
        result != null -> stringResource(result.labelRes())
        else -> stringResource(R.string.comp_result_pending)
    }
}

internal fun CompetitionResult.labelRes(): Int = when (this) {
    CompetitionResult.WON -> R.string.comp_result_won
    CompetitionResult.PODIUM -> R.string.comp_result_podium
    CompetitionResult.FINALIST -> R.string.comp_result_finalist
    CompetitionResult.PARTICIPATED -> R.string.comp_result_participated
}

private fun LazyListScope.team(state: CompetitionsUiState, vm: CompetitionsViewModel, open: (Dialog) -> Unit) {
    val overview = state.overview
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { open(Dialog.AddMember) }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.comp_team_add), Modifier.padding(start = 8.dp))
            }
            Text(stringResource(R.string.comp_team_hint), style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle)
        }
    }
    if (state.members.isEmpty()) item { EmptyState(stringResource(R.string.comp_tab_team), stringResource(R.string.comp_team_empty)) }
    overview?.teammates.orEmpty().forEachIndexed { index, ranked ->
        item(key = "ranked-${ranked.member.id}") {
            val s = ranked.stats
            OxCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "#${index + 1}",
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = NumeralFamily),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(52.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(ranked.member.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.comp_team_stats, s.competitions.toInt(), s.wins.toInt(), s.podiums.toInt(), s.winRatePercent.roundToInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = OxTheme.colors.subtle,
                        )
                    }
                    Text(stringResource(R.string.comp_team_score, OxFormat.points(s.score.roundToInt().toLong())), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { vm.deleteMember(ranked.member.id) }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.comp_action_delete), tint = OxTheme.colors.subtle)
                    }
                }
            }
        }
    }
    val newcomers = overview?.newMembers.orEmpty()
    if (newcomers.isNotEmpty()) {
        item(key = "newcomers") {
            OxCard {
                SectionLabel(stringResource(R.string.comp_team_new))
                newcomers.forEach { member ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(member.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.deleteMember(member.id) }) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.comp_action_delete), tint = OxTheme.colors.subtle)
                        }
                    }
                }
            }
        }
    }
}

internal fun hours(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(value)
