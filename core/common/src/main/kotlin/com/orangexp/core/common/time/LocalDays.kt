package com.orangexp.core.common.time

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Days since 1970-01-01 in the user's local calendar, as used by the engine. */
typealias EpochDay = Int

val LocalDate.epochDay: EpochDay get() = toEpochDay().toInt()

fun EpochDay.toLocalDate(): LocalDate = LocalDate.ofEpochDay(toLong())

/** Local day `[start, end)` in epoch milliseconds. DST-aware: may be 23 or 25 hours long. */
data class DayWindow(val day: EpochDay, val startMs: Long, val endMs: Long) {
    operator fun contains(timestampMs: Long): Boolean = timestampMs in startMs until endMs
}

fun dayWindow(day: EpochDay, zone: ZoneId): DayWindow {
    val date = day.toLocalDate()
    return DayWindow(
        day = day,
        startMs = date.atStartOfDay(zone).toInstant().toEpochMilli(),
        endMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    )
}

fun TimeSource.today(): EpochDay = LocalDate.ofInstant(now(), zone()).epochDay

fun TimeSource.nowMs(): Long = now().toEpochMilli()

fun epochDayOf(timestampMs: Long, zone: ZoneId): EpochDay =
    LocalDate.ofInstant(Instant.ofEpochMilli(timestampMs), zone).epochDay

/** UTC offset at `instant`, in minutes, for the engine's fixed-offset helpers. */
fun utcOffsetMinutes(instant: Instant, zone: ZoneId): Int =
    zone.rules.getOffset(instant).totalSeconds / 60

/** Epoch milliseconds of a local minute-of-day on `day` (may be negative or ≥ 1440). */
fun localMinuteToEpochMs(day: EpochDay, minuteOfDay: Int, zone: ZoneId): Long {
    val midnight = ZonedDateTime.of(day.toLocalDate(), LocalTime.MIDNIGHT, zone)
    return midnight.plusMinutes(minuteOfDay.toLong()).toInstant().toEpochMilli()
}

/** ISO day of week, Monday = 1 ... Sunday = 7. */
fun EpochDay.dayOfWeek(): DayOfWeek = toLocalDate().dayOfWeek
