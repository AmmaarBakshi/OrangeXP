package com.orangexp.core.common.time

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalDaysTest {

    @Test
    fun `epoch day matches the engine's calendar`() {
        assertEquals(20_720, LocalDate.of(2026, 9, 24).epochDay)
        assertEquals(LocalDate.of(2026, 9, 24), 20_720.toLocalDate())
    }

    @Test
    fun `day window spans exactly one local day`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val window = dayWindow(20_720, zone)
        assertEquals(24 * 3_600_000L, window.endMs - window.startMs)
        assertEquals(20_720, epochDayOf(window.startMs, zone))
        assertEquals(20_720, epochDayOf(window.endMs - 1, zone))
        assertEquals(20_721, epochDayOf(window.endMs, zone))
    }

    @Test
    fun `daylight saving days are 23 or 25 hours long`() {
        val zone = ZoneId.of("Europe/Berlin")
        val spring = dayWindow(LocalDate.of(2026, 3, 29).epochDay, zone)
        val autumn = dayWindow(LocalDate.of(2026, 10, 25).epochDay, zone)
        assertEquals(23 * 3_600_000L, spring.endMs - spring.startMs)
        assertEquals(25 * 3_600_000L, autumn.endMs - autumn.startMs)
    }

    @Test
    fun `local minutes convert across midnight`() {
        val zone = ZoneId.of("UTC")
        val day = 20_720
        val window = dayWindow(day, zone)
        assertEquals(window.startMs + 495 * 60_000L, localMinuteToEpochMs(day, 495, zone))
        assertTrue(localMinuteToEpochMs(day, -30, zone) < window.startMs)
    }
}
