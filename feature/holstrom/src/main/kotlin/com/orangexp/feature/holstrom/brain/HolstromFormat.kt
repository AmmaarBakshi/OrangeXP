package com.orangexp.feature.holstrom.brain

import android.content.Context
import android.text.format.DateFormat
import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.common.time.epochDayOf
import com.orangexp.core.common.time.today
import com.orangexp.core.ui.formatMedium
import com.orangexp.feature.holstrom.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Times and moments in words: "tomorrow at 09:00", "on Monday at 19:00". */
@Singleton
class HolstromFormat @Inject constructor(
    @ApplicationContext private val context: Context,
    private val time: TimeSource,
) {
    private val clockFormatter: DateTimeFormatter
        get() = DateTimeFormatter.ofPattern(
            if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",
            Locale.getDefault(),
        )

    fun clock(ms: Long): String = LocalTime.ofInstant(Instant.ofEpochMilli(ms), time.zone()).format(clockFormatter)

    fun clockOfMinute(minuteOfDay: Int): String =
        LocalTime.of((minuteOfDay / 60).coerceIn(0, 23), minuteOfDay % 60).format(clockFormatter)

    fun weekday(ms: Long): String = Instant.ofEpochMilli(ms).atZone(time.zone()).dayOfWeek
        .getDisplayName(TextStyle.FULL, Locale.getDefault())

    fun day(ms: Long): String = epochDayOf(ms, time.zone()).formatMedium()

    /** A moment relative to now, for replies and lists. */
    fun moment(ms: Long): String {
        val days = epochDayOf(ms, time.zone()) - time.today()
        val clock = clock(ms)
        val minutesAway = (ms - time.now().toEpochMilli()) / 60_000
        return when {
            minutesAway in 0..<1 -> context.getString(R.string.when_now)
            minutesAway in 1..<90 -> context.getString(R.string.when_in_minutes, minutesAway.toInt(), clock)
            days == 0 -> context.getString(R.string.when_today, clock)
            days == 1 -> context.getString(R.string.when_tomorrow, clock)
            days in 2..6 -> context.getString(R.string.when_weekday, weekday(ms), clock)
            else -> context.getString(R.string.when_date, day(ms), clock)
        }
    }

    /** A title as it reads after "remind you to": "Texting ria" → "texting ria". */
    fun task(title: String): String =
        if (title.length > 1 && title[1].isLowerCase()) title.replaceFirstChar { it.lowercase() } else title
}
