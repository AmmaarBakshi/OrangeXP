package com.orangexp.core.data.tracking

import com.orangexp.core.common.time.EpochDay
import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.common.time.nowMs
import com.orangexp.core.common.time.today
import com.orangexp.core.data.repository.AcademicRepository
import com.orangexp.core.data.repository.DayRepository
import com.orangexp.core.data.repository.StorageKeys
import com.orangexp.core.database.dao.DeviceEventDao
import com.orangexp.core.database.dao.KeyValueDao
import com.orangexp.core.database.dao.StepsDao
import com.orangexp.core.database.model.DailyStepsEntity
import com.orangexp.core.database.model.DeviceEventEntity
import com.orangexp.core.database.model.KeyValueEntity
import com.orangexp.core.engine.ffi.DayState
import com.orangexp.core.engine.ffi.DeviceEventKind
import com.orangexp.core.sensing.DeviceUsageSource
import com.orangexp.core.sensing.PowerStateSource
import com.orangexp.core.sensing.StepCounterSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class SyncResult(val day: EpochDay, val totalPoints: Long, val state: DayState)

/**
 * One tracking pass: pull new device signals, keep the study plan current and
 * re-evaluate today. Idempotent and cheap, so it can run on a schedule and
 * whenever the app opens.
 */
interface TrackingCoordinator {
    suspend fun sync(): SyncResult
}

@Singleton
internal class DefaultTrackingCoordinator @Inject constructor(
    private val deviceUsage: DeviceUsageSource,
    private val stepCounter: StepCounterSource,
    private val power: PowerStateSource,
    private val deviceEventDao: DeviceEventDao,
    private val stepsDao: StepsDao,
    private val keyValues: KeyValueDao,
    private val dayRepository: DayRepository,
    private val academicRepository: AcademicRepository,
    private val time: TimeSource,
) : TrackingCoordinator {

    private val mutex = Mutex()

    override suspend fun sync(): SyncResult = mutex.withLock {
        val now = time.nowMs()
        val today = time.today()

        ingestDeviceEvents(now)
        ingestSteps(now, today)

        if (keyValues.get(StorageKeys.PLAN_GENERATED_FOR_DAY)?.toIntOrNull() != today) {
            academicRepository.regeneratePlan()
        }
        // Evaluate yesterday one last time after midnight so late signals are included.
        val lastFinalized = keyValues.get(StorageKeys.LAST_FINALIZED_DAY)?.toIntOrNull()
        if (lastFinalized == null || lastFinalized < today - 1) {
            dayRepository.evaluate(today - 1)
            keyValues.put(KeyValueEntity(StorageKeys.LAST_FINALIZED_DAY, (today - 1).toString()))
        }
        val evaluation = dayRepository.evaluate(today)

        deviceEventDao.deleteBefore(now - EVENT_RETENTION_MS)
        SyncResult(today, evaluation.totalPoints, evaluation.state.state)
    }

    private suspend fun ingestDeviceEvents(now: Long) {
        if (deviceUsage.hasAccess()) {
            val cursor = keyValues.get(StorageKeys.USAGE_CURSOR_MS)?.toLongOrNull() ?: (now - INITIAL_LOOKBACK_MS)
            val from = maxOf(cursor - CURSOR_OVERLAP_MS, now - MAX_LOOKBACK_MS)
            val events = deviceUsage.events(from, now)
            deviceEventDao.insertAll(events.map { DeviceEventEntity(it.timestampMs, it.kind.name) })
            keyValues.put(KeyValueEntity(StorageKeys.USAGE_CURSOR_MS, now.toString()))
        }

        // Power changes are sampled rather than listened for: no receiver stays registered.
        val charging = power.isCharging()
        val previous = keyValues.get(StorageKeys.LAST_CHARGING)?.toBooleanStrictOrNull()
        if (previous != charging) {
            val kind = if (charging) DeviceEventKind.POWER_CONNECTED else DeviceEventKind.POWER_DISCONNECTED
            deviceEventDao.insertAll(listOf(DeviceEventEntity(now, kind.name)))
            keyValues.put(KeyValueEntity(StorageKeys.LAST_CHARGING, charging.toString()))
        }
    }

    private suspend fun ingestSteps(now: Long, today: EpochDay) {
        val counter = stepCounter.readCumulativeSteps() ?: return
        val baseline = StepAccumulator.Baseline.decode(keyValues.get(StorageKeys.STEP_BASELINE))
            ?.takeIf { now - it.timestampMs <= MAX_STEP_GAP_MS }
        val delta = StepAccumulator.delta(baseline, counter)
        if (delta > 0) {
            val existing = stepsDao.get(today)?.steps ?: 0
            stepsDao.upsert(DailyStepsEntity(today, existing + delta))
        }
        keyValues.put(KeyValueEntity(StorageKeys.STEP_BASELINE, StepAccumulator.Baseline(counter, now).encode()))
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val INITIAL_LOOKBACK_MS = 72 * HOUR_MS
        const val MAX_LOOKBACK_MS = 7 * 24 * HOUR_MS
        const val CURSOR_OVERLAP_MS = 10 * 60_000L
        const val EVENT_RETENTION_MS = 14 * 24 * HOUR_MS

        /** Older baselines can't be attributed to a day reliably; start over instead. */
        const val MAX_STEP_GAP_MS = 24 * HOUR_MS
    }
}
