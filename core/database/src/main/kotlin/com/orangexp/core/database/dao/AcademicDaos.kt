package com.orangexp.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.orangexp.core.database.model.AttendanceEntity
import com.orangexp.core.database.model.StudyPlanEntity
import com.orangexp.core.database.model.StudySessionEntity
import com.orangexp.core.database.model.StudyWindowEntity
import com.orangexp.core.database.model.SubjectEntity
import com.orangexp.core.database.model.SubjectForecastEntity
import com.orangexp.core.database.model.SubjectWithUnits
import com.orangexp.core.database.model.TimetableSlotEntity
import com.orangexp.core.database.model.TopicEntity
import com.orangexp.core.database.model.TravelRecordEntity
import com.orangexp.core.database.model.UnitEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TimetableDao {
    @Query("SELECT * FROM timetable_slots ORDER BY weekday, startMinute")
    fun observeAll(): Flow<List<TimetableSlotEntity>>

    @Query("SELECT * FROM timetable_slots ORDER BY weekday, startMinute")
    suspend fun getAll(): List<TimetableSlotEntity>

    @Upsert
    suspend fun upsert(slot: TimetableSlotEntity): Long

    @Query("DELETE FROM timetable_slots WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM study_windows ORDER BY weekday, startMinute")
    fun observeStudyWindows(): Flow<List<StudyWindowEntity>>

    @Query("SELECT * FROM study_windows ORDER BY weekday, startMinute")
    suspend fun getStudyWindows(): List<StudyWindowEntity>

    @Query("SELECT COUNT(*) FROM study_windows")
    suspend fun studyWindowCount(): Int

    @Insert
    suspend fun insertStudyWindows(windows: List<StudyWindowEntity>)

    @Delete
    suspend fun deleteStudyWindow(window: StudyWindowEntity)
}

@Dao
interface SyllabusDao {
    @Transaction
    @Query("SELECT * FROM subjects ORDER BY name")
    fun observeTree(): Flow<List<SubjectWithUnits>>

    @Query("SELECT * FROM subjects ORDER BY name")
    suspend fun getSubjects(): List<SubjectEntity>

    @Query("SELECT * FROM topics")
    suspend fun getTopics(): List<TopicEntity>

    @Query("SELECT * FROM units")
    suspend fun getUnits(): List<UnitEntity>

    @Query("SELECT * FROM topics")
    fun observeTopics(): Flow<List<TopicEntity>>

    @Query("SELECT * FROM topics WHERE id = :id")
    suspend fun getTopic(id: Long): TopicEntity?

    @Query("SELECT * FROM topics WHERE completedAtMs >= :fromMs AND completedAtMs < :toMs")
    suspend fun completedBetween(fromMs: Long, toMs: Long): List<TopicEntity>

    @Upsert
    suspend fun upsertSubject(subject: SubjectEntity): Long

    @Query("DELETE FROM subjects WHERE id = :id")
    suspend fun deleteSubject(id: Long)

    @Upsert
    suspend fun upsertUnit(unit: UnitEntity): Long

    @Query("DELETE FROM units WHERE id = :id")
    suspend fun deleteUnit(id: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM units WHERE subjectId = :subjectId")
    suspend fun nextUnitPosition(subjectId: Long): Int

    @Upsert
    suspend fun upsertTopic(topic: TopicEntity): Long

    @Update
    suspend fun updateTopic(topic: TopicEntity)

    @Query("DELETE FROM topics WHERE id = :id")
    suspend fun deleteTopic(id: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM topics WHERE unitId = :unitId")
    suspend fun nextTopicPosition(unitId: Long): Int
}

@Dao
interface StudyDao {
    @Query("SELECT * FROM study_sessions WHERE endMs IS NULL LIMIT 1")
    fun observeRunning(): Flow<StudySessionEntity?>

    @Query("SELECT * FROM study_sessions WHERE endMs IS NULL LIMIT 1")
    suspend fun getRunning(): StudySessionEntity?

    @Insert
    suspend fun insertSession(session: StudySessionEntity): Long

    @Update
    suspend fun updateSession(session: StudySessionEntity)

    /** Sessions overlapping `[fromMs, toMs)`, including a running one. */
    @Query("SELECT * FROM study_sessions WHERE startMs < :toMs AND (endMs IS NULL OR endMs > :fromMs) ORDER BY startMs")
    suspend fun overlapping(fromMs: Long, toMs: Long): List<StudySessionEntity>

    @Query("SELECT * FROM study_sessions WHERE startMs < :toMs AND (endMs IS NULL OR endMs > :fromMs) ORDER BY startMs")
    fun observeOverlapping(fromMs: Long, toMs: Long): Flow<List<StudySessionEntity>>

    @Query("SELECT topicId, SUM(endMs - startMs) AS totalMs FROM study_sessions WHERE topicId IS NOT NULL AND endMs IS NOT NULL GROUP BY topicId")
    suspend fun studiedPerTopic(): List<TopicStudyTotal>

    @Query("SELECT * FROM study_plan WHERE epochDay = :day ORDER BY position")
    suspend fun planFor(day: Int): List<StudyPlanEntity>

    @Query("SELECT * FROM study_plan WHERE epochDay BETWEEN :from AND :to ORDER BY epochDay, position")
    fun observePlan(from: Int, to: Int): Flow<List<StudyPlanEntity>>

    @Query("DELETE FROM study_plan WHERE epochDay >= :fromDay")
    suspend fun deletePlanFrom(fromDay: Int)

    @Insert
    suspend fun insertPlan(items: List<StudyPlanEntity>)

    @Query("DELETE FROM subject_forecasts")
    suspend fun clearForecasts()

    @Insert
    suspend fun insertForecasts(items: List<SubjectForecastEntity>)

    @Query("SELECT * FROM subject_forecasts")
    fun observeForecasts(): Flow<List<SubjectForecastEntity>>

    /** Keeps past plan days (for adherence history) and replaces the future. */
    @Transaction
    suspend fun replacePlan(fromDay: Int, plan: List<StudyPlanEntity>, forecasts: List<SubjectForecastEntity>) {
        deletePlanFrom(fromDay)
        insertPlan(plan)
        clearForecasts()
        insertForecasts(forecasts)
    }
}

data class TopicStudyTotal(val topicId: Long, val totalMs: Long)

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance WHERE epochDay = :day")
    suspend fun forDay(day: Int): List<AttendanceEntity>

    @Query("SELECT * FROM attendance WHERE epochDay = :day")
    fun observeDay(day: Int): Flow<List<AttendanceEntity>>

    @Upsert
    suspend fun upsert(entity: AttendanceEntity)

    @Query("DELETE FROM attendance WHERE epochDay = :day AND slotId = :slotId")
    suspend fun clear(day: Int, slotId: Long)
}

@Dao
interface TravelDao {
    @Query("SELECT * FROM travel_records WHERE epochDay = :day ORDER BY departedMs")
    suspend fun forDay(day: Int): List<TravelRecordEntity>

    @Query("SELECT * FROM travel_records WHERE epochDay = :day ORDER BY departedMs")
    fun observeDay(day: Int): Flow<List<TravelRecordEntity>>

    /** Durations of the most recent completed trips, newest first. */
    @Query("SELECT (arrivedMs - departedMs) / 60000 FROM travel_records WHERE arrivedMs IS NOT NULL ORDER BY departedMs DESC LIMIT :limit")
    suspend fun recentTripMinutes(limit: Int): List<Long>

    @Insert
    suspend fun insert(record: TravelRecordEntity): Long

    @Update
    suspend fun update(record: TravelRecordEntity)
}
