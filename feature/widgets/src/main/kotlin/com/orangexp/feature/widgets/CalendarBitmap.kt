package com.orangexp.feature.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Draws the contribution calendar into a bitmap. Widgets can't draw freely and
 * limit how many views a layout may hold, so the grid is rendered as one image
 * showing as many recent weeks as fit.
 */
internal object CalendarBitmap {

    /** Number of most recent weeks that fit [widthPx] when each of the 7 rows gets [heightPx] / 7. */
    fun weeksThatFit(widthPx: Int, heightPx: Int): Int {
        val pitch = heightPx / 7f
        return if (pitch <= 0f) 0 else (widthPx / pitch).toInt()
    }

    fun render(
        cells: List<CalendarCell>,
        weekCount: Int,
        widthPx: Int,
        heightPx: Int,
        heat: IntArray,
        todayOutline: Int,
    ): Bitmap {
        val weeks = weeksThatFit(widthPx, heightPx).coerceIn(1, weekCount.coerceAtLeast(1))
        val pitch = heightPx / 7f
        val gap = pitch * 0.18f
        val cell = pitch - gap
        val firstWeek = weekCount - weeks
        // Right-align so the current week always sits at the edge.
        val offsetX = widthPx - weeks * pitch + gap
        val bitmap = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (cell * 0.14f).coerceAtLeast(2f)
            color = todayOutline
        }
        val radius = cell * 0.24f
        for (c in cells) {
            if (c.week < firstWeek) continue
            val left = offsetX + (c.week - firstWeek) * pitch
            val top = c.row * pitch + gap / 2
            val rect = RectF(left, top, left + cell, top + cell)
            fill.color = heat[c.level.coerceIn(0, heat.lastIndex)]
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (c.isToday) {
                rect.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
                canvas.drawRoundRect(rect, radius, radius, stroke)
            }
        }
        return bitmap
    }
}
