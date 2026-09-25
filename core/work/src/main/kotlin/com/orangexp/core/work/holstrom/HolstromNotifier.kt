package com.orangexp.core.work.holstrom

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.orangexp.core.common.holstrom.HolstromIntents
import com.orangexp.core.data.model.FiredReminder
import com.orangexp.core.engine.ffi.CommandKind
import com.orangexp.core.engine.ffi.DeviceAction
import com.orangexp.core.work.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Holstrom's notifications: reminders with Done/Snooze, and reports on phone actions. */
@Singleton
class HolstromNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun showReminder(fired: FiredReminder) {
        val reminder = fired.reminder
        val detail = when {
            reminder.kind != CommandKind.DEADLINE -> context.getString(R.string.holstrom_notification_reminder)
            fired.isFinal && reminder.finalAlert -> context.getString(R.string.holstrom_due_now)
            fired.daysLeft == 0 -> context.getString(R.string.holstrom_due_today)
            fired.daysLeft == 1 -> context.getString(R.string.holstrom_due_tomorrow)
            else -> context.resources.getQuantityString(R.plurals.holstrom_days_left, fired.daysLeft, fired.daysLeft)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_orangexp)
            .setContentTitle(reminder.title)
            .setContentText(detail)
            .setSubText(context.getString(R.string.holstrom_name))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openHolstrom())
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.holstrom_action_done), action(ReminderActionReceiver.ACTION_DONE, reminder.id))
            .addAction(0, context.getString(R.string.holstrom_action_snooze), action(ReminderActionReceiver.ACTION_SNOOZE, reminder.id))
            .build()
        notify(NOTIFICATION_BASE + reminder.id.toInt(), notification)
    }

    fun showActionResult(action: DeviceAction, result: ActionResult) {
        val (text, intent, important) = when (result) {
            ActionResult.DONE -> Triple(context.getString(R.string.holstrom_action_performed, label(action)), openHolstrom(), false)
            ActionResult.NEEDS_POLICY_ACCESS -> Triple(
                context.getString(R.string.holstrom_action_needs_access, label(action)),
                PendingIntent.getActivity(
                    context,
                    REQUEST_POLICY,
                    Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
                true,
            )
            else -> Triple(context.getString(R.string.holstrom_action_failed, label(action)), openHolstrom(), true)
        }
        val notification = NotificationCompat.Builder(context, if (important) CHANNEL_REMINDERS else CHANNEL_ACTIONS)
            .setSmallIcon(R.drawable.ic_stat_orangexp)
            .setContentTitle(context.getString(R.string.holstrom_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (important) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .apply { if (!important) setTimeoutAfter(ACTION_NOTICE_TIMEOUT_MS) }
            .build()
        notify(NOTIFICATION_ACTION, notification)
    }

    fun dismiss(reminderId: Long) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_BASE + reminderId.toInt())
    }

    /** "Silence the phone", "Turn on the flashlight", … */
    fun label(action: DeviceAction): String = context.getString(
        when (action) {
            DeviceAction.SILENT -> R.string.holstrom_device_silent
            DeviceAction.VIBRATE -> R.string.holstrom_device_vibrate
            DeviceAction.RING -> R.string.holstrom_device_ring
            DeviceAction.DND_ON -> R.string.holstrom_device_dnd_on
            DeviceAction.DND_OFF -> R.string.holstrom_device_dnd_off
            DeviceAction.FLASHLIGHT_ON -> R.string.holstrom_device_flashlight_on
            DeviceAction.FLASHLIGHT_OFF -> R.string.holstrom_device_flashlight_off
            DeviceAction.SET_ALARM -> R.string.holstrom_device_alarm
            DeviceAction.SET_TIMER -> R.string.holstrom_device_timer
        },
    )

    private fun notify(id: Int, notification: android.app.Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun action(action: String, reminderId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("orangexp://reminder/$reminderId"))
            .putExtra(ExactReminderAlarms.EXTRA_REMINDER_ID, reminderId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openHolstrom(): PendingIntent? = HolstromIntents.openHolstrom(context)?.let {
        PendingIntent.getActivity(context, REQUEST_OPEN, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL_REMINDERS = "holstrom_reminders"
        const val CHANNEL_ACTIONS = "holstrom_actions"
        private const val NOTIFICATION_BASE = 20_000
        private const val NOTIFICATION_ACTION = 19_999
        private const val REQUEST_OPEN = 7
        private const val REQUEST_POLICY = 8
        private const val ACTION_NOTICE_TIMEOUT_MS = 30_000L

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_REMINDERS,
                    context.getString(R.string.channel_holstrom_reminders_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.channel_holstrom_reminders_description) },
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ACTIONS,
                    context.getString(R.string.channel_holstrom_actions_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.channel_holstrom_actions_description) },
            )
        }
    }
}
