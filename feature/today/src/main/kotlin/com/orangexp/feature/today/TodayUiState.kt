package com.orangexp.feature.today

import com.orangexp.core.common.time.EpochDay
import com.orangexp.core.data.model.AttendanceStatus
import com.orangexp.core.data.model.Contribution
import com.orangexp.core.data.model.RunningStudy
import com.orangexp.core.data.model.StudyPlanItem
import com.orangexp.core.data.repository.DayDeparture
import com.orangexp.core.data.repository.TripStatus
import com.orangexp.core.designsystem.component.HeatCell
import com.orangexp.core.engine.ffi.DayState
import com.orangexp.core.sensing.TrackingPermissions

data class ClassItem(
    val slotId: Long,
    val title: String,
    val startMinute: Int,
    val endMinute: Int,
    val location: String,
    val attendance: AttendanceStatus?,
)

data class TodayUiState(
    val loading: Boolean = true,
    val day: EpochDay = 0,
    val totalPoints: Long = 0,
    val state: DayState = DayState.GREEN,
    /** Label of the rule that set a non-green state. */
    val stateReason: String? = null,
    val streakDays: Int = 0,
    val weekStrip: List<HeatCell> = emptyList(),
    val sleepMinutes: Int? = null,
    val sleepConfidence: Int? = null,
    val awakeSinceMs: Long? = null,
    val restingSinceMs: Long? = null,
    val steps: Long? = null,
    val distanceMeters: Double? = null,
    val contributions: List<Contribution> = emptyList(),
    val classes: List<ClassItem> = emptyList(),
    val plan: List<StudyPlanItem> = emptyList(),
    val runningStudy: RunningStudy? = null,
    val departure: DayDeparture? = null,
    val trip: TripStatus = TripStatus(null, null),
    val permissions: TrackingPermissions? = null,
) {
    val plannedTopicsDone: Int get() = plan.distinctBy { it.topicId }.count { it.topicCompleted }
    val plannedTopics: Int get() = plan.distinctBy { it.topicId }.size
}
