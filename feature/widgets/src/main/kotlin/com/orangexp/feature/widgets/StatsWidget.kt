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
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import com.orangexp.core.designsystem.format.OxFormat
import com.orangexp.core.engine.ffi.XpPool

/** Lifetime XP, the four XP pools and recent consistency. */
class StatsWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetDataLoader(context).stats()
        provideContent { StatsContent(data) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { StatsContent(SAMPLE) }
    }

    private companion object {
        val COMPACT = DpSize(250.dp, 110.dp)
        val TALL = DpSize(250.dp, 180.dp)
        val SAMPLE = StatsData(
            lifetime = 3_642_817,
            pools = mapOf(XpPool.STUDY to 842_190, XpPool.PHYSICAL to 391_204, XpPool.SLEEP to 502_601, XpPool.DISCIPLINE to 1_104_822),
            average7 = 11_904,
            consistency = 87,
            bestDay = 18_310,
            daysTracked = 312,
        )
    }
}

class StatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = StatsWidget()
}

@Composable
private fun StatsContent(data: StatsData) {
    val context = LocalContext.current
    val tall = LocalSize.current.height >= 180.dp
    WidgetSurface {
        Label(context.getString(R.string.widget_lifetime_xp))
        Numeral(OxFormat.points(data.lifetime), size = if (tall) 32.sp else 26.sp, color = WidgetColors.primary)
        Spacer(GlanceModifier.height(if (tall) 10.dp else 6.dp))
        if (tall) {
            Row(GlanceModifier.fillMaxWidth()) {
                Stat(context.getString(R.string.widget_pool_study), OxFormat.points(data.pools[XpPool.STUDY] ?: 0))
                Stat(context.getString(R.string.widget_pool_physical), OxFormat.points(data.pools[XpPool.PHYSICAL] ?: 0))
            }
            Spacer(GlanceModifier.height(6.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                Stat(context.getString(R.string.widget_pool_sleep), OxFormat.points(data.pools[XpPool.SLEEP] ?: 0))
                Stat(context.getString(R.string.widget_pool_discipline), OxFormat.points(data.pools[XpPool.DISCIPLINE] ?: 0))
            }
            Spacer(GlanceModifier.height(10.dp))
        }
        Row(GlanceModifier.fillMaxWidth()) {
            Stat(context.getString(R.string.widget_avg7), OxFormat.points(data.average7))
            Stat(context.getString(R.string.widget_consistency), "${data.consistency}%")
            Stat(context.getString(R.string.widget_best_day), OxFormat.points(data.bestDay))
        }
    }
}

@Composable
private fun androidx.glance.layout.RowScope.Stat(label: String, value: String) {
    Column(GlanceModifier.defaultWeight()) {
        Label(label)
        Numeral(value, size = 15.sp)
    }
}
