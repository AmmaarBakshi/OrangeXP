package com.orangexp.core.database.model

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * The persisted result of evaluating one day. Everything needed to explain the
 * score is stored alongside it, so history never has to be recomputed to be shown.
 */
@Entity(tableName = "day_records")
data class DayRecordEntity(
    @PrimaryKey val epochDay: Int,
    val totalPoints: Long,
    /** `DayState` name. */
    val state: String,
    val decisiveStateRuleId: String?,
    val computedAtMs: Long,
    val engineVersion: String,
)

@Entity(
    tableName = "day_contributions",
    primaryKeys = ["epochDay", "ruleId"],
    foreignKeys = [
        ForeignKey(
            entity = DayRecordEntity::class,
            parentColumns = ["epochDay"],
            childColumns = ["epochDay"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DayContributionEntity(
    val epochDay: Int,
    val ruleId: String,
    val position: Int,
    val label: String,
    val category: String,
    val metric: String,
    val value: Double,
    val rawPoints: Double,
    val points: Long,
    val limited: Boolean,
)

@Entity(
    tableName = "day_metrics",
    primaryKeys = ["epochDay", "metric"],
    foreignKeys = [
        ForeignKey(
            entity = DayRecordEntity::class,
            parentColumns = ["epochDay"],
            childColumns = ["epochDay"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DayMetricEntity(
    val epochDay: Int,
    val metric: String,
    val value: Double,
)

@Entity(
    tableName = "day_category_points",
    primaryKeys = ["epochDay", "category"],
    foreignKeys = [
        ForeignKey(
            entity = DayRecordEntity::class,
            parentColumns = ["epochDay"],
            childColumns = ["epochDay"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DayCategoryPointsEntity(
    val epochDay: Int,
    val category: String,
    val points: Long,
)

@Entity(
    tableName = "day_state_findings",
    primaryKeys = ["epochDay", "ruleId"],
    foreignKeys = [
        ForeignKey(
            entity = DayRecordEntity::class,
            parentColumns = ["epochDay"],
            childColumns = ["epochDay"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DayStateFindingEntity(
    val epochDay: Int,
    val ruleId: String,
    val label: String,
    val metric: String,
    val value: Double,
    val state: String,
)

data class DayRecordWithCategories(
    @Embedded val record: DayRecordEntity,
    @Relation(parentColumn = "epochDay", entityColumn = "epochDay")
    val categories: List<DayCategoryPointsEntity>,
)

data class DayDetail(
    @Embedded val record: DayRecordEntity,
    @Relation(parentColumn = "epochDay", entityColumn = "epochDay")
    val contributions: List<DayContributionEntity>,
    @Relation(parentColumn = "epochDay", entityColumn = "epochDay")
    val metrics: List<DayMetricEntity>,
    @Relation(parentColumn = "epochDay", entityColumn = "epochDay")
    val findings: List<DayStateFindingEntity>,
)
