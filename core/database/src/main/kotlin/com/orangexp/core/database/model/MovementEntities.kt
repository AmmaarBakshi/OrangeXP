package com.orangexp.core.database.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A GPS fix recorded while the platform reported movement. Pruned after a retention window. */
@Entity(tableName = "location_fixes")
data class LocationFixEntity(
    @PrimaryKey val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double,
)

/** A step-counter reading taken alongside a location fix, for cadence. */
@Entity(tableName = "step_samples")
data class StepSampleEntity(
    @PrimaryKey val timestampMs: Long,
    val cumulativeSteps: Long,
)

/** The platform detected that `activity` (engine `ActivityHint` name) started. */
@Entity(tableName = "activity_transitions", primaryKeys = ["timestampMs", "activity"])
data class ActivityTransitionEntity(
    val timestampMs: Long,
    val activity: String,
)
