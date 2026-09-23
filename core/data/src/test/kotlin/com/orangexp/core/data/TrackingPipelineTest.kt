package com.orangexp.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.common.time.dayWindow
import com.orangexp.core.data.model.AttendanceStatus
import com.orangexp.core.data.model.TimetableEntry
import com.orangexp.core.data.repository.DataChangeNotifier
import com.orangexp.core.data.repository.OfflineAcademicRepository
import com.orangexp.core.data.repository.OfflineConfigRepository
import com.orangexp.core.data.repository.OfflineDayRepository
import com.orangexp.core.data.repository.OfflineTravelRepository
import com.orangexp.core.data.tracking.DefaultTrackingCoordinator
import com.orangexp.core.database.OrangeXpDatabase
import com.orangexp.core.engine.MetricKeys
import com.orangexp.core.engine.UniffiOrangeEngine
import com.orangexp.core.engine.ffi.DeviceEvent
import com.orangexp.core.engine.ffi.DeviceEventKind
import com.orangexp.core.sensing.DeviceUsageSource
import com.orangexp.core.sensing.PowerStateSource
import com.orangexp.core.sensing.StepCounterSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The full tracking pipeline against the real Rust engine: fake sensors →
 * ingestion → Room → measurement assembly → engine → stored, explained day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackingPipelineTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val date = LocalDate.of(2026, 9, 24) // Thursday
    private val day = date.toEpochDay().toInt()
    private val midnight = dayWindow(day, zone).startMs
    private val hour = 3_600_000L

    private val clock = object : TimeSource {
        var nowMs = midnight + 20 * hour
        override fun now(): Instant = Instant.ofEpochMilli(nowMs)
        override fun zone(): ZoneId = zone
    }

    private val usage = object : DeviceUsageSource {
        val events = mutableListOf<DeviceEvent>()
        override fun hasAccess() = true
        override fun events(fromMs: Long, toMs: Long) = events.filter { it.timestampMs in fromMs until toMs }
    }

    private val steps = object : StepCounterSource {
        var counter = 10_000L
        override fun isAvailable() = true
        override fun hasPermission() = true
        override suspend fun readCumulativeSteps() = counter
    }

    private val power = object : PowerStateSource {
        override fun isCharging() = false
    }

    private lateinit var db: OrangeXpDatabase
    private lateinit var days: OfflineDayRepository
    private lateinit var academics: OfflineAcademicRepository
    private lateinit var travel: OfflineTravelRepository
    private lateinit var coordinator: DefaultTrackingCoordinator

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OrangeXpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val engine = UniffiOrangeEngine()
        val dispatcher = Dispatchers.Unconfined
        val noChanges = DataChangeNotifier(emptySet())
        val config = OfflineConfigRepository(db.keyValueDao(), engine, dispatcher)
        academics = OfflineAcademicRepository(
            db.timetableDao(), db.syllabusDao(), db.studyDao(), db.attendanceDao(), db.keyValueDao(),
            config, engine, clock, noChanges, dispatcher,
        )
        days = OfflineDayRepository(
            db.dayRecordDao(), db.deviceEventDao(), db.sleepDao(), db.stepsDao(), db.studyDao(), db.syllabusDao(),
            db.timetableDao(), db.attendanceDao(), db.travelDao(), db.keyValueDao(), config, engine, clock, noChanges,
            dispatcher,
        )
        travel = OfflineTravelRepository(db.travelDao(), academics, config, engine, clock, dispatcher)
        coordinator = DefaultTrackingCoordinator(
            usage, steps, power, db.deviceEventDao(), db.stepsDao(), db.keyValueDao(), days, academics, clock,
        )
    }

    @After
    fun tearDown() = db.close()

    private fun usePhone(atMs: Long, minutes: Long) {
        usage.events += DeviceEvent(atMs, DeviceEventKind.UNLOCK)
        usage.events += DeviceEvent(atMs + 1_000, DeviceEventKind.INTERACTION)
        usage.events += DeviceEvent(atMs + minutes * 60_000, DeviceEventKind.SCREEN_OFF)
    }

    @Test
    fun trackedDayIsScoredAndExplained() = runTest {
        // Phone use until 23:30 the night before, awake at 06:45, regular use through the day.
        usePhone(midnight - 40 * 60_000, 10)
        var t = midnight + 6 * hour + 45 * 60_000
        while (t < clock.nowMs - hour) {
            usePhone(t, 10)
            t += 40 * 60_000
        }
        academics.saveTimetableEntry(
            TimetableEntry(title = "Data Structures", weekday = DayOfWeek.THURSDAY, startMinute = 495, endMinute = 555),
        )
        val slot = academics.timetable.first().single()
        academics.setAttendance(day, slot.id, AttendanceStatus.ATTENDED)

        val subjectId = academics.saveSubject(0, "DSA", examDay = day + 30, priority = 4)
        val unitId = academics.addUnit(subjectId, "Linear structures")
        val topicId = academics.addTopic(unitId, subjectId, "Linked lists", estimatedMinutes = 90, difficulty = 3)
        academics.setTopicCompleted(topicId, completed = true)

        coordinator.sync() // establishes the step baseline
        steps.counter += 8_000
        clock.nowMs += 60_000
        val result = coordinator.sync()

        val breakdown = assertNotNull(days.breakdown(day).first())
        assertEquals(result.totalPoints, breakdown.totalPoints)
        assertEquals(breakdown.totalPoints, breakdown.contributions.sumOf { it.points })

        val metrics = breakdown.metrics
        assertEquals(8_000.0, metrics[MetricKeys.STEPS])
        assertEquals(1.0, metrics[MetricKeys.CLASSES_ATTENDED])
        assertEquals(90.0, metrics[MetricKeys.SYLLABUS_COMPLETED_MINUTES])
        // Last activity 23:30 + 45 min threshold → asleep 00:15; woke 06:45 → 6h30m.
        assertEquals(390.0, metrics[MetricKeys.SLEEP_MINUTES])
        assertTrue(breakdown.contributions.any { it.ruleId == "sleep.duration" && it.points > 0 })

        val status = days.sleepStatus.first()
        assertEquals(midnight + 6 * hour + 45 * 60_000, status.awakeSinceMs)

        val history = days.history().first()
        assertEquals(breakdown.totalPoints, history.statistics.lifetimePoints)
        assertEquals(day, history.heatmap.cells.last().epochDay)

        val departure = assertNotNull(travel.departure(day).first())
        assertEquals(495 - 30 - 45, departure.plan.leaveMinute)
    }

    @Test
    fun missedDayIsReplannedFromToday() = runTest {
        val subjectId = academics.saveSubject(0, "DBMS", examDay = day + 10, priority = 3)
        val unitId = academics.addUnit(subjectId, "Design")
        academics.addTopic(unitId, subjectId, "Normalization", estimatedMinutes = 120, difficulty = 3)

        val firstPlan = academics.plan(day, day + 30).first()
        assertEquals(day, firstPlan.first().day)

        clock.nowMs += 24 * hour
        academics.regeneratePlan()
        val replanned = academics.plan(day + 1, day + 30).first()
        assertEquals(day + 1, replanned.first().day)
        assertEquals(120, replanned.sumOf { it.minutes })
    }
}
