package com.orangexp.core.work.holstrom

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.orangexp.core.data.repository.ReminderAlarms
import com.orangexp.core.data.repository.ReminderRepository
import com.orangexp.core.engine.ffi.CommandKind
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exact wake-up alarms, one per reminder. They fire in Doze and while the
 * screen is off, so a reminder at 09:00 arrives at 09:00, not at the next
 * maintenance window.
 */
@Singleton
internal class ExactReminderAlarms @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReminderAlarms {
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)

    override fun schedule(reminderId: Long, atMs: Long) {
        val pending = pendingIntent(context, reminderId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            // Only if the user revoked "Alarms & reminders": still wakes the phone, a few minutes late.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
        }
    }

    override fun cancel(reminderId: Long) {
        alarmManager.cancel(pendingIntent(context, reminderId))
    }

    companion object {
        const val EXTRA_REMINDER_ID = "reminder_id"

        /** One distinct intent per reminder (the data URI keeps them apart). */
        fun pendingIntent(context: Context, reminderId: Long): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderAlarmReceiver::class.java)
                .setData(Uri.parse("orangexp://reminder/$reminderId"))
                .putExtra(EXTRA_REMINDER_ID, reminderId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Runs [block] off the main thread while keeping the broadcast alive until it finishes. */
internal fun BroadcastReceiver.handleAsync(tag: String, block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            block()
        } catch (e: Exception) {
            Log.w(tag, "Broadcast handling failed", e)
        } finally {
            pending.finish()
        }
    }
}

/** A reminder's alarm went off: notify, or carry out the phone action. */
@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var reminders: ReminderRepository
    @Inject lateinit var actions: DeviceActions
    @Inject lateinit var notifier: HolstromNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ExactReminderAlarms.EXTRA_REMINDER_ID, -1L).takeIf { it > 0 } ?: return
        handleAsync(TAG) {
            val fired = reminders.fire(id) ?: return@handleAsync
            val reminder = fired.reminder
            if (reminder.kind == CommandKind.DEVICE_ACTION) {
                val action = reminder.action ?: return@handleAsync
                val result = actions.perform(action, reminder.atMs, reminder.durationMinutes)
                notifier.showActionResult(action, result)
            } else {
                notifier.showReminder(fired)
            }
        }
    }

    private companion object {
        const val TAG = "ReminderAlarmReceiver"
    }
}

/** The Done and Snooze buttons on a reminder notification. */
@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {
    @Inject lateinit var reminders: ReminderRepository
    @Inject lateinit var notifier: HolstromNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ExactReminderAlarms.EXTRA_REMINDER_ID, -1L).takeIf { it > 0 } ?: return
        notifier.dismiss(id)
        handleAsync(TAG) {
            when (intent.action) {
                ACTION_DONE -> reminders.complete(id)
                ACTION_SNOOZE -> reminders.snooze(id, SNOOZE_MINUTES)
            }
        }
    }

    companion object {
        private const val TAG = "ReminderActionReceiver"
        const val ACTION_DONE = "com.orangexp.action.REMINDER_DONE"
        const val ACTION_SNOOZE = "com.orangexp.action.REMINDER_SNOOZE"
        const val SNOOZE_MINUTES = 60
    }
}

/** Alarms do not survive a reboot, and a clock change moves their wall-clock times. */
@AndroidEntryPoint
class ReminderRestoreReceiver : BroadcastReceiver() {
    @Inject lateinit var reminders: ReminderRepository

    override fun onReceive(context: Context, intent: Intent) {
        handleAsync("ReminderRestoreReceiver") { reminders.restoreAlarms() }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface HolstromWorkModule {
    @Binds
    fun reminderAlarms(impl: ExactReminderAlarms): ReminderAlarms
}
