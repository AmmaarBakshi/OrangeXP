package com.orangexp.core.data.model

import com.orangexp.core.common.time.EpochDay
import com.orangexp.core.engine.ffi.SubjectStatus
import java.time.DayOfWeek

data class TimetableEntry(
    val id: Long = 0,
    val title: String,
    val weekday: DayOfWeek,
    val startMinute: Int,
    val endMinute: Int,
    val location: String = "",
    val teacher: String = "",
    val notes: String = "",
    val requiresTravel: Boolean = true,
    val travelMinutes: Int? = null,
    val preparationMinutes: Int? = null,
)

data class Subject(
    val id: Long = 0,
    val name: String,
    val examDay: EpochDay? = null,
    /** 1 (low) ..= 5 (high). */
    val priority: Int = 3,
    val units: List<SyllabusUnit> = emptyList(),
) {
    val topics: List<Topic> get() = units.flatMap { it.topics }
    val completedTopics: Int get() = topics.count { it.isCompleted }
}

data class SyllabusUnit(
    val id: Long = 0,
    val subjectId: Long,
    val title: String,
    val position: Int,
    val topics: List<Topic> = emptyList(),
)

data class Topic(
    val id: Long = 0,
    val unitId: Long,
    val subjectId: Long,
    val title: String,
    val position: Int,
    val estimatedMinutes: Int,
    /** 1 (easy) ..= 5 (hard). */
    val difficulty: Int = 3,
    val completedAtMs: Long? = null,
) {
    val isCompleted: Boolean get() = completedAtMs != null
}

data class StudyWindow(
    val id: Long = 0,
    val weekday: DayOfWeek,
    val startMinute: Int,
    val endMinute: Int,
)

data class StudyPlanItem(
    val day: EpochDay,
    val topicId: Long,
    val subjectId: Long,
    val topicTitle: String,
    val subjectName: String,
    val minutes: Int,
    val partIndex: Int,
    val partCount: Int,
    val topicCompleted: Boolean,
)

data class SubjectForecast(
    val subjectId: Long,
    val subjectName: String,
    val status: SubjectStatus,
    val remainingMinutes: Int,
    val scheduledMinutes: Int,
    val shortfallMinutes: Int,
    val targetDay: EpochDay?,
    val projectedFinishDay: EpochDay?,
)

enum class AttendanceStatus { ATTENDED, MISSED, CANCELLED }

data class RunningStudy(
    val sessionId: Long,
    val topicId: Long?,
    val startMs: Long,
)
