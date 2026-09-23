package com.orangexp.core.ui

import com.orangexp.core.common.time.toLocalDate
import com.orangexp.core.designsystem.component.HeatCell
import com.orangexp.core.engine.ffi.Heatmap
import java.time.format.TextStyle
import java.util.Locale

fun Heatmap.toHeatCells(): List<HeatCell> = cells.map {
    HeatCell(
        key = it.epochDay,
        week = it.weekIndex.toInt(),
        row = it.row.toInt(),
        level = it.level.toInt(),
        isToday = it.isToday,
    )
}

/** Short month names placed above the first week column in which each month begins. */
fun Heatmap.monthLabels(locale: Locale = Locale.getDefault()): Map<Int, String> {
    val labels = LinkedHashMap<Int, String>()
    var lastMonth = -1
    var lastLabelWeek = -MIN_WEEKS_BETWEEN_LABELS
    for (cell in cells) {
        val date = cell.epochDay.toLocalDate()
        val week = cell.weekIndex.toInt()
        if (date.monthValue != lastMonth && date.dayOfMonth <= 7 && week - lastLabelWeek >= MIN_WEEKS_BETWEEN_LABELS) {
            labels[week] = date.month.getDisplayName(TextStyle.SHORT, locale)
            lastLabelWeek = week
        }
        lastMonth = date.monthValue
    }
    return labels
}

private const val MIN_WEEKS_BETWEEN_LABELS = 3
