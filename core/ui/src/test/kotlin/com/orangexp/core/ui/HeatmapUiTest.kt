package com.orangexp.core.ui

import com.orangexp.core.engine.ffi.Heatmap
import com.orangexp.core.engine.ffi.HeatmapCell
import java.time.LocalDate
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class HeatmapUiTest {

    private fun heatmap(from: LocalDate, days: Int): Heatmap {
        val start = from.toEpochDay().toInt()
        val offset = from.dayOfWeek.value - 1
        val cells = (0 until days).map { i ->
            HeatmapCell(
                epochDay = start + i,
                weekIndex = ((i + offset) / 7).toUInt(),
                row = ((i + offset) % 7).toUInt(),
                level = (i % 6).toUByte(),
                points = i.toLong(),
                state = null,
                isToday = i == days - 1,
            )
        }
        return Heatmap(cells, cells.last().weekIndex + 1u, listOf(1, 2, 3, 4, 5), days.toLong())
    }

    @Test
    fun `cells keep grid positions and levels`() {
        val cells = heatmap(LocalDate.of(2026, 9, 24), 10).toHeatCells()
        assertEquals(3, cells.first().row)
        assertEquals(0, cells.first().week)
        assertEquals(1, cells[4].week)
        assertEquals(true, cells.last().isToday)
    }

    @Test
    fun `month labels appear where months begin`() {
        val labels = heatmap(LocalDate.of(2026, 7, 1), 92).monthLabels(Locale.US)
        assertEquals(listOf("Jul", "Aug", "Sep"), labels.values.toList())
    }
}
