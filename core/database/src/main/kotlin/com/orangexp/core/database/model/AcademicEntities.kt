package com.orangexp.core.database.model

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "timetable_slots", indices = [Index("weekday")])
data class TimetableSlotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** ISO day of week: Monday = 1 ... Sunday = 7. */
    val weekday: Int,
    val startMinute: Int,
    val endMinute: Int,
    val location: String = "",
    val teacher: String = "",
    val notes: String = "",
    val requiresTravel: Boolean = true,
    val travelMinutes: Int? = null,
    val preparationMinutes: Int? = null,
)

@Entity(tableName = "subjects")
data class SubjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val examEpochDay: Int? = null,
    /** 1 (low) ..= 5 (high). */
    val priority: Int = 3,
)

@Entity(
    tableName = "units",
    indices = [Index("subjectId")],
    foreignKeys = [
        ForeignKey(
            entity = SubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["subjectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class UnitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectId: Long,
    val title: String,
    val position: Int,
)

@Entity(
    tableName = "topics",
    indices = [Index("unitId"), Index("subjectId"), Index("completedAtMs")],
    foreignKeys = [
        ForeignKey(
            entity = UnitEntity::class,
            parentColumns = ["id"],
            childColumns = ["unitId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TopicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val unitId: Long,
    val subjectId: Long,
    val title: String,
    val position: Int,
    val estimatedMinutes: Int,
    /** 1 (easy) ..= 5 (hard). */
    val difficulty: Int = 3,
    val completedAtMs: Long? = null,
)

/** A block of real study. `endMs == null` while the timer is running. */
@Entity(tableName = "study_sessions", indices = [Index("startMs"), Index("topicId")])
data class StudySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topicId: Long?,
    val subjectId: Long?,
    val startMs: Long,
    val endMs: Long?,
)

/** One day's slice of the generated study schedule. */
@Entity(tableName = "study_plan", primaryKeys = ["epochDay", "topicId"])
data class StudyPlanEntity(
    val epochDay: Int,
    val topicId: Long,
    val subjectId: Long,
    val minutes: Int,
    val partIndex: Int,
    val partCount: Int,
    val position: Int,
)

@Entity(tableName = "subject_forecasts")
data class SubjectForecastEntity(
    @PrimaryKey val subjectId: Long,
    val status: String,
    val remainingMinutes: Int,
    val scheduledMinutes: Int,
    val shortfallMinutes: Int,
    val targetDay: Int?,
    val projectedFinishDay: Int?,
    val generatedForDay: Int,
)

@Entity(tableName = "study_windows")
data class StudyWindowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weekday: Int,
    val startMinute: Int,
    val endMinute: Int,
)

@Entity(tableName = "attendance", primaryKeys = ["epochDay", "slotId"])
data class AttendanceEntity(
    val epochDay: Int,
    val slotId: Long,
    /** ATTENDED, MISSED or CANCELLED. */
    val status: String,
    val recordedAtMs: Long,
)

@Entity(tableName = "travel_records", indices = [Index("epochDay")])
data class TravelRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Int,
    val slotId: Long?,
    val plannedLeaveMs: Long?,
    val departedMs: Long,
    val arrivedMs: Long? = null,
)

data class UnitWithTopics(
    @Embedded val unit: UnitEntity,
    @Relation(parentColumn = "id", entityColumn = "unitId")
    val topics: List<TopicEntity>,
)

data class SubjectWithUnits(
    @Embedded val subject: SubjectEntity,
    @Relation(entity = UnitEntity::class, parentColumn = "id", entityColumn = "subjectId")
    val units: List<UnitWithTopics>,
)
