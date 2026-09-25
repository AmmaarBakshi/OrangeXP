package com.orangexp.core.data.repository

import com.orangexp.core.common.coroutines.Dispatcher
import com.orangexp.core.common.coroutines.OxDispatchers
import com.orangexp.core.common.time.DayWindow
import com.orangexp.core.common.time.EpochDay
import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.common.time.dayOfWeek
import com.orangexp.core.common.time.dayWindow
import com.orangexp.core.common.time.nowMs
import com.orangexp.core.common.time.today
import com.orangexp.core.common.time.utcOffsetMinutes
import com.orangexp.core.data.model.AttendanceStatus
import com.orangexp.core.data.model.DayBreakdown
import com.orangexp.core.data.model.SleepStatus
import com.orangexp.core.data.model.toModel
import com.orangexp.core.database.dao.AttendanceDao
import com.orangexp.core.database.dao.DayRecordDao
import com.orangexp.core.database.dao.DaySnapshot
import com.orangexp.core.database.dao.DeviceEventDao
import com.orangexp.core.database.dao.KeyValueDao
import com.orangexp.core.database.dao.ReminderDao
import com.orangexp.core.database.dao.SleepDao
import com.orangexp.core.database.dao.StepsDao
import com.orangexp.core.database.dao.StudyDao
import com.orangexp.core.database.dao.SyllabusDao
import com.orangexp.core.database.dao.TimetableDao
import com.orangexp.core.database.dao.TravelDao
import com.orangexp.core.database.model.DayCategoryPointsEntity
import com.orangexp.core.database.model.DayContributionEntity
import com.orangexp.core.database.model.DayMetricEntity
import com.orangexp.core.database.model.DayRecordEntity
import com.orangexp.core.database.model.DayStateFindingEntity
import com.orangexp.core.database.model.KeyValueEntity
import com.orangexp.core.database.model.SleepSessionEntity
import com.orangexp.core.engine.MetricKeys
import com.orangexp.core.engine.OrangeEngine
import com.orangexp.core.engine.ffi.CategoryPoints
import com.orangexp.core.engine.ffi.DayEvaluation
import com.orangexp.core.engine.ffi.DayInput
import com.orangexp.core.engine.ffi.DayRecord
import com.orangexp.core.engine.ffi.DeviceEvent
import com.orangexp.core.engine.ffi.DeviceEventKind
import com.orangexp.core.engine.ffi.EngineConfig
import com.orangexp.core.engine.ffi.HistoryEvaluation
import com.orangexp.core.engine.ffi.HistoryInput
import com.orangexp.core.engine.ffi.Measurement
import com.orangexp.core.engine.ffi.SleepDayReport
import com.orangexp.core.engine.ffi.WorkBlock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Daily scores: computing them from measurements and reading them back with explanations. */
interface DayRepository {
    fun breakdown(day: EpochDay): Flow<DayBreakdown?>

    /** Streak, heatmap and statistics over all stored days, for the heatmap range ending today. */
    fun history(heatmapDays: Int = 371): Flow<HistoryEvaluation>

    val sleepStatus: Flow<SleepStatus>

    /** Collects every measurement of [day], evaluates it with the engine and stores the result. */
    suspend fun evaluate(day: EpochDay): DayEvaluation
}

@Singleton
internal class OfflineDayRepository @Inject constructor(
    private val dayRecordDao: DayRecordDao,
    private val deviceEventDao: DeviceEventDao,
    private val sleepDao: SleepDao,
    private val stepsDao: StepsDao,
    private val studyDao: StudyDao,
    private val syllabusDao: SyllabusDao,
    private val timetableDao: TimetableDao,
    private val attendanceDao: AttendanceDao,
    private val travelDao: TravelDao,
    private val reminderDao: ReminderDao,
    private val keyValues: KeyValueDao,
    private val configRepository: ConfigRepository,
    private val engine: OrangeEngine,
    private val movement: MovementRepository,
    private val time: TimeSource,
    private val changes: DataChangeNotifier,
    @Dispatcher(OxDispatchers.Default) private val dispatcher: CoroutineDispatcher,
) : DayRepository {

    override fun breakdown(day: EpochDay): Flow<DayBreakdown?> =
        dayRecordDao.observeDetail(day).map { it?.toModel() }

    override fun history(heatmapDays: Int): Flow<HistoryEvaluation> =
        combine(dayRecordDao.observeAllWithCategories(), configRepository.config) { rows, config ->
            val today = time.today()
            val records = rows.map { row ->
                DayRecord(
                    epochDay = row.record.epochDay,
                    totalPoints = row.record.totalPoints,
                    state = enumValueOf(row.record.state),
                    categoryPoints = row.categories.map { CategoryPoints(enumValueOf(it.category), it.points) },
                )
            }
            engine.evaluateHistory(
                HistoryInput(records, today, heatmapStartDay = today - heatmapDays + 1, heatmapEndDay = today),
                config,
            )
        }.flowOn(dispatcher)

    override val sleepStatus: Flow<SleepStatus> = combine(
        keyValues.observe(StorageKeys.SLEEP_AWAKE_SINCE_MS),
        keyValues.observe(StorageKeys.SLEEP_ONGOING_SINCE_MS),
        keyValues.observe(StorageKeys.SLEEP_LAST_MINUTES),
        keyValues.observe(StorageKeys.SLEEP_LAST_CONFIDENCE),
    ) { awake, ongoing, minutes, confidence ->
        SleepStatus(
            awakeSinceMs = awake?.toLongOrNull(),
            sleepingSinceMs = ongoing?.toLongOrNull(),
            lastSleepMinutes = minutes?.toIntOrNull(),
            lastSleepConfidence = confidence?.toIntOrNull(),
        )
    }

    override suspend fun evaluate(day: EpochDay): DayEvaluation = withContext(dispatcher) {
        val zone = time.zone()
        val now = time.now()
        val nowMs = now.toEpochMilli()
        val window = dayWindow(day, zone)
        val config = configRepository.current()

        val measurements = buildList {
            addAll(sleepAndPhoneMeasurements(window, nowMs, utcOffsetMinutes(now, zone), config, isToday = day == time.today()))
            addAll(walkingMeasurements(window, stepsDao.get(day)?.steps, config))
            addAll(studyMeasurements(window, nowMs))
            addAll(attendanceMeasurements(day))
            addAll(travelMeasurements(day))
            addAll(taskMeasurements(window))
        }
        val evaluation = engine.evaluateDay(DayInput(day, measurements), config)
        dayRecordDao.replaceDay(evaluation.toSnapshot(nowMs, engine.version))
        changes.notifyChanged()
        evaluation
    }

    private suspend fun sleepAndPhoneMeasurements(
        window: DayWindow,
        nowMs: Long,
        utcOffsetMinutes: Int,
        config: EngineConfig,
        isToday: Boolean,
    ): List<Measurement> {
        val from = window.startMs - SLEEP_LOOKBACK_MS
        val to = minOf(window.endMs, nowMs) + 1
        val events = deviceEventDao.between(from, to).map { DeviceEvent(it.timestampMs, enumValueOf<DeviceEventKind>(it.kind)) }
        if (events.isEmpty()) return emptyList()

        val report = engine.analyzeSleepDay(events, window.startMs, window.endMs, nowMs, utcOffsetMinutes, config.sleep)
        persistSleep(report, replaceFromMs = window.startMs - SLEEP_REPLACE_MARGIN_MS)
        if (isToday) publishSleepStatus(report)

        return buildList {
            val summary = report.summary
            if (summary.sessionCount > 0u) add(Measurement(MetricKeys.SLEEP_MINUTES, summary.sleepMinutes.toDouble()))
            summary.longestAwakeMinutes?.let { add(Measurement(MetricKeys.AWAKE_MINUTES, it.toDouble())) }
            add(Measurement(MetricKeys.SCREEN_MINUTES, report.usage.screenMinutes.toDouble()))
            add(Measurement(MetricKeys.UNLOCKS, report.usage.unlocks.toDouble()))
        }
    }

    /**
     * Steps always come from the step counter. When GPS movement data exists,
     * walking distance is measured instead of estimated from stride length, and
     * steps the counter registered while riding a vehicle are removed.
     */
    private suspend fun walkingMeasurements(window: DayWindow, steps: Long?, config: EngineConfig): List<Measurement> {
        val summary = movement.summarize(window, config.movement)
        return buildList {
            val walkedSteps = steps?.let { (it - (summary?.stepsInVehicle ?: 0)).coerceAtLeast(0) }
            walkedSteps?.let { add(Measurement(MetricKeys.STEPS, it.toDouble())) }
            if (summary != null) {
                add(Measurement(MetricKeys.WALKING_METERS, summary.walkingMeters))
                add(Measurement(MetricKeys.WALKING_MINUTES, summary.walkingMinutes.toDouble()))
                add(Measurement(MetricKeys.VEHICLE_MINUTES, summary.vehicleMinutes.toDouble()))
                add(Measurement(MetricKeys.VEHICLE_METERS, summary.vehicleMeters))
            }
        }
    }

    private suspend fun persistSleep(report: SleepDayReport, replaceFromMs: Long) {
        sleepDao.replaceDetected(
            replaceFromMs,
            report.analysis.sessions.map {
                SleepSessionEntity(
                    lastActivityMs = it.lastActivityMs,
                    startMs = it.startMs,
                    endMs = it.endMs,
                    durationMinutes = it.durationMinutes.toInt(),
                    interruptionCount = it.interruptionCount.toInt(),
                    interruptionMinutes = it.interruptionMinutes.toInt(),
                    confidence = it.confidence.toInt(),
                    confidenceLevel = it.confidenceLevel.name,
                    method = it.method.name,
                    isManual = false,
                )
            },
        )
    }

    private suspend fun publishSleepStatus(report: SleepDayReport) {
        val analysis = report.analysis
        val last = analysis.sessions.lastOrNull()
        keyValues.put(KeyValueEntity(StorageKeys.SLEEP_AWAKE_SINCE_MS, analysis.awakeSinceMs?.toString().orEmpty()))
        keyValues.put(KeyValueEntity(StorageKeys.SLEEP_ONGOING_SINCE_MS, analysis.ongoing?.startMs?.toString().orEmpty()))
        keyValues.put(KeyValueEntity(StorageKeys.SLEEP_LAST_MINUTES, last?.durationMinutes?.toString().orEmpty()))
        keyValues.put(KeyValueEntity(StorageKeys.SLEEP_LAST_CONFIDENCE, last?.confidence?.toString().orEmpty()))
    }

    private suspend fun studyMeasurements(window: DayWindow, nowMs: Long): List<Measurement> {
        val end = minOf(window.endMs, nowMs)
        val sessions = studyDao.overlapping(window.startMs, window.endMs)
        val minutesByTopic = HashMap<Long?, Double>()
        for (s in sessions) {
            val clipped = (minOf(s.endMs ?: nowMs, end) - maxOf(s.startMs, window.startMs)).coerceAtLeast(0)
            minutesByTopic.merge(s.topicId, clipped / 60_000.0, Double::plus)
        }
        val completed = syllabusDao.completedBetween(window.startMs, window.endMs)
        val plan = studyDao.planFor(window.day)

        return buildList {
            if (sessions.isNotEmpty()) add(Measurement(MetricKeys.STUDY_MINUTES, minutesByTopic.values.sum()))
            if (completed.isNotEmpty()) {
                add(Measurement(MetricKeys.TOPICS_COMPLETED, completed.size.toDouble()))
                add(Measurement(MetricKeys.SYLLABUS_COMPLETED_MINUTES, completed.sumOf { it.estimatedMinutes }.toDouble()))
            }
            if (plan.isNotEmpty()) {
                val adherence = engine.adherence(
                    planned = plan.map { WorkBlock(it.topicId.toString(), it.minutes.toUInt()) },
                    actual = minutesByTopic.mapNotNull { (topic, minutes) ->
                        topic?.let { WorkBlock(it.toString(), minutes.toInt().toUInt()) }
                    },
                )
                adherence.percent?.let { add(Measurement(MetricKeys.SCHEDULE_ADHERENCE_PERCENT, it)) }
            }
        }
    }

    private suspend fun attendanceMeasurements(day: EpochDay): List<Measurement> {
        val weekday = day.dayOfWeek().value
        val slots = timetableDao.getAll().filter { it.weekday == weekday }
        if (slots.isEmpty()) return emptyList()
        val statuses = attendanceDao.forDay(day).associate { it.slotId to it.status }
        val scheduled = slots.count { statuses[it.id] != AttendanceStatus.CANCELLED.name }
        val attended = slots.count { statuses[it.id] == AttendanceStatus.ATTENDED.name }
        return listOf(
            Measurement(MetricKeys.CLASSES_SCHEDULED, scheduled.toDouble()),
            Measurement(MetricKeys.CLASSES_ATTENDED, attended.toDouble()),
        )
    }

    private suspend fun travelMeasurements(day: EpochDay): List<Measurement> {
        val trips = travelDao.forDay(day).filter { it.plannedLeaveMs != null }
        if (trips.isEmpty()) return emptyList()
        val onTime = trips.count { it.departedMs <= it.plannedLeaveMs!! + ON_TIME_GRACE_MS }
        return listOf(Measurement(MetricKeys.ON_TIME_DEPARTURES, onTime.toDouble()))
    }

    /** Holstrom reminders ticked off during the day. */
    private suspend fun taskMeasurements(window: DayWindow): List<Measurement> {
        val done = reminderDao.completionsBetween(window.startMs, window.endMs)
        return if (done > 0) listOf(Measurement(MetricKeys.TASKS_COMPLETED, done.toDouble())) else emptyList()
    }

    private companion object {
        /** Enough history to see the night before the day starts. */
        const val SLEEP_LOOKBACK_MS = 36 * 3_600_000L
        const val SLEEP_REPLACE_MARGIN_MS = 12 * 3_600_000L
        const val ON_TIME_GRACE_MS = 2 * 60_000L
    }
}

internal fun DayEvaluation.toSnapshot(computedAtMs: Long, engineVersion: String) = DaySnapshot(
    record = DayRecordEntity(
        epochDay = epochDay,
        totalPoints = totalPoints,
        state = state.state.name,
        decisiveStateRuleId = state.decisiveRuleId,
        computedAtMs = computedAtMs,
        engineVersion = engineVersion,
    ),
    contributions = contributions.mapIndexed { index, c ->
        DayContributionEntity(
            epochDay = epochDay,
            ruleId = c.ruleId,
            position = index,
            label = c.label,
            category = c.category.name,
            metric = c.metric,
            value = c.value,
            rawPoints = c.rawPoints,
            points = c.points,
            limited = c.limited,
        )
    },
    metrics = metrics.map { DayMetricEntity(epochDay, it.metric, it.value) },
    categories = categoryPoints.map { DayCategoryPointsEntity(epochDay, it.category.name, it.points) },
    findings = state.findings.map {
        DayStateFindingEntity(epochDay, it.ruleId, it.label, it.metric, it.value, it.state.name)
    },
)
