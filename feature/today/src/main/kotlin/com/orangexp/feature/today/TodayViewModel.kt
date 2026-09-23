package com.orangexp.feature.today

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orangexp.core.common.time.EpochDay
import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.common.time.dayOfWeek
import com.orangexp.core.common.time.today
import com.orangexp.core.data.model.AttendanceStatus
import com.orangexp.core.data.repository.AcademicRepository
import com.orangexp.core.data.repository.DayRepository
import com.orangexp.core.data.repository.TravelRepository
import com.orangexp.core.data.tracking.TrackingCoordinator
import com.orangexp.core.engine.MetricKeys
import com.orangexp.core.engine.ffi.DayState
import com.orangexp.core.sensing.TrackingPermissionChecker
import com.orangexp.core.ui.toHeatCells
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val days: DayRepository,
    private val academics: AcademicRepository,
    private val travel: TravelRepository,
    private val tracking: TrackingCoordinator,
    private val permissionChecker: TrackingPermissionChecker,
    private val time: TimeSource,
) : ViewModel() {

    private val today = MutableStateFlow(time.today())
    private val permissions = MutableStateFlow(permissionChecker.current())

    val uiState: StateFlow<TodayUiState> = today.flatMapLatest { day -> stateFor(day) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    private fun stateFor(day: EpochDay) = combine(
        combine(days.breakdown(day), days.history(heatmapDays = 7), days.sleepStatus, ::Triple),
        combine(academics.timetable, academics.attendance(day), academics.plan(day, day), academics.runningStudy, ::Quad),
        combine(travel.departure(day), travel.trip(day), ::Pair),
        permissions,
    ) { (breakdown, history, sleep), (timetable, attendance, plan, running), (departure, trip), perms ->
        val metrics = breakdown?.metrics.orEmpty()
        val weekday = day.dayOfWeek()
        TodayUiState(
            loading = false,
            day = day,
            totalPoints = breakdown?.totalPoints ?: 0,
            state = breakdown?.state ?: DayState.GREEN,
            stateReason = breakdown?.let { b -> b.findings.firstOrNull { it.ruleId == b.decisiveStateRuleId }?.label },
            streakDays = history.streak.current.toInt(),
            weekStrip = history.heatmap.toHeatCells(),
            sleepMinutes = metrics[MetricKeys.SLEEP_MINUTES]?.toInt(),
            sleepConfidence = sleep.lastSleepConfidence,
            awakeSinceMs = sleep.awakeSinceMs,
            restingSinceMs = sleep.sleepingSinceMs,
            steps = metrics[MetricKeys.STEPS]?.toLong(),
            distanceMeters = metrics[MetricKeys.WALKING_METERS],
            contributions = breakdown?.contributions.orEmpty(),
            classes = timetable.filter { it.weekday == weekday }.map {
                ClassItem(it.id, it.title, it.startMinute, it.endMinute, it.location, attendance[it.id])
            },
            plan = plan,
            runningStudy = running,
            departure = departure,
            trip = trip,
            permissions = perms,
        )
    }

    /** Called when the screen resumes: permissions may have changed in system settings. */
    fun onResume() {
        today.value = time.today()
        permissions.value = permissionChecker.current()
        launchSafely { tracking.sync() }
    }

    fun usageAccessIntent(): Intent = permissionChecker.usageAccessSettingsIntent()

    fun setAttendance(slotId: Long, status: AttendanceStatus?) = launchAndReevaluate {
        academics.setAttendance(today.value, slotId, status)
    }

    fun setTopicCompleted(topicId: Long, completed: Boolean) = launchAndReevaluate {
        academics.setTopicCompleted(topicId, completed)
    }

    fun startStudy(topicId: Long) = launchAndReevaluate { academics.startStudy(topicId) }

    fun stopStudy() = launchAndReevaluate { academics.stopStudy() }

    fun recordDeparture() = launchAndReevaluate { travel.recordDeparture() }

    fun recordArrival() = launchSafely { travel.recordArrival() }

    private fun launchAndReevaluate(block: suspend () -> Unit) = launchSafely {
        block()
        days.evaluate(today.value)
    }

    private fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "Action failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "TodayViewModel"
    }
}

internal data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
