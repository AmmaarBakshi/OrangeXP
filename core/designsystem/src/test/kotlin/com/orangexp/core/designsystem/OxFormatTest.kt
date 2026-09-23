package com.orangexp.core.designsystem

import com.orangexp.core.designsystem.format.OxFormat
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class OxFormatTest {

    @Test
    fun points() {
        assertEquals("12,482", OxFormat.points(12_482, Locale.US))
        assertEquals("3,642,817", OxFormat.points(3_642_817, Locale.US))
        assertEquals("+320", OxFormat.signedPoints(320, Locale.US))
        assertEquals("−1,500", OxFormat.signedPoints(-1_500, Locale.US))
    }

    @Test
    fun durations() {
        assertEquals("6h 21m", OxFormat.duration(381))
        assertEquals("45m", OxFormat.duration(45))
        assertEquals("0m", OxFormat.duration(-5))
    }

    @Test
    fun clock() {
        assertEquals("06:45", OxFormat.clock(405))
        assertEquals("23:45", OxFormat.clock(-15))
        assertEquals("00:30", OxFormat.clock(1_470))
    }

    @Test
    fun distance() {
        assertEquals("850 m", OxFormat.distance(850.0, Locale.US))
        assertEquals("4.7 km", OxFormat.distance(4_700.25, Locale.US))
    }
}
