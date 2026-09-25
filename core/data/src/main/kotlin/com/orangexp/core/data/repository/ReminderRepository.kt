package com.orangexp.core.data.repository

import com.orangexp.core.common.coroutines.Dispatcher
import com.orangexp.core.common.coroutines.OxDispatchers
import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.common.time.epochDayOf
import com.orangexp.core.common.time.nowMs
import com.orangexp.core.common.time.today
import com.orangexp.core.common.time.utcOffsetMinutes
import com.orangexp.core.data.model.FiredReminder
import com.orangexp.core.data.model.Reminder
import com.orangexp.core.data.model.ReminderStatus
import com.orangexp.core.data.model.enumValueOrDefault
import com.orangexp.core.database.dao.ReminderDao
import com.orangexp.core.database.model.ReminderCompletionEntity
import com.orangexp.core.database.model.ReminderEntity
import com.orangexp.core.engine.OrangeEngine
import com.orangexp.core.engine.ffi.CommandInput
import com.orangexp.core.engine.ffi.CommandKind
import com.orangexp.core.engine.ffi.DeviceAction
import com.orangexp.core.engine.ffi.ParsedCommand
import com.orangexp.core.engine.ffi.ReminderSchedule
import com.orangexp.core.engine.ffi.RepeatRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The platform alarm behind each reminder. Implemented with exact, wake-up
 * alarms in `core:work` so reminders fire while the phone sleeps.
 */
interface ReminderAlarms {
    fun schedule(reminderId: Long, atMs: Long)

    fun cancel(reminderId: Long)
}

/** Reminders, deadlines and scheduled phone actions that Holstrom looks after. */
interface ReminderRepository {
    val active: Flow<List<Reminder>>
    val finished: Flow<List<Reminder>>

    /** Reads a sentence without storing anything, e.g. for a live preview while typing. */
    suspend fun parse(text: String): ParsedCommand

    /** Stores a reminder or deadline and schedules its first alarm. */
    suspend fun create(command: ParsedCommand, sourceText: String): Reminder

    /** Schedules a phone action to run at [atMs]. */
    suspend fun scheduleAction(action: DeviceAction, atMs: Long, sourceText: String, durationMinutes: Int? = null): Reminder

    suspend fun get(id: Long): Reminder?

    /** Changes the title and time of a reminder and reschedules it. */
    suspend fun edit(id: Long, title: String, atMs: Long)

    /** Records that the alarm of [id] went off and schedules the next one. `null` if it is no longer active. */
    suspend fun fire(id: Long): FiredReminder?

    /** Ticks off a reminder (one occurrence for repeating ones). Counts toward the day's tasks. */
    suspend fun complete(id: Long)

    suspend fun snooze(id: Long, minutes: Int)

    suspend fun cancel(id: Long)

    suspend fun delete(id: Long)

    suspend fun clearFinished()

    /** Re-arms every alarm, e.g. after a reboot or a clock change. Missed alarms fire right away. */
    suspend fun restoreAlarms()
}

@Singleton
internal class OfflineReminderRepository @Inject constructor(
    private val dao: ReminderDao,
    private val engine: OrangeEngine,
    private val settings: HolstromSettingsRepository,
    private val alarms: ReminderAlarms,
    private val days: DayRepository,
    private val time: TimeSource,
    private val changes: DataChangeNotifier,
    @Dispatcher(OxDispatchers.Default) private val dispatcher: CoroutineDispatcher,
) : ReminderRepository {

    override val active: Flow<List<Reminder>> = dao.observeActive().map { rows -> rows.map { it.toModel() } }

    override val finished: Flow<List<Reminder>> = dao.observeFinished(50).map { rows -> rows.map { it.toModel() } }

    override suspend fun parse(text: String): ParsedCommand = withContext(dispatcher) {
        val s = settings.current()
        engine.parseCommand(
            CommandInput(
                text = text,
                nowMs = time.nowMs(),
                utcOffsetMinutes = offset(),
                defaultMinute = s.defaultMinute.toUInt(),
                defaultDelayMinutes = s.defaultDelayMinutes.toUInt(),
            ),
        )
    }

    override suspend fun create(command: ParsedCommand, sourceText: String): Reminder = withContext(dispatcher) {
        require(command.kind == CommandKind.REMINDER || command.kind == CommandKind.DEADLINE) {
            "Only reminders and deadlines are stored with create()"
        }
        val now = time.nowMs()
        val entity = ReminderEntity(
            title = command.title.ifBlank { sourceText.trim() }.take(MAX_TITLE),
            sourceText = sourceText.trim(),
            kind = command.kind.name,
            atMs = command.atMs ?: (now + settings.current().defaultDelayMinutes * MINUTE),
            repeat = command.repeat.name,
            nudgeMinute = settings.current().nudgeMinute,
            finalAlert = command.kind == CommandKind.DEADLINE && command.timeExplicit,
            createdMs = now,
        )
        val id = dao.upsert(entity)
        arm(entity.copy(id = id), after = now)
    }

    override suspend fun scheduleAction(
        action: DeviceAction,
        atMs: Long,
        sourceText: String,
        durationMinutes: Int?,
    ): Reminder = withContext(dispatcher) {
        val now = time.nowMs()
        val entity = ReminderEntity(
            title = "",
            sourceText = sourceText.trim(),
            kind = CommandKind.DEVICE_ACTION.name,
            atMs = atMs.coerceAtLeast(now),
            action = action.name,
            durationMinutes = durationMinutes,
            nextFireMs = atMs.coerceAtLeast(now),
            createdMs = now,
        )
        val id = dao.upsert(entity)
        alarms.schedule(id, entity.atMs)
        changes.notifyChanged()
        entity.copy(id = id).toModel()
    }

    override suspend fun get(id: Long): Reminder? = dao.get(id)?.toModel()

    override suspend fun edit(id: Long, title: String, atMs: Long) = withContext(dispatcher) {
        val existing = dao.get(id) ?: return@withContext
        val updated = existing.copy(title = title.trim().ifBlank { existing.title }.take(MAX_TITLE), atMs = atMs)
        dao.upsert(updated)
        if (existing.kind == CommandKind.DEVICE_ACTION.name) {
            dao.setNextFire(id, atMs)
            alarms.schedule(id, atMs)
            changes.notifyChanged()
        } else {
            arm(updated, after = time.nowMs())
        }
        Unit
    }

    override suspend fun fire(id: Long): FiredReminder? = withContext(dispatcher) {
        val entity = dao.get(id)?.takeIf { it.status == ReminderStatus.ACTIVE.name } ?: return@withContext null
        val now = time.nowMs()
        val reminder = entity.toModel()
        if (reminder.kind == CommandKind.DEVICE_ACTION) {
            dao.upsert(entity.copy(lastFiredMs = now))
            dao.finish(id, ReminderStatus.DONE.name, now)
            changes.notifyChanged()
            return@withContext FiredReminder(reminder, daysLeft = 0, isFinal = true)
        }
        // Never schedule twice for the same moment: continue from the later of now and this alarm.
        val after = maxOf(now, entity.nextFireMs ?: now)
        val next = nextFire(entity, after)
        dao.upsert(entity.copy(lastFiredMs = now, nextFireMs = next))
        if (next != null) alarms.schedule(id, next)
        changes.notifyChanged()
        val daysLeft = if (reminder.kind == CommandKind.DEADLINE) {
            (epochDayOf(entity.atMs, time.zone()) - time.today()).coerceAtLeast(0)
        } else {
            0
        }
        FiredReminder(reminder.copy(lastFiredMs = now, nextFireMs = next), daysLeft, isFinal = next == null)
    }

    override suspend fun complete(id: Long) = withContext(dispatcher) {
        val entity = dao.get(id) ?: return@withContext
        val now = time.nowMs()
        if (entity.kind != CommandKind.DEVICE_ACTION.name) {
            dao.insertCompletion(ReminderCompletionEntity(reminderId = id, completedMs = now))
        }
        val repeats = entity.repeat == RepeatRule.DAILY.name || entity.repeat == RepeatRule.WEEKLY.name
        if (!repeats) {
            dao.finish(id, ReminderStatus.DONE.name, now)
            alarms.cancel(id)
        } else if (entity.nextFireMs == null) {
            arm(entity, after = now)
        }
        // A completed task scores: re-evaluate today (this also refreshes widgets).
        days.evaluate(time.today())
        Unit
    }

    override suspend fun snooze(id: Long, minutes: Int) = withContext(dispatcher) {
        val entity = dao.get(id)?.takeIf { it.status == ReminderStatus.ACTIVE.name } ?: return@withContext
        val at = time.nowMs() + minutes.coerceIn(1, 24 * 60) * MINUTE
        // A snooze never pushes a repeating reminder's next regular alarm away.
        val next = entity.nextFireMs?.let { minOf(it, at) } ?: at
        dao.setNextFire(id, next)
        alarms.schedule(id, next)
        changes.notifyChanged()
    }

    override suspend fun cancel(id: Long) = withContext(dispatcher) {
        alarms.cancel(id)
        dao.finish(id, ReminderStatus.CANCELLED.name, time.nowMs())
        changes.notifyChanged()
    }

    override suspend fun delete(id: Long) = withContext(dispatcher) {
        alarms.cancel(id)
        dao.delete(id)
        changes.notifyChanged()
    }

    override suspend fun clearFinished() = dao.clearFinished()

    override suspend fun restoreAlarms() = withContext(dispatcher) {
        val now = time.nowMs()
        for (entity in dao.getActive()) {
            val next = entity.nextFireMs ?: continue
            alarms.schedule(entity.id, maxOf(next, now + RESTORE_DELAY_MS))
        }
    }

    /** Computes and schedules the first alarm after [after]; a reminder with none left is due now. */
    private suspend fun arm(entity: ReminderEntity, after: Long): Reminder {
        val next = nextFire(entity, after)
        dao.setNextFire(entity.id, next)
        if (next != null) alarms.schedule(entity.id, next) else alarms.cancel(entity.id)
        changes.notifyChanged()
        return entity.copy(nextFireMs = next).toModel()
    }

    private fun nextFire(entity: ReminderEntity, after: Long): Long? {
        val schedule = ReminderSchedule(
            atMs = entity.atMs,
            repeat = enumValueOrDefault(entity.repeat, RepeatRule.NONE),
            nudgeMinute = entity.nudgeMinute.coerceIn(0, 1_439).toUInt(),
            finalAlert = entity.finalAlert,
        )
        return engine.nextReminderFire(schedule, after, offset(after))?.atMs
    }

    private fun offset(atMs: Long = time.nowMs()): Int = utcOffsetMinutes(Instant.ofEpochMilli(atMs), time.zone())

    private companion object {
        const val MINUTE = 60_000L
        const val MAX_TITLE = 200
        const val RESTORE_DELAY_MS = 5_000L
    }
}

internal fun ReminderEntity.toModel() = Reminder(
    id = id,
    title = title,
    sourceText = sourceText,
    kind = enumValueOrDefault(kind, CommandKind.REMINDER),
    atMs = atMs,
    repeat = enumValueOrDefault(repeat, RepeatRule.NONE),
    nudgeMinute = nudgeMinute,
    finalAlert = finalAlert,
    action = action?.let { enumValueOrDefault(it, DeviceAction.SILENT) },
    durationMinutes = durationMinutes,
    nextFireMs = nextFireMs,
    lastFiredMs = lastFiredMs,
    status = enumValueOrDefault(status, ReminderStatus.ACTIVE),
    createdMs = createdMs,
    completedMs = completedMs,
)
