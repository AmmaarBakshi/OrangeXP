package com.orangexp.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.orangexp.core.database.model.ActivityTransitionEntity
import com.orangexp.core.database.model.CompetitionEntity
import com.orangexp.core.database.model.CompetitionMemberEntity
import com.orangexp.core.database.model.CompetitionWithMembers
import com.orangexp.core.database.model.LocationFixEntity
import com.orangexp.core.database.model.StepSampleEntity
import com.orangexp.core.database.model.TeamMemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MovementDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFixes(fixes: List<LocationFixEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStepSample(sample: StepSampleEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransitions(transitions: List<ActivityTransitionEntity>)

    @Query("SELECT * FROM location_fixes WHERE timestampMs >= :fromMs AND timestampMs < :toMs ORDER BY timestampMs")
    suspend fun fixesBetween(fromMs: Long, toMs: Long): List<LocationFixEntity>

    @Query("SELECT * FROM step_samples WHERE timestampMs >= :fromMs AND timestampMs < :toMs ORDER BY timestampMs")
    suspend fun stepSamplesBetween(fromMs: Long, toMs: Long): List<StepSampleEntity>

    @Query("SELECT * FROM activity_transitions WHERE timestampMs >= :fromMs AND timestampMs < :toMs ORDER BY timestampMs")
    suspend fun transitionsBetween(fromMs: Long, toMs: Long): List<ActivityTransitionEntity>

    @Query("SELECT * FROM activity_transitions ORDER BY timestampMs DESC LIMIT 1")
    suspend fun latestTransition(): ActivityTransitionEntity?

    @Query("DELETE FROM location_fixes WHERE timestampMs < :beforeMs")
    suspend fun deleteFixesBefore(beforeMs: Long)

    @Query("DELETE FROM step_samples WHERE timestampMs < :beforeMs")
    suspend fun deleteStepSamplesBefore(beforeMs: Long)

    @Query("DELETE FROM activity_transitions WHERE timestampMs < :beforeMs")
    suspend fun deleteTransitionsBefore(beforeMs: Long)
}

@Dao
interface CompetitionDao {
    @Transaction
    @Query("SELECT * FROM competitions ORDER BY eventStartDay, name")
    fun observeAll(): Flow<List<CompetitionWithMembers>>

    @Transaction
    @Query("SELECT * FROM competitions ORDER BY eventStartDay, name")
    suspend fun getAll(): List<CompetitionWithMembers>

    @Query("SELECT * FROM competitions WHERE id = :id")
    suspend fun get(id: Long): CompetitionEntity?

    @Upsert
    suspend fun upsert(competition: CompetitionEntity): Long

    @Query("DELETE FROM competitions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM competition_members WHERE competitionId = :competitionId")
    suspend fun clearMembers(competitionId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembers(links: List<CompetitionMemberEntity>)

    /** Saves a competition and replaces its team in one transaction; returns its id. */
    @Transaction
    suspend fun save(competition: CompetitionEntity, memberIds: List<Long>): Long {
        val inserted = upsert(competition)
        val id = if (competition.id == 0L) inserted else competition.id
        clearMembers(id)
        insertMembers(memberIds.distinct().map { CompetitionMemberEntity(id, it) })
        return id
    }

    @Query("SELECT * FROM team_members ORDER BY name")
    fun observeMembers(): Flow<List<TeamMemberEntity>>

    @Upsert
    suspend fun upsertMember(member: TeamMemberEntity): Long

    @Query("DELETE FROM team_members WHERE id = :id")
    suspend fun deleteMember(id: Long)
}
