package com.orangexp.feature.holstrom.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.orangexp.core.common.holstrom.HolstromIntents
import com.orangexp.core.data.repository.DataChangeListener
import com.orangexp.core.data.repository.ReminderRepository
import com.orangexp.core.engine.ffi.CommandKind
import com.orangexp.feature.holstrom.R
import com.orangexp.feature.holstrom.brain.HolstromAnswers
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.first
import javax.inject.Inject

private object HolstromWidgetColors {
    val background = ColorProvider(day = Color(0xFFFBF8F5), night = Color(0xFF161412))
    val onBackground = ColorProvider(day = Color(0xFF0E0D0C), night = Color(0xFFEDE7E1))
    val subtle = ColorProvider(day = Color(0xFF6F675E), night = Color(0xFFB3AAA0))
    val primary = ColorProvider(day = Color(0xFFD65212), night = Color(0xFFF2661B))
    val onPrimary = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF1C1A17))
}

/** The next things Holstrom will remind you of. */
private data class NextUp(val lines: List<String>)

/**
 * A mic on the home screen: tap it and start talking. Wider sizes also show
 * what's next. Android widgets only receive taps, so talking starts on tap and
 * ends by itself after a pause.
 */
class HolstromWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val next = load(context)
        provideContent { Content(next) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { Content(NextUp(listOf("Submit assignment, due 3 Oct", "Call mom, today at 18:00"))) }
    }

    private suspend fun load(context: Context): NextUp {
        val entry = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val reminders = runCatching { entry.reminders().active.first() }.getOrDefault(emptyList())
            .filter { it.kind != CommandKind.DEVICE_ACTION }
        return NextUp(reminders.take(2).map { entry.answers().describe(it) })
    }

    private companion object {
        val SMALL = DpSize(80.dp, 80.dp)
        val WIDE = DpSize(220.dp, 80.dp)
    }
}

@Composable
private fun Content(next: NextUp) {
    val context = LocalContext.current
    val wide = LocalSize.current.width >= 220.dp
    val listen = actionStartActivity(HolstromIntents.listen(context))
    val open = HolstromIntents.openHolstrom(context)?.let { actionStartActivity(it) }

    Row(
        GlanceModifier.fillMaxSize().background(HolstromWidgetColors.background).cornerRadius(28.dp).padding(10.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = if (wide) Alignment.Horizontal.Start else Alignment.Horizontal.CenterHorizontally,
    ) {
        Box(
            GlanceModifier.size(56.dp).cornerRadius(28.dp).background(HolstromWidgetColors.primary).clickable(listen),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_holstrom_mic),
                contentDescription = context.getString(R.string.shortcut_talk_long),
                modifier = GlanceModifier.size(28.dp),
                colorFilter = ColorFilter.tint(HolstromWidgetColors.onPrimary),
            )
        }
        if (wide) {
            Spacer(GlanceModifier.width(12.dp))
            var column = GlanceModifier.defaultWeight()
            if (open != null) column = column.clickable(open)
            Column(column) {
                Text(
                    context.getString(R.string.holstrom).uppercase(),
                    style = TextStyle(color = HolstromWidgetColors.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(2.dp))
                val lines = next.lines.ifEmpty { listOf(context.getString(R.string.widget_nothing_next)) }
                lines.forEachIndexed { i, line ->
                    Text(
                        line,
                        style = TextStyle(
                            color = if (i == 0) HolstromWidgetColors.onBackground else HolstromWidgetColors.subtle,
                            fontSize = if (i == 0) 14.sp else 12.sp,
                            fontWeight = if (i == 0) FontWeight.Medium else FontWeight.Normal,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

class HolstromWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = HolstromWidget()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetEntryPoint {
    fun reminders(): ReminderRepository
    fun answers(): HolstromAnswers
}

/** Keeps "what's next" current whenever reminders change. */
internal class HolstromWidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
) : DataChangeListener {
    override suspend fun onDataChanged() = HolstromWidget().updateAll(context)
}

@Module
@InstallIn(SingletonComponent::class)
internal interface HolstromWidgetModule {
    @Binds
    @IntoSet
    fun refresher(impl: HolstromWidgetRefresher): DataChangeListener
}
