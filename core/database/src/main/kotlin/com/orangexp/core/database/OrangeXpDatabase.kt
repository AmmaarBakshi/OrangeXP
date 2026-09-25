package com.orangexp.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import com.orangexp.core.database.dao.AssistantMessageDao
import com.orangexp.core.database.dao.AttendanceDao
import com.orangexp.core.database.dao.CompetitionDao
import com.orangexp.core.database.dao.DayRecordDao
import com.orangexp.core.database.dao.DeviceEventDao
import com.orangexp.core.database.dao.KeyValueDao
import com.orangexp.core.database.dao.MovementDao
import com.orangexp.core.database.dao.ReminderDao
import com.orangexp.core.database.dao.SleepDao
import com.orangexp.core.database.dao.StepsDao
import com.orangexp.core.database.dao.StudyDao
import com.orangexp.core.database.dao.SyllabusDao
import com.orangexp.core.database.dao.TimetableDao
import com.orangexp.core.database.dao.TravelDao
import com.orangexp.core.database.model.ActivityTransitionEntity
import com.orangexp.core.database.model.AssistantMessageEntity
import com.orangexp.core.database.model.AttendanceEntity
import com.orangexp.core.database.model.CompetitionEntity
import com.orangexp.core.database.model.CompetitionMemberEntity
import com.orangexp.core.database.model.DailyStepsEntity
import com.orangexp.core.database.model.DayCategoryPointsEntity
import com.orangexp.core.database.model.DayContributionEntity
import com.orangexp.core.database.model.DayMetricEntity
import com.orangexp.core.database.model.DayRecordEntity
import com.orangexp.core.database.model.DayStateFindingEntity
import com.orangexp.core.database.model.DeviceEventEntity
import com.orangexp.core.database.model.KeyValueEntity
import com.orangexp.core.database.model.LocationFixEntity
import com.orangexp.core.database.model.ReminderEntity
import com.orangexp.core.database.model.SleepSessionEntity
import com.orangexp.core.database.model.StepSampleEntity
import com.orangexp.core.database.model.StudyPlanEntity
import com.orangexp.core.database.model.StudySessionEntity
import com.orangexp.core.database.model.StudyWindowEntity
import com.orangexp.core.database.model.SubjectEntity
import com.orangexp.core.database.model.SubjectForecastEntity
import com.orangexp.core.database.model.TeamMemberEntity
import com.orangexp.core.database.model.TimetableSlotEntity
import com.orangexp.core.database.model.TopicEntity
import com.orangexp.core.database.model.TravelRecordEntity
import com.orangexp.core.database.model.UnitEntity

/**
 * The single local database. Schema changes must bump [version] and ship a
 * migration; exported schemas live in `core/database/schemas`.
 *
 * - v2: movement tracking (fixes, step samples, activity transitions) and competitions.
 * - v3: Holstrom reminders and conversation.
 */
@Database(
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
    entities = [
        DeviceEventEntity::class,
        DailyStepsEntity::class,
        KeyValueEntity::class,
        SleepSessionEntity::class,
        DayRecordEntity::class,
        DayContributionEntity::class,
        DayMetricEntity::class,
        DayCategoryPointsEntity::class,
        DayStateFindingEntity::class,
        TimetableSlotEntity::class,
        SubjectEntity::class,
        UnitEntity::class,
        TopicEntity::class,
        StudySessionEntity::class,
        StudyPlanEntity::class,
        SubjectForecastEntity::class,
        StudyWindowEntity::class,
        AttendanceEntity::class,
        TravelRecordEntity::class,
        LocationFixEntity::class,
        StepSampleEntity::class,
        ActivityTransitionEntity::class,
        CompetitionEntity::class,
        TeamMemberEntity::class,
        CompetitionMemberEntity::class,
        ReminderEntity::class,
        AssistantMessageEntity::class,
    ],
)
abstract class OrangeXpDatabase : RoomDatabase() {
    abstract fun deviceEventDao(): DeviceEventDao
    abstract fun keyValueDao(): KeyValueDao
    abstract fun stepsDao(): StepsDao
    abstract fun sleepDao(): SleepDao
    abstract fun dayRecordDao(): DayRecordDao
    abstract fun timetableDao(): TimetableDao
    abstract fun syllabusDao(): SyllabusDao
    abstract fun studyDao(): StudyDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun travelDao(): TravelDao
    abstract fun movementDao(): MovementDao
    abstract fun competitionDao(): CompetitionDao
    abstract fun reminderDao(): ReminderDao
    abstract fun assistantMessageDao(): AssistantMessageDao
}
