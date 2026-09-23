package com.orangexp.feature.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import com.orangexp.core.designsystem.format.OxFormat
import com.orangexp.core.engine.ffi.DayState
import com.orangexp.core.ui.formatShort
import com.orangexp.core.ui.labelRes

/** Today's score, day state and streak. Wide sizes add sleep, walking and study. */
class ScoreWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetDataLoader(context).score()
        provideContent { ScoreContent(data) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { ScoreContent(SAMPLE) }
    }

    private companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)
        val SAMPLE = ScoreData(20_720, 12_482, DayState.ORANGE, 23, 381, 4_700.0, 160)
    }
}

class ScoreWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ScoreWidget()
}

@Composable
private fun ScoreContent(data: ScoreData) {
    val context = LocalContext.current
    val wide = LocalSize.current.width >= 250.dp
    WidgetSurface {
        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                Label(data.day.formatShort())
                Spacer(GlanceModifier.height(2.dp))
                Numeral(OxFormat.points(data.points), size = if (wide) 34.sp else 28.sp)
                Label(context.getString(R.string.widget_points_today))
                Spacer(GlanceModifier.height(8.dp))
                Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                    Box(GlanceModifier.size(9.dp).cornerRadius(5.dp).background(WidgetColors.state(data.state))) {}
                    Spacer(GlanceModifier.width(6.dp))
                    Label(context.getString(data.state.labelRes), color = WidgetColors.state(data.state))
                    Spacer(GlanceModifier.width(10.dp))
                    Body(
                        if (data.streak > 0) "🔥 ${data.streak}" else "🔥 0",
                        color = WidgetColors.primary,
                        bold = true,
                    )
                }
            }
            if (wide) {
                Column {
                    MetricLine(context.getString(R.string.widget_sleep), data.sleepMinutes?.let { OxFormat.duration(it.toLong()) })
                    Spacer(GlanceModifier.height(6.dp))
                    MetricLine(context.getString(R.string.widget_walked), data.distanceMeters?.let { OxFormat.distance(it) })
                    Spacer(GlanceModifier.height(6.dp))
                    MetricLine(context.getString(R.string.widget_study), data.studyMinutes?.let { OxFormat.duration(it.toLong()) })
                }
            }
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String?) {
    Column {
        Label(label)
        Body(value ?: "—", bold = true)
    }
}
