package com.orangexp.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.orangexp.core.database.model.DailyStepsEntity
import com.orangexp.core.database.model.DeviceEventEntity
import com.orangexp.core.database.model.KeyValueEntity
import com.orangexp.core.database.model.SleepSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceEventDao {
    /** Re-ingesting overlapping windows is harmless: duplicates are ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<DeviceEventEntity>)

    @Query("SELECT * FROM device_events WHERE timestampMs >= :fromMs AND timestampMs < :toMs ORDER BY timestampMs")
    suspend fun between(fromMs: Long, toMs: Long): List<DeviceEventEntity>

    @Query("DELETE FROM device_events WHERE timestampMs < :beforeMs")
    suspend fun deleteBefore(beforeMs: Long)
}

@Dao
interface KeyValueDao {
    @Query("SELECT value FROM key_values WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM key_values WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Upsert
    suspend fun put(entry: KeyValueEntity)
}

@Dao
interface StepsDao {
    @Query("SELECT * FROM daily_steps WHERE epochDay = :day")
    suspend fun get(day: Int): DailyStepsEntity?

    @Query("SELECT * FROM daily_steps WHERE epochDay = :day")
    fun observe(day: Int): Flow<DailyStepsEntity?>

    @Upsert
    suspend fun upsert(entity: DailyStepsEntity)
}

@Dao
interface SleepDao {
    @Query("SELECT * FROM sleep_sessions WHERE endMs >= :fromMs AND endMs < :toMs ORDER BY endMs")
    suspend fun endingBetween(fromMs: Long, toMs: Long): List<SleepSessionEntity>

    @Query("SELECT * FROM sleep_sessions WHERE endMs >= :fromMs AND endMs < :toMs ORDER BY endMs")
    fun observeEndingBetween(fromMs: Long, toMs: Long): Flow<List<SleepSessionEntity>>

    @Query("DELETE FROM sleep_sessions WHERE isManual = 0 AND endMs >= :fromMs")
    suspend fun deleteDetectedEndingAfter(fromMs: Long)

    @Insert
    suspend fun insertAll(sessions: List<SleepSessionEntity>)

    /** Replaces automatically detected sessions from `fromMs` on; manual sessions are kept. */
    @Transaction
    suspend fun replaceDetected(fromMs: Long, sessions: List<SleepSessionEntity>) {
        deleteDetectedEndingAfter(fromMs)
        insertAll(sessions.filter { it.endMs >= fromMs })
    }
}
