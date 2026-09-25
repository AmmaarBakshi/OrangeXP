package com.orangexp.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.common.time.localMinuteToEpochMs
import com.orangexp.core.data.model.ReminderStatus
import com.orangexp.core.data.repository.DataChangeNotifier
import com.orangexp.core.data.repository.OfflineAcademicRepository
import com.orangexp.core.data.repository.OfflineBriefingRepository
import com.orangexp.core.data.repository.OfflineCompetitionRepository
import com.orangexp.core.data.repository.OfflineConfigRepository
import com.orangexp.core.data.repository.OfflineDayRepository
import com.orangexp.core.data.repository.OfflineHolstromSettingsRepository
import com.orangexp.core.data.repository.OfflineMovementRepository
import com.orangexp.core.data.repository.OfflineReminderRepository
import com.orangexp.core.data.repository.OfflineTravelRepository
import com.orangexp.core.data.repository.ReminderAlarms
import com.orangexp.core.database.OrangeXpDatabase
import com.orangexp.core.engine.MetricKeys
import com.orangexp.core.engine.UniffiOrangeEngine
import com.orangexp.core.engine.ffi.CommandKind
import com.orangexp.core.engine.ffi.DeviceAction
import com.orangexp.core.sensing.movement.MovementTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Holstrom's reminders end to end: sentence → engine → Room → alarms → score. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HolstromRepositoryTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 9, 26).toEpochDay().toInt() // Saturday

    private fun at(dayOffset: Int, hour: Int, minute: Int = 0) =
        localMinuteToEpochMs(day + dayOffset, hour * 60 + minute, zone)

    private val clock = object : TimeSource {
        var nowMs = at(0, 14)
        override fun now(): Instant = Instant.ofEpochMilli(nowMs)
        override fun zone(): ZoneId = zone
    }

    private val alarms = object : ReminderAlarms {
        val scheduled = mutableMapOf<Long, Long>()
        override fun schedule(reminderId: Long, atMs: Long) {
            scheduled[reminderId] = atMs
        }
        override fun cancel(reminderId: Long) {
            scheduled.remove(reminderId)
        }
    }

    private val noTracker = object : MovementTracker {
        override fun isSupported() = false
        override fun missingPermissions() = emptyList<String>()
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override fun startLocationUpdates() = Unit
        override fun stopLocationUpdates() = Unit
    }

    private lateinit var db: OrangeXpDatabase
    private lateinit var days: OfflineDayRepository
    private lateinit var reminders: OfflineReminderRepository
    private lateinit var briefing: OfflineBriefingRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OrangeXpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val engine = UniffiOrangeEngine()
        val dispatcher = Dispatchers.Unconfined
        val noChanges = DataChangeNotifier(emptySet())
        val movement = OfflineMovementRepository(db.movementDao(), db.keyValueDao(), noTracker, engine, clock)
        val config = OfflineConfigRepository(db.keyValueDao(), engine, dispatcher)
        val academics = OfflineAcademicRepository(
            db.timetableDao(), db.syllabusDao(), db.studyDao(), db.attendanceDao(), db.keyValueDao(),
            config, engine, clock, noChanges, dispatcher,
        )
        days = OfflineDayRepository(
            db.dayRecordDao(), db.deviceEventDao(), db.sleepDao(), db.stepsDao(), db.studyDao(), db.syllabusDao(),
            db.timetableDao(), db.attendanceDao(), db.travelDao(), db.reminderDao(), db.keyValueDao(), config, engine,
            movement, clock, noChanges, dispatcher,
        )
        val settings = OfflineHolstromSettingsRepository(db.keyValueDao())
        reminders = OfflineReminderRepository(db.reminderDao(), engine, settings, alarms, days, clock, noChanges, dispatcher)
        val travel = OfflineTravelRepository(db.travelDao(), academics, config, engine, clock, dispatcher)
        val competitions = OfflineCompetitionRepository(db.competitionDao(), config, engine, clock, dispatcher)
        briefing = OfflineBriefingRepository(days, academics, travel, competitions, reminders, clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun deadlineNudgesEveryDayAndCompletingItScores() = runTest {
        val text = "i have to submit my assignment at 3 oct"
        val command = reminders.parse(text)
        assertEquals(CommandKind.DEADLINE, command.kind)

        val stored = reminders.create(command, text)
        assertEquals("Submit my assignment", stored.title)
        assertEquals(at(1, 9), stored.nextFireMs)
        assertEquals(at(1, 9), alarms.scheduled[stored.id])

        clock.nowMs = at(1, 9)
        val fired = assertNotNull(reminders.fire(stored.id))
        assertEquals(6, fired.daysLeft)
        assertEquals(at(2, 9), alarms.scheduled[stored.id], "tomorrow's nudge is armed")

        reminders.complete(stored.id)
        assertNull(alarms.scheduled[stored.id])
        assertEquals(ReminderStatus.DONE, reminders.get(stored.id)!!.status)
        val today = assertNotNull(days.breakdown(day + 1).first())
        assertEquals(1.0, today.metrics[MetricKeys.TASKS_COMPLETED])
        assertTrue(today.contributions.any { it.ruleId == "tasks.completed" && it.points > 0 })
    }

    @Test
    fun oneOffReminderBecomesDueAfterItFires() = runTest {
        val text = "remind me texting ria tommoro"
        val stored = reminders.create(reminders.parse(text), text)
        assertEquals("Texting ria", stored.title)
        assertEquals(at(1, 9), stored.nextFireMs)

        clock.nowMs = at(1, 9)
        val fired = assertNotNull(reminders.fire(stored.id))
        assertTrue(fired.isFinal)
        assertTrue(reminders.get(stored.id)!!.isDue)

        reminders.snooze(stored.id, 15)
        assertEquals(at(1, 9, 15), alarms.scheduled[stored.id])
    }

    @Test
    fun scheduledPhoneActionRunsOnceAndMissedAlarmsAreRestored() = runTest {
        val action = reminders.scheduleAction(DeviceAction.SILENT, at(0, 15), "silent my phone after 1hr")
        assertEquals(at(0, 15), alarms.scheduled[action.id])

        alarms.scheduled.clear()
        clock.nowMs = at(0, 16) // the phone was off when the alarm was due
        reminders.restoreAlarms()
        assertEquals(clock.nowMs + 5_000, alarms.scheduled[action.id])

        assertNotNull(reminders.fire(action.id))
        assertEquals(ReminderStatus.DONE, reminders.get(action.id)!!.status)
        assertNull(reminders.fire(action.id), "an action never runs twice")
    }

    @Test
    fun briefingCollectsTheWholeApp() = runTest {
        val text = "remind me to call mom at 6pm"
        reminders.create(reminders.parse(text), text)
        days.evaluate(day)
        val snapshot = briefing.current()
        assertEquals(day, snapshot.today)
        assertEquals(14 * 60, snapshot.minuteOfDay)
        assertEquals(listOf("Call mom"), snapshot.reminders.map { it.title })
        assertEquals(day, snapshot.lastDays.last().first)
    }
}
