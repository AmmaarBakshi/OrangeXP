package com.orangexp.feature.academics

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orangexp.core.common.time.EpochDay
import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.common.time.today
import com.orangexp.core.data.model.StudyPlanItem
import com.orangexp.core.data.model.StudyWindow
import com.orangexp.core.data.model.Subject
import com.orangexp.core.data.model.SubjectForecast
import com.orangexp.core.data.model.TimetableEntry
import com.orangexp.core.data.repository.AcademicRepository
import com.orangexp.core.engine.ffi.TimetableConflict
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject

data class PlanDay(val day: EpochDay, val items: List<StudyPlanItem>)

data class AcademicsUiState(
    val loading: Boolean = true,
    val today: EpochDay = 0,
    val planDays: List<PlanDay> = emptyList(),
    val forecasts: List<SubjectForecast> = emptyList(),
    val subjects: List<Subject> = emptyList(),
    val timetable: Map<DayOfWeek, List<TimetableEntry>> = emptyMap(),
    val conflicts: List<TimetableConflict> = emptyList(),
    val studyWindows: List<StudyWindow> = emptyList(),
) {
    fun classTitle(id: String): String =
        timetable.values.flatten().firstOrNull { it.id.toString() == id }?.title ?: id
}

@HiltViewModel
class AcademicsViewModel @Inject constructor(
    private val academics: AcademicRepository,
    time: TimeSource,
) : ViewModel() {

    private val today = time.today()

    val uiState: StateFlow<AcademicsUiState> = combine(
        combine(academics.plan(today, today + PLAN_DAYS - 1), academics.forecasts, ::Pair),
        academics.subjects,
        combine(academics.timetable, academics.timetableConflicts, academics.studyWindows, ::Triple),
    ) { (plan, forecasts), subjects, (timetable, conflicts, windows) ->
        AcademicsUiState(
            loading = false,
            today = today,
            planDays = plan.groupBy { it.day }.map { (day, items) -> PlanDay(day, items) }.sortedBy { it.day },
            forecasts = forecasts,
            subjects = subjects,
            timetable = timetable.groupBy { it.weekday }.toSortedMap(),
            conflicts = conflicts,
            studyWindows = windows,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AcademicsUiState())

    fun saveSubject(id: Long, name: String, examDay: EpochDay?, priority: Int) =
        launch { academics.saveSubject(id, name, examDay, priority) }

    fun deleteSubject(id: Long) = launch { academics.deleteSubject(id) }

    fun addUnit(subjectId: Long, title: String) = launch { academics.addUnit(subjectId, title) }

    fun deleteUnit(id: Long) = launch { academics.deleteUnit(id) }

    fun addTopic(unitId: Long, subjectId: Long, title: String, minutes: Int, difficulty: Int) =
        launch { academics.addTopic(unitId, subjectId, title, minutes, difficulty) }

    fun deleteTopic(id: Long) = launch { academics.deleteTopic(id) }

    fun setTopicCompleted(id: Long, completed: Boolean) = launch { academics.setTopicCompleted(id, completed) }

    fun saveClass(entry: TimetableEntry) = launch { academics.saveTimetableEntry(entry) }

    fun deleteClass(id: Long) = launch { academics.deleteTimetableEntry(id) }

    fun addStudyWindow(weekday: DayOfWeek, start: Int, end: Int) = launch { academics.addStudyWindow(weekday, start, end) }

    fun deleteStudyWindow(window: StudyWindow) = launch { academics.deleteStudyWindow(window) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "Academic action failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "AcademicsViewModel"
        const val PLAN_DAYS = 14
    }
}
