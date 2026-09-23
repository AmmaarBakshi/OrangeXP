package com.orangexp.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.orangexp.core.database.dao.AttendanceDao
import com.orangexp.core.database.dao.DayRecordDao
import com.orangexp.core.database.dao.DeviceEventDao
import com.orangexp.core.database.dao.KeyValueDao
import com.orangexp.core.database.dao.SleepDao
import com.orangexp.core.database.dao.StepsDao
import com.orangexp.core.database.dao.StudyDao
import com.orangexp.core.database.dao.SyllabusDao
import com.orangexp.core.database.dao.TimetableDao
import com.orangexp.core.database.dao.TravelDao
import com.orangexp.core.database.model.AttendanceEntity
import com.orangexp.core.database.model.DailyStepsEntity
import com.orangexp.core.database.model.DayCategoryPointsEntity
import com.orangexp.core.database.model.DayContributionEntity
import com.orangexp.core.database.model.DayMetricEntity
import com.orangexp.core.database.model.DayRecordEntity
import com.orangexp.core.database.model.DayStateFindingEntity
import com.orangexp.core.database.model.DeviceEventEntity
import com.orangexp.core.database.model.KeyValueEntity
import com.orangexp.core.database.model.SleepSessionEntity
import com.orangexp.core.database.model.StudyPlanEntity
import com.orangexp.core.database.model.StudySessionEntity
import com.orangexp.core.database.model.StudyWindowEntity
import com.orangexp.core.database.model.SubjectEntity
import com.orangexp.core.database.model.SubjectForecastEntity
import com.orangexp.core.database.model.TimetableSlotEntity
import com.orangexp.core.database.model.TopicEntity
import com.orangexp.core.database.model.TravelRecordEntity
import com.orangexp.core.database.model.UnitEntity

/**
 * The single local database. Schema changes must bump [version] and ship a
 * migration; exported schemas live in `core/database/schemas`.
 */
@Database(
    version = 1,
    exportSchema = true,
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
}
