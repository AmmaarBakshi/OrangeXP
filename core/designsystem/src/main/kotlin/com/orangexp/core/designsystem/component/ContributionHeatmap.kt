package com.orangexp.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.orangexp.core.designsystem.theme.OxTheme

/** One day square. [level] is 0 (nothing) to 5 (very high). */
@Immutable
data class HeatCell(
    val key: Int,
    val week: Int,
    val row: Int,
    val level: Int,
    val isToday: Boolean,
)

/**
 * The contribution graph: one square per day, one column per week, intensity in
 * shades of orange. Drawn on a single Canvas so a full year stays cheap to render.
 * Starts scrolled to the most recent week.
 */
@Composable
fun ContributionHeatmap(
    cells: List<HeatCell>,
    weekCount: Int,
    modifier: Modifier = Modifier,
    monthLabels: Map<Int, String> = emptyMap(),
    rowLabels: List<String?> = emptyList(),
    selectedKey: Int? = null,
    cellSize: Dp = 13.dp,
    gap: Dp = 3.dp,
    contentDescription: String = "",
    onCellClick: ((Int) -> Unit)? = null,
) {
    val heat = OxTheme.colors.heat
    val selectionColor = MaterialTheme.colorScheme.onBackground
    val todayColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val cellPx = with(density) { cellSize.toPx() }
    val gapPx = with(density) { gap.toPx() }
    val pitch = cellPx + gapPx
    val byPosition = remember(cells) { cells.associateBy { it.week to it.row } }
    val scroll = rememberScrollState()
    LaunchedEffect(weekCount) { scroll.scrollTo(scroll.maxValue) }

    Row(modifier.semantics { this.contentDescription = contentDescription }) {
        if (rowLabels.isNotEmpty()) {
            Column(Modifier.padding(top = cellSize + gap, end = 6.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
                rowLabels.take(7).forEach { label ->
                    Box(Modifier.height(cellSize), contentAlignment = Alignment.CenterStart) {
                        if (label != null) {
                            Text(label, style = MaterialTheme.typography.labelSmall, color = OxTheme.colors.subtle)
                        }
                    }
                }
            }
        }
        Column(Modifier.horizontalScroll(scroll)) {
            Box(Modifier.height(cellSize + gap).width(pitch.toDp(density) * weekCount)) {
                monthLabels.forEach { (week, label) ->
                    Text(
                        text = label,
                        modifier = Modifier.padding(start = (pitch * week).toDp(density)),
                        style = MaterialTheme.typography.labelSmall,
                        color = OxTheme.colors.subtle,
                        maxLines = 1,
                    )
                }
            }
            Canvas(
                Modifier
                    .size(width = pitch.toDp(density) * weekCount, height = pitch.toDp(density) * 7)
                    .pointerInput(byPosition, onCellClick) {
                        if (onCellClick == null) return@pointerInput
                        detectTapGestures { offset ->
                            val week = (offset.x / pitch).toInt()
                            val row = (offset.y / pitch).toInt()
                            byPosition[week to row]?.let { onCellClick(it.key) }
                        }
                    },
            ) {
                val corner = CornerRadius(cellPx * 0.22f)
                val cellSizePx = Size(cellPx, cellPx)
                for (cell in cells) {
                    val topLeft = Offset(cell.week * pitch, cell.row * pitch)
                    drawRoundRect(heat[cell.level.coerceIn(0, heat.lastIndex)], topLeft, cellSizePx, corner)
                    val outline = when {
                        cell.key == selectedKey -> selectionColor
                        cell.isToday -> todayColor
                        else -> null
                    }
                    if (outline != null) {
                        drawRoundRect(outline, topLeft, cellSizePx, corner, style = Stroke(width = 1.5.dp.toPx()))
                    }
                }
            }
        }
    }
}

/** "Less ▢▢▢▢▢▢ More" */
@Composable
fun HeatmapLegend(lessLabel: String, moreLabel: String, modifier: Modifier = Modifier) {
    val heat = OxTheme.colors.heat
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(lessLabel, style = MaterialTheme.typography.labelSmall, color = OxTheme.colors.subtle)
        heat.forEach { color ->
            Canvas(Modifier.size(11.dp)) { drawRoundRect(color, cornerRadius = CornerRadius(size.minDimension * 0.22f)) }
        }
        Text(moreLabel, style = MaterialTheme.typography.labelSmall, color = OxTheme.colors.subtle)
    }
}

private fun Float.toDp(density: androidx.compose.ui.unit.Density): Dp = with(density) { this@toDp.toDp() }
