package com.orangexp.feature.academics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.orangexp.core.data.model.Subject
import com.orangexp.core.data.model.SubjectForecast
import com.orangexp.core.designsystem.component.Dot
import com.orangexp.core.designsystem.component.EmptyState
import com.orangexp.core.designsystem.component.OxCard
import com.orangexp.core.designsystem.component.SectionLabel
import com.orangexp.core.designsystem.format.OxFormat
import com.orangexp.core.designsystem.theme.OxTheme
import com.orangexp.core.engine.ffi.SubjectStatus
import com.orangexp.core.ui.formatMedium
import com.orangexp.core.ui.formatShort
import java.time.format.TextStyle
import java.util.Locale

private enum class AcademicsTab(val label: Int) { Plan(R.string.tab_plan), Syllabus(R.string.tab_syllabus), Timetable(R.string.tab_timetable) }

/** Which dialog is open. Kept as plain data so the screen stays stateless below the route. */
private sealed interface DialogRequest {
    data object AddSubject : DialogRequest
    data class EditSubject(val subject: Subject) : DialogRequest
    data class AddUnit(val subjectId: Long) : DialogRequest
    data class AddTopic(val unitId: Long, val subjectId: Long) : DialogRequest
    data object AddClass : DialogRequest
    data object AddStudyWindow : DialogRequest
}

@Composable
fun AcademicsRoute(viewModel: AcademicsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(AcademicsTab.Plan) }
    var dialog by remember { mutableStateOf<DialogRequest?>(null) }

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            AcademicsTab.entries.forEach { t ->
                Tab(selected = tab == t, onClick = { tab = t }, text = { Text(stringResource(t.label)) })
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (tab) {
                AcademicsTab.Plan -> planTab(state)
                AcademicsTab.Syllabus -> syllabusTab(state, viewModel, onDialog = { dialog = it })
                AcademicsTab.Timetable -> timetableTab(state, viewModel, onDialog = { dialog = it })
            }
        }
    }

    val dismiss = { dialog = null }
    when (val request = dialog) {
        DialogRequest.AddSubject -> SubjectDialog("", null, 3, stringResource(R.string.syllabus_add_subject), { name, exam, priority ->
            viewModel.saveSubject(0, name, exam, priority); dismiss()
        }, dismiss)
        is DialogRequest.EditSubject -> SubjectDialog(
            request.subject.name, request.subject.examDay, request.subject.priority,
            stringResource(R.string.syllabus_edit_subject),
            { name, exam, priority -> viewModel.saveSubject(request.subject.id, name, exam, priority); dismiss() },
            dismiss,
        )
        is DialogRequest.AddUnit -> TextInputDialog(stringResource(R.string.syllabus_add_unit), stringResource(R.string.field_title), {
            viewModel.addUnit(request.subjectId, it); dismiss()
        }, dismiss)
        is DialogRequest.AddTopic -> TopicDialog(stringResource(R.string.syllabus_add_topic), { title, minutes, difficulty ->
            viewModel.addTopic(request.unitId, request.subjectId, title, minutes, difficulty); dismiss()
        }, dismiss)
        DialogRequest.AddClass -> ClassDialog(stringResource(R.string.timetable_add_class), { viewModel.saveClass(it); dismiss() }, dismiss)
        DialogRequest.AddStudyWindow -> StudyWindowDialog(stringResource(R.string.study_windows_add), { day, start, end ->
            viewModel.addStudyWindow(day, start, end); dismiss()
        }, dismiss)
        null -> Unit
    }
}

private fun LazyListScope.planTab(state: AcademicsUiState) {
    if (state.forecasts.isNotEmpty()) {
        item {
            OxCard {
                SectionLabel(stringResource(R.string.plan_forecast))
                state.forecasts.forEach { ForecastRow(it) }
            }
        }
    }
    item { SectionLabel(stringResource(R.string.plan_upcoming)) }
    if (state.planDays.isEmpty()) {
        item { EmptyState(stringResource(R.string.tab_plan), stringResource(R.string.academics_plan_empty)) }
    }
    items(state.planDays, key = { it.day }) { day ->
        OxCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(day.day.formatShort(), Modifier.weight(1f))
                Text(OxFormat.duration(day.items.sumOf { it.minutes }.toLong()), style = MaterialTheme.typography.labelLarge)
            }
            day.items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (item.topicCompleted) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (item.topicCompleted) MaterialTheme.colorScheme.primary else OxTheme.colors.subtle,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(item.topicTitle, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.plan_item, item.subjectName, stringResource(R.string.academics_plan_part, item.partIndex, item.partCount)),
                            style = MaterialTheme.typography.bodySmall,
                            color = OxTheme.colors.subtle,
                        )
                    }
                    Text(OxFormat.duration(item.minutes.toLong()), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun ForecastRow(forecast: SubjectForecast) {
    val (label, color) = when (forecast.status) {
        SubjectStatus.COMPLETE -> stringResource(R.string.status_complete) to OxTheme.colors.stateGood
        SubjectStatus.ON_TRACK -> stringResource(R.string.status_on_track) to OxTheme.colors.stateGood
        SubjectStatus.AT_RISK -> stringResource(R.string.status_at_risk) to OxTheme.colors.stateBad
        SubjectStatus.DEADLINE_PASSED -> stringResource(R.string.status_deadline_passed) to OxTheme.colors.subtle
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(color)
            Text(forecast.subjectName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = color)
        }
        val targetDay = forecast.targetDay
        val detail = if (forecast.shortfallMinutes > 0 && targetDay != null) {
            stringResource(R.string.forecast_shortfall, OxFormat.duration(forecast.shortfallMinutes.toLong()), targetDay.formatMedium())
        } else {
            stringResource(
                R.string.forecast_detail,
                OxFormat.duration(forecast.remainingMinutes.toLong()),
                forecast.projectedFinishDay?.formatMedium() ?: stringResource(R.string.forecast_no_finish),
            )
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle)
    }
}

private fun LazyListScope.syllabusTab(state: AcademicsUiState, vm: AcademicsViewModel, onDialog: (DialogRequest) -> Unit) {
    item {
        FilledTonalButton(onClick = { onDialog(DialogRequest.AddSubject) }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.syllabus_add_subject), Modifier.padding(start = 8.dp))
        }
    }
    if (state.subjects.isEmpty()) {
        item { EmptyState(stringResource(R.string.tab_syllabus), stringResource(R.string.syllabus_empty)) }
    }
    items(state.subjects, key = { it.id }) { subject -> SubjectCard(subject, vm, onDialog) }
}

@Composable
private fun SubjectCard(subject: Subject, vm: AcademicsViewModel, onDialog: (DialogRequest) -> Unit) {
    val total = subject.topics.size
    OxCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(subject.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    (subject.examDay?.let { stringResource(R.string.syllabus_exam, it.formatMedium()) } ?: stringResource(R.string.syllabus_no_exam)) +
                        " · " + stringResource(R.string.syllabus_progress, subject.completedTopics, total),
                    style = MaterialTheme.typography.bodySmall,
                    color = OxTheme.colors.subtle,
                )
            }
            IconButton(onClick = { onDialog(DialogRequest.EditSubject(subject)) }) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.syllabus_edit_subject))
            }
            IconButton(onClick = { vm.deleteSubject(subject.id) }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.syllabus_delete))
            }
        }
        if (total > 0) {
            LinearProgressIndicator(
                progress = { subject.completedTopics.toFloat() / total },
                modifier = Modifier.fillMaxWidth(),
                trackColor = OxTheme.colors.heat[0],
            )
        }
        subject.units.forEach { unit ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(unit.title, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                IconButton(onClick = { onDialog(DialogRequest.AddTopic(unit.id, subject.id)) }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.syllabus_add_topic))
                }
                IconButton(onClick = { vm.deleteUnit(unit.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.syllabus_delete), tint = OxTheme.colors.subtle)
                }
            }
            unit.topics.forEach { topic ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.setTopicCompleted(topic.id, !topic.isCompleted) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (topic.isCompleted) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (topic.isCompleted) MaterialTheme.colorScheme.primary else OxTheme.colors.subtle,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(topic.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.syllabus_topic_detail, OxFormat.duration(topic.estimatedMinutes.toLong()), topic.difficulty),
                            style = MaterialTheme.typography.bodySmall,
                            color = OxTheme.colors.subtle,
                        )
                    }
                    IconButton(onClick = { vm.deleteTopic(topic.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.syllabus_delete), tint = OxTheme.colors.subtle)
                    }
                }
            }
        }
        TextButton(onClick = { onDialog(DialogRequest.AddUnit(subject.id)) }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.syllabus_add_unit), Modifier.padding(start = 8.dp))
        }
    }
}

private fun LazyListScope.timetableTab(state: AcademicsUiState, vm: AcademicsViewModel, onDialog: (DialogRequest) -> Unit) {
    val locale = Locale.getDefault()
    item {
        FilledTonalButton(onClick = { onDialog(DialogRequest.AddClass) }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.timetable_add_class), Modifier.padding(start = 8.dp))
        }
    }
    if (state.conflicts.isNotEmpty()) {
        item {
            OxCard {
                state.conflicts.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = OxTheme.colors.stateBad)
                        Text(
                            stringResource(R.string.timetable_conflict, state.classTitle(c.firstId), state.classTitle(c.secondId), c.overlapMinutes.toInt()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
    if (state.timetable.isEmpty()) {
        item { EmptyState(stringResource(R.string.tab_timetable), stringResource(R.string.timetable_empty)) }
    }
    state.timetable.forEach { (weekday, entries) ->
        item(key = "day-$weekday") {
            OxCard {
                SectionLabel(weekday.getDisplayName(TextStyle.FULL, locale))
                entries.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${OxFormat.clock(entry.startMinute)}–${OxFormat.clock(entry.endMinute)}  ${entry.title}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            val detail = listOfNotNull(
                                entry.location.ifBlank { null },
                                entry.teacher.ifBlank { null },
                                if (entry.requiresTravel) stringResource(R.string.timetable_travel) else null,
                            ).joinToString(" · ")
                            if (detail.isNotEmpty()) {
                                Text(detail, style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle)
                            }
                        }
                        IconButton(onClick = { vm.deleteClass(entry.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.syllabus_delete), tint = OxTheme.colors.subtle)
                        }
                    }
                }
            }
        }
    }
    item {
        OxCard {
            SectionLabel(stringResource(R.string.study_windows))
            Text(stringResource(R.string.study_windows_hint), style = MaterialTheme.typography.bodySmall, color = OxTheme.colors.subtle)
            state.studyWindows.forEach { window ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${window.weekday.getDisplayName(TextStyle.SHORT, locale)}  ${OxFormat.clock(window.startMinute)}–${OxFormat.clock(window.endMinute)}",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.deleteStudyWindow(window) }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.syllabus_delete), tint = OxTheme.colors.subtle)
                    }
                }
            }
            TextButton(onClick = { onDialog(DialogRequest.AddStudyWindow) }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.study_windows_add), Modifier.padding(start = 8.dp))
            }
        }
    }
}
