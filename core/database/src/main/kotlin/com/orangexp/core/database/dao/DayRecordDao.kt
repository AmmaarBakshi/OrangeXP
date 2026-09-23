package com.orangexp.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.orangexp.core.database.model.DayCategoryPointsEntity
import com.orangexp.core.database.model.DayContributionEntity
import com.orangexp.core.database.model.DayDetail
import com.orangexp.core.database.model.DayMetricEntity
import com.orangexp.core.database.model.DayRecordEntity
import com.orangexp.core.database.model.DayRecordWithCategories
import com.orangexp.core.database.model.DayStateFindingEntity
import kotlinx.coroutines.flow.Flow

/** Everything that describes one evaluated day, written atomically. */
data class DaySnapshot(
    val record: DayRecordEntity,
    val contributions: List<DayContributionEntity>,
    val metrics: List<DayMetricEntity>,
    val categories: List<DayCategoryPointsEntity>,
    val findings: List<DayStateFindingEntity>,
)

@Dao
interface DayRecordDao {
    @Transaction
    @Query("SELECT * FROM day_records ORDER BY epochDay")
    fun observeAllWithCategories(): Flow<List<DayRecordWithCategories>>

    @Transaction
    @Query("SELECT * FROM day_records WHERE epochDay = :day")
    fun observeDetail(day: Int): Flow<DayDetail?>

    @Query("SELECT * FROM day_records WHERE epochDay = :day")
    suspend fun get(day: Int): DayRecordEntity?

    @Query("SELECT * FROM day_records WHERE epochDay BETWEEN :from AND :to ORDER BY epochDay")
    fun observeRange(from: Int, to: Int): Flow<List<DayRecordEntity>>

    @Upsert
    suspend fun upsertRecord(record: DayRecordEntity)

    @Query("DELETE FROM day_contributions WHERE epochDay = :day")
    suspend fun deleteContributions(day: Int)

    @Query("DELETE FROM day_metrics WHERE epochDay = :day")
    suspend fun deleteMetrics(day: Int)

    @Query("DELETE FROM day_category_points WHERE epochDay = :day")
    suspend fun deleteCategories(day: Int)

    @Query("DELETE FROM day_state_findings WHERE epochDay = :day")
    suspend fun deleteFindings(day: Int)

    @Insert
    suspend fun insertContributions(items: List<DayContributionEntity>)

    @Insert
    suspend fun insertMetrics(items: List<DayMetricEntity>)

    @Insert
    suspend fun insertCategories(items: List<DayCategoryPointsEntity>)

    @Insert
    suspend fun insertFindings(items: List<DayStateFindingEntity>)

    @Transaction
    suspend fun replaceDay(snapshot: DaySnapshot) {
        val day = snapshot.record.epochDay
        upsertRecord(snapshot.record)
        deleteContributions(day)
        deleteMetrics(day)
        deleteCategories(day)
        deleteFindings(day)
        insertContributions(snapshot.contributions)
        insertMetrics(snapshot.metrics)
        insertCategories(snapshot.categories)
        insertFindings(snapshot.findings)
    }
}
