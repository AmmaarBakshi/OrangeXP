package com.orangexp.core.data.repository

import com.orangexp.core.common.time.DayWindow
import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.common.time.nowMs
import com.orangexp.core.database.dao.KeyValueDao
import com.orangexp.core.database.dao.MovementDao
import com.orangexp.core.database.model.ActivityTransitionEntity
import com.orangexp.core.database.model.KeyValueEntity
import com.orangexp.core.database.model.LocationFixEntity
import com.orangexp.core.database.model.StepSampleEntity
import com.orangexp.core.engine.OrangeEngine
import com.orangexp.core.engine.ffi.ActivityHint
import com.orangexp.core.engine.ffi.ActivityTransition
import com.orangexp.core.engine.ffi.LocationFix
import com.orangexp.core.engine.ffi.MovementConfig
import com.orangexp.core.engine.ffi.MovementInput
import com.orangexp.core.engine.ffi.MovementSummary
import com.orangexp.core.engine.ffi.StepSample
import com.orangexp.core.sensing.movement.MovementSink
import com.orangexp.core.sensing.movement.MovementTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Optional GPS-assisted walking detection. */
interface MovementRepository {
    val enabled: Flow<Boolean>

    fun isSupported(): Boolean

    /** Runtime permissions still needed before tracking can run. */
    fun missingPermissions(): List<String>

    suspend fun setEnabled(enabled: Boolean)

    /** Re-registers tracking if enabled (registrations don't survive reboots or updates). */
    suspend fun ensureTracking()

    /** Walking vs. vehicle for [window], or `null` when no GPS data covers it. */
    suspend fun summarize(window: DayWindow, config: MovementConfig): MovementSummary?
}

@Singleton
internal class OfflineMovementRepository @Inject constructor(
    private val dao: MovementDao,
    private val keyValues: KeyValueDao,
    private val tracker: MovementTracker,
    private val engine: OrangeEngine,
    private val time: TimeSource,
) : MovementRepository, MovementSink {

    override val enabled: Flow<Boolean> =
        keyValues.observe(StorageKeys.MOVEMENT_ENABLED).map { it.toBoolean() }

    override fun isSupported(): Boolean = tracker.isSupported()

    override fun missingPermissions(): List<String> = tracker.missingPermissions()

    override suspend fun setEnabled(enabled: Boolean) {
        keyValues.put(KeyValueEntity(StorageKeys.MOVEMENT_ENABLED, enabled.toString()))
        if (enabled) tracker.start() else tracker.stop()
    }

    override suspend fun ensureTracking() {
        val now = time.nowMs()
        dao.deleteFixesBefore(now - RETENTION_MS)
        dao.deleteStepSamplesBefore(now - RETENTION_MS)
        dao.deleteTransitionsBefore(now - RETENTION_MS)
        if (keyValues.get(StorageKeys.MOVEMENT_ENABLED).toBoolean()) tracker.start()
    }

    override suspend fun onTransitions(transitions: List<Pair<Long, ActivityHint>>) {
        dao.insertTransitions(transitions.map { (t, hint) -> ActivityTransitionEntity(t, hint.name) })
    }

    override suspend fun onLocations(fixes: List<LocationFix>, cumulativeSteps: Long?, atMs: Long) {
        dao.insertFixes(fixes.map { LocationFixEntity(it.timestampMs, it.latitude, it.longitude, it.accuracyM) })
        if (cumulativeSteps != null) dao.insertStepSample(StepSampleEntity(atMs, cumulativeSteps))
    }

    override suspend fun summarize(window: DayWindow, config: MovementConfig): MovementSummary? {
        val fixes = dao.fixesBetween(window.startMs, window.endMs)
        if (fixes.size < 2) return null
        // Context just outside the day lets segments that cross midnight classify correctly.
        val from = window.startMs - CONTEXT_MS
        val to = window.endMs + CONTEXT_MS
        val input = MovementInput(
            fixes = dao.fixesBetween(from, to).map { LocationFix(it.timestampMs, it.latitude, it.longitude, it.accuracyM) },
            steps = dao.stepSamplesBetween(from, to).map { StepSample(it.timestampMs, it.cumulativeSteps) },
            transitions = dao.transitionsBetween(from - CONTEXT_MS, to).map {
                ActivityTransition(it.timestampMs, enumValueOf<ActivityHint>(it.activity))
            },
            windowStartMs = window.startMs,
            windowEndMs = window.endMs,
        )
        return engine.analyzeMovement(input, config)
    }

    private companion object {
        const val RETENTION_MS = 14 * 24 * 3_600_000L
        const val CONTEXT_MS = 30 * 60_000L
    }
}
