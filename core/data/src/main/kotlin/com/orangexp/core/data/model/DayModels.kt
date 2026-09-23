package com.orangexp.core.data.model

import com.orangexp.core.common.time.EpochDay
import com.orangexp.core.engine.ffi.Category
import com.orangexp.core.engine.ffi.DayState

/** A stored day with its full explanation. */
data class DayBreakdown(
    val day: EpochDay,
    val totalPoints: Long,
    val state: DayState,
    val decisiveStateRuleId: String?,
    val contributions: List<Contribution>,
    val metrics: Map<String, Double>,
    val findings: List<StateFindingModel>,
)

data class Contribution(
    val ruleId: String,
    val label: String,
    val category: Category,
    val metric: String,
    val value: Double,
    val rawPoints: Double,
    val points: Long,
    val limited: Boolean,
)

data class StateFindingModel(
    val ruleId: String,
    val label: String,
    val metric: String,
    val value: Double,
    val state: DayState,
)

/** Live sleep/wake status, refreshed on every tracking pass. */
data class SleepStatus(
    /** End of the last estimated sleep; `null` if unknown or currently asleep. */
    val awakeSinceMs: Long?,
    /** Start of an estimated sleep in progress. */
    val sleepingSinceMs: Long?,
    val lastSleepMinutes: Int?,
    val lastSleepConfidence: Int?,
)
