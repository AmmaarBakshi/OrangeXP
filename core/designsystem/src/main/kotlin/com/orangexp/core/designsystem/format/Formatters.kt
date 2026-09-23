package com.orangexp.core.designsystem.format

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Plain, locale-aware formatting for measured values. No rounding tricks, no inflation. */
object OxFormat {

    fun points(value: Long, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getIntegerInstance(locale).format(value)

    /** "+320" / "−150" for breakdown rows. */
    fun signedPoints(value: Long, locale: Locale = Locale.getDefault()): String =
        (if (value < 0) "−" else "+") + points(abs(value), locale)

    /** 381 → "6h 21m", 45 → "45m", 0 → "0m". */
    fun duration(totalMinutes: Long): String {
        val minutes = totalMinutes.coerceAtLeast(0)
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    /** Minute of day → "06:45". Values outside a day wrap (e.g. −15 → "23:45"). */
    fun clock(minuteOfDay: Int): String {
        val m = Math.floorMod(minuteOfDay, 24 * 60)
        return "%02d:%02d".format(Locale.ROOT, m / 60, m % 60)
    }

    /** Meters → "850 m" or "4.7 km". */
    fun distance(meters: Double, locale: Locale = Locale.getDefault()): String =
        if (meters < 1_000) {
            "${meters.roundToLong()} m"
        } else {
            val format = NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            }
            "${format.format(meters / 1_000)} km"
        }

    fun percent(value: Double): String = "${value.roundToLong()}%"
}
