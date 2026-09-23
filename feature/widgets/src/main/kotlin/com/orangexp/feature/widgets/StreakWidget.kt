package com.orangexp.feature.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width

/** The streak and the contribution calendar: as many recent weeks as the widget is wide. */
class StreakWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetDataLoader(context).streak()
        provideContent { StreakContent(data) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { StreakContent(sample()) }
    }

    private fun sample(): StreakData {
        val weeks = 26
        val cells = (0 until weeks * 7 - 3).map { i ->
            CalendarCell(i / 7, i % 7, level = listOf(0, 2, 3, 5, 4, 1, 3, 4, 5, 2)[(i * 7 + i / 5) % 10], isToday = i == weeks * 7 - 4)
        }
        return StreakData(current = 23, longest = 41, todayQualifies = true, cells = cells, weekCount = weeks)
    }
}

class StreakWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = StreakWidget()
}

@Composable
private fun StreakContent(data: StreakData) {
    val context = LocalContext.current
    val size = LocalSize.current
    val density = context.resources.displayMetrics.density
    // Space left for the calendar after the surface padding and the header row.
    val widthPx = ((size.width - 32.dp).value * density).toInt().coerceAtLeast(1)
    val heightPx = ((size.height - 28.dp - HEADER_HEIGHT).value * density).toInt().coerceAtLeast(7)

    WidgetSurface {
        Row(GlanceModifier.fillMaxWidth().height(HEADER_HEIGHT - 8.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Numeral("🔥 ${data.current}", size = 22.sp, color = WidgetColors.primary)
            Spacer(GlanceModifier.width(8.dp))
            Column(GlanceModifier.defaultWeight()) {
                Label(context.getString(R.string.widget_day_streak))
            }
            Label(context.getString(R.string.widget_best, data.longest))
        }
        Spacer(GlanceModifier.height(8.dp))
        Image(
            provider = ImageProvider(
                CalendarBitmap.render(
                    cells = data.cells,
                    weekCount = data.weekCount,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    heat = WidgetColors.heat(context),
                    todayOutline = WidgetColors.todayOutline(context),
                ),
            ),
            contentDescription = context.getString(R.string.widget_streak_description),
            contentScale = ContentScale.Fit,
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
        )
    }
}

private val HEADER_HEIGHT = 40.dp
