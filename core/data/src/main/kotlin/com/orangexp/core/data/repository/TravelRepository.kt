package com.orangexp.core.data.repository

import com.orangexp.core.common.coroutines.Dispatcher
import com.orangexp.core.common.coroutines.OxDispatchers
import com.orangexp.core.common.time.EpochDay
import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.common.time.dayOfWeek
import com.orangexp.core.common.time.localMinuteToEpochMs
import com.orangexp.core.common.time.nowMs
import com.orangexp.core.common.time.today
import com.orangexp.core.data.model.toEngine
import com.orangexp.core.database.dao.TravelDao
import com.orangexp.core.database.model.TravelRecordEntity
import com.orangexp.core.engine.OrangeEngine
import com.orangexp.core.engine.ffi.DeparturePlan
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A departure plan resolved to absolute times for a specific day. */
data class DayDeparture(
    val day: EpochDay,
    val plan: DeparturePlan,
    val startPreparingMs: Long,
    val leaveMs: Long,
    val eventStartMs: Long,
)

data class TripStatus(val departedMs: Long?, val arrivedMs: Long?)

interface TravelRepository {
    /** When to start preparing and leave for the first class that needs travel on [day]. */
    fun departure(day: EpochDay): Flow<DayDeparture?>

    fun trip(day: EpochDay): Flow<TripStatus>

    suspend fun recordDeparture()
    suspend fun recordArrival()
}

@Singleton
internal class OfflineTravelRepository @Inject constructor(
    private val travelDao: TravelDao,
    private val academicRepository: AcademicRepository,
    private val configRepository: ConfigRepository,
    private val engine: OrangeEngine,
    private val time: TimeSource,
    @Dispatcher(OxDispatchers.Default) private val dispatcher: CoroutineDispatcher,
) : TravelRepository {

    override fun departure(day: EpochDay): Flow<DayDeparture?> =
        combine(academicRepository.timetable, configRepository.config, travelDao.observeDay(day)) { entries, config, _ ->
            val history = travelDao.recentTripMinutes(HISTORY_SIZE).map { it.coerceAtLeast(0).toUInt() }
            val plan = engine.planFirstDeparture(
                slots = entries.map { it.toSlot() },
                weekday = day.dayOfWeek().toEngine(),
                travelHistoryMinutes = history,
                config = config.travel,
            ) ?: return@combine null
            val zone = time.zone()
            DayDeparture(
                day = day,
                plan = plan,
                startPreparingMs = localMinuteToEpochMs(day, plan.startPreparingMinute, zone),
                leaveMs = localMinuteToEpochMs(day, plan.leaveMinute, zone),
                eventStartMs = localMinuteToEpochMs(day, plan.eventStartMinute, zone),
            )
        }.flowOn(dispatcher)

    override fun trip(day: EpochDay): Flow<TripStatus> = travelDao.observeDay(day).map { trips ->
        val last = trips.lastOrNull()
        TripStatus(departedMs = last?.departedMs, arrivedMs = last?.arrivedMs)
    }

    override suspend fun recordDeparture() {
        val today = time.today()
        val plan = departure(today).first()
        travelDao.insert(
            TravelRecordEntity(
                epochDay = today,
                slotId = plan?.plan?.slotId?.toLongOrNull(),
                plannedLeaveMs = plan?.leaveMs,
                departedMs = time.nowMs(),
            ),
        )
    }

    override suspend fun recordArrival() {
        val open = travelDao.forDay(time.today()).lastOrNull { it.arrivedMs == null } ?: return
        travelDao.update(open.copy(arrivedMs = time.nowMs()))
    }

    private companion object {
        const val HISTORY_SIZE = 20
    }
}
