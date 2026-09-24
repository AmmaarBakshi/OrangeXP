package com.orangexp.feature.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import com.orangexp.core.common.time.dayOfWeek
import com.orangexp.core.designsystem.format.OxFormat
import java.time.format.TextStyle
import java.util.Locale

/** Today's classes (or the next day's once today is done) with the leave-home time. */
class TimetableWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetDataLoader(context).timetable()
        provideContent { TimetableContent(data) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { TimetableContent(SAMPLE) }
    }

    private companion object {
        val SAMPLE = TimetableData(
            day = 20_720,
            isToday = true,
            nowMinute = 10 * 60 + 40,
            classes = listOf(
                ClassRow(9 * 60 + 15, 10 * 60 + 15, "Linear Algebra", "Room 101"),
                ClassRow(10 * 60 + 15, 12 * 60 + 15, "Physics Lab", "Lab 2"),
                ClassRow(13 * 60 + 15, 14 * 60 + 15, "Signals and Systems", "Room 204"),
            ),
            leaveMinute = 8 * 60,
        )
    }
}

class TimetableWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TimetableWidget()
}

@Composable
private fun TimetableContent(data: TimetableData) {
    val context = LocalContext.current
    val dayName = data.day.dayOfWeek().getDisplayName(TextStyle.FULL, Locale.getDefault())
    WidgetSurface {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                Label(if (data.isToday) context.getString(R.string.widget_today_on, dayName) else dayName)
            }
            data.leaveMinute?.takeIf { data.classes.isNotEmpty() }?.let { leave ->
                Row(
                    GlanceModifier.background(WidgetColors.surface).cornerRadius(12.dp).padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Body(context.getString(R.string.widget_leave_at, OxFormat.clock(leave)), color = WidgetColors.primary, size = 12.sp, bold = true)
                }
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        if (data.classes.isEmpty()) {
            Body(context.getString(R.string.widget_no_classes), color = WidgetColors.subtle)
        } else {
            LazyColumn {
                items(data.classes) { item -> ClassLine(item, data) }
            }
        }
    }
}

@Composable
private fun ClassLine(item: ClassRow, data: TimetableData) {
    val past = data.isToday && item.endMinute <= data.nowMinute
    val current = data.isToday && item.startMinute <= data.nowMinute && data.nowMinute < item.endMinute
    val titleColor = when {
        current -> WidgetColors.primary
        past -> WidgetColors.subtle
        else -> WidgetColors.onBackground
    }
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Column(GlanceModifier.width(52.dp)) {
            Numeral(OxFormat.clock(item.startMinute), size = 14.sp, color = titleColor)
        }
        Column(GlanceModifier.defaultWeight()) {
            Body(item.title, color = titleColor, bold = current)
            val until = LocalContext.current.getString(R.string.widget_until, OxFormat.clock(item.endMinute))
            Body(
                if (item.location.isNotBlank()) "${item.location} · $until" else until,
                color = WidgetColors.subtle,
                size = 11.sp,
            )
        }
    }
}
