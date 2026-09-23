package com.orangexp.feature.widgets

import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarBitmapTest {
    @Test
    fun `weeks that fit follow the widget's aspect ratio`() {
        // 7 rows in 140 px → 20 px pitch → 600 px fits 30 weeks.
        assertEquals(30, CalendarBitmap.weeksThatFit(widthPx = 600, heightPx = 140))
        assertEquals(0, CalendarBitmap.weeksThatFit(widthPx = 600, heightPx = 0))
    }
}
