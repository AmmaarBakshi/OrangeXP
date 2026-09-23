package com.orangexp.core.work

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.common.time.nowMs
import com.orangexp.core.common.time.today
import com.orangexp.core.data.repository.AcademicRepository
import com.orangexp.core.data.repository.TravelRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules today's "start preparing" and "leave now" notifications from the
 * engine's departure plan. Re-run on every tracking pass, so plan changes and
 * reboots are picked up without extra receivers.
 */
@Singleton
class DepartureReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val travel: TravelRepository,
    private val academics: AcademicRepository,
    private val time: TimeSource,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    suspend fun scheduleToday() {
        val departure = travel.departure(time.today()).first()
        val trip = travel.trip(time.today()).first()
        if (departure == null || trip.departedMs != null) {
            cancel(Kind.PREPARE)
            cancel(Kind.LEAVE)
            return
        }
        val slotId = departure.plan.slotId?.toLongOrNull()
        val title = academics.timetable.first().firstOrNull { it.id == slotId }?.title.orEmpty()
        val plan = departure.plan
        schedule(Kind.PREPARE, departure.startPreparingMs, title, plan.eventStartMinute, plan.leaveMinute, plan.earlyByMinutes)
        schedule(Kind.LEAVE, departure.leaveMs, title, plan.eventStartMinute, plan.leaveMinute, plan.earlyByMinutes)
    }

    private fun schedule(kind: Kind, atMs: Long, title: String, startMinute: Int, leaveMinute: Int, earlyBy: Int) {
        if (atMs <= time.nowMs()) return cancel(kind)
        val intent = Intent(context, DepartureReminderReceiver::class.java)
            .putExtra(EXTRA_KIND, kind.name)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_START, clock(startMinute))
            .putExtra(EXTRA_LEAVE, clock(leaveMinute))
            .putExtra(EXTRA_EARLY_BY, earlyBy)
        // Inexact-while-idle is enough: the plan already contains a safety buffer.
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pendingIntent(kind, intent))
    }

    private fun cancel(kind: Kind) {
        alarmManager.cancel(pendingIntent(kind, Intent(context, DepartureReminderReceiver::class.java)))
    }

    private fun pendingIntent(kind: Kind, intent: Intent): PendingIntent = PendingIntent.getBroadcast(
        context,
        kind.ordinal,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal enum class Kind { PREPARE, LEAVE }

    internal companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START = "start"
        const val EXTRA_LEAVE = "leave"
        const val EXTRA_EARLY_BY = "early_by"

        fun clock(minuteOfDay: Int): String {
            val m = Math.floorMod(minuteOfDay, 24 * 60)
            return "%02d:%02d".format(Locale.ROOT, m / 60, m % 60)
        }
    }
}

class DepartureReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val kind = intent.getStringExtra(DepartureReminderScheduler.EXTRA_KIND)
            ?.let { runCatching { DepartureReminderScheduler.Kind.valueOf(it) }.getOrNull() } ?: return
        val title = intent.getStringExtra(DepartureReminderScheduler.EXTRA_TITLE).orEmpty()
        val start = intent.getStringExtra(DepartureReminderScheduler.EXTRA_START).orEmpty()
        val leave = intent.getStringExtra(DepartureReminderScheduler.EXTRA_LEAVE).orEmpty()
        val earlyBy = intent.getIntExtra(DepartureReminderScheduler.EXTRA_EARLY_BY, 0)

        val (heading, body) = when (kind) {
            DepartureReminderScheduler.Kind.PREPARE ->
                context.getString(R.string.reminder_prepare_title) to
                    context.getString(R.string.reminder_prepare_body, title, start, leave)
            DepartureReminderScheduler.Kind.LEAVE ->
                context.getString(R.string.reminder_leave_title) to
                    context.getString(R.string.reminder_leave_body, title, start, earlyBy)
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, NotificationChannels.DEPARTURES)
            .setSmallIcon(R.drawable.ic_stat_orangexp)
            .setContentTitle(heading)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(launch)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE_ID + kind.ordinal, notification)
    }

    private companion object {
        const val NOTIFICATION_BASE_ID = 1_000
    }
}

object NotificationChannels {
    const val DEPARTURES = "departures"

    fun create(context: Context) {
        val channel = NotificationChannel(
            DEPARTURES,
            context.getString(R.string.channel_departures_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.channel_departures_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
