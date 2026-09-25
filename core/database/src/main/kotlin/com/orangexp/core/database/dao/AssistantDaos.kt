package com.orangexp.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.orangexp.core.database.model.AssistantMessageEntity
import com.orangexp.core.database.model.ReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE status = 'ACTIVE' ORDER BY COALESCE(nextFireMs, atMs), id")
    fun observeActive(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE status != 'ACTIVE' ORDER BY COALESCE(completedMs, atMs) DESC LIMIT :limit")
    fun observeFinished(limit: Int): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE status = 'ACTIVE' ORDER BY COALESCE(nextFireMs, atMs), id")
    suspend fun getActive(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: Long): ReminderEntity?

    @Upsert
    suspend fun upsert(reminder: ReminderEntity): Long

    @Query("UPDATE reminders SET nextFireMs = :nextFireMs WHERE id = :id")
    suspend fun setNextFire(id: Long, nextFireMs: Long?)

    @Query("UPDATE reminders SET status = :status, completedMs = :completedMs, nextFireMs = NULL WHERE id = :id")
    suspend fun finish(id: Long, status: String, completedMs: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM reminders WHERE status != 'ACTIVE'")
    suspend fun clearFinished()
}

@Dao
interface AssistantMessageDao {
    /** Newest last. */
    @Query("SELECT * FROM (SELECT * FROM assistant_messages ORDER BY timestampMs DESC, id DESC LIMIT :limit) ORDER BY timestampMs, id")
    fun observeRecent(limit: Int): Flow<List<AssistantMessageEntity>>

    /** Newest last. */
    @Query("SELECT * FROM (SELECT * FROM assistant_messages ORDER BY timestampMs DESC, id DESC LIMIT :limit) ORDER BY timestampMs, id")
    suspend fun recent(limit: Int): List<AssistantMessageEntity>

    @Insert
    suspend fun insert(message: AssistantMessageEntity): Long

    @Query("UPDATE assistant_messages SET text = :text WHERE id = :id")
    suspend fun updateText(id: Long, text: String)

    @Query("DELETE FROM assistant_messages")
    suspend fun clear()
}
