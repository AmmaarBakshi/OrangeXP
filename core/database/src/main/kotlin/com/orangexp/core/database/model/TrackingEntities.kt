package com.orangexp.core.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A raw device event (screen on/off, unlock, app interaction, power).
 * `kind` is the engine's `DeviceEventKind` name. Pruned after a retention window.
 */
@Entity(tableName = "device_events", primaryKeys = ["timestampMs", "kind"])
data class DeviceEventEntity(
    val timestampMs: Long,
    val kind: String,
)

@Entity(tableName = "daily_steps")
data class DailyStepsEntity(
    @PrimaryKey val epochDay: Int,
    val steps: Long,
)

/** Small persistent values: ingestion cursors, sensor baselines, the engine configuration. */
@Entity(tableName = "key_values")
data class KeyValueEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "sleep_sessions", indices = [Index("endMs")])
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lastActivityMs: Long,
    val startMs: Long,
    val endMs: Long,
    val durationMinutes: Int,
    val interruptionCount: Int,
    val interruptionMinutes: Int,
    val confidence: Int,
    val confidenceLevel: String,
    val method: String,
    val isManual: Boolean,
)
