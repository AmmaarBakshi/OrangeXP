package com.orangexp.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.orangexp.core.common.widgets.HomeWidgetKind
import com.orangexp.core.common.widgets.HomeWidgets
import com.orangexp.core.data.repository.DataChangeListener
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject

object OrangeXpWidgets {

    suspend fun updateAll(context: Context) {
        ScoreWidget().updateAll(context)
        StreakWidget().updateAll(context)
        TimetableWidget().updateAll(context)
        StatsWidget().updateAll(context)
    }

    /**
     * Publishes generated previews for the launcher's widget picker (Android 15+).
     * The platform rate-limits this, so call it once per app version.
     */
    suspend fun publishPreviews(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val manager = GlanceAppWidgetManager(context)
        for (receiver in listOf(
            ScoreWidgetReceiver::class,
            StreakWidgetReceiver::class,
            TimetableWidgetReceiver::class,
            StatsWidgetReceiver::class,
        )) {
            try {
                manager.setWidgetPreviews(receiver)
            } catch (e: Exception) {
                Log.w("OrangeXpWidgets", "Could not publish preview for ${receiver.simpleName}", e)
            }
        }
    }
}

/** Refreshes every placed widget whenever a day is re-evaluated or the plan changes. */
internal class WidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
) : DataChangeListener {
    override suspend fun onDataChanged() = OrangeXpWidgets.updateAll(context)
}

internal class GlanceHomeWidgets @Inject constructor(
    @ApplicationContext private val context: Context,
) : HomeWidgets {
    override fun canPin(): Boolean =
        context.getSystemService(AppWidgetManager::class.java)?.isRequestPinAppWidgetSupported == true

    override suspend fun requestPin(kind: HomeWidgetKind) {
        val receiver = when (kind) {
            HomeWidgetKind.SCORE -> ScoreWidgetReceiver::class.java
            HomeWidgetKind.STREAK -> StreakWidgetReceiver::class.java
            HomeWidgetKind.TIMETABLE -> TimetableWidgetReceiver::class.java
            HomeWidgetKind.STATS -> StatsWidgetReceiver::class.java
        }
        GlanceAppWidgetManager(context).requestPinGlanceAppWidget(receiver)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface WidgetModule {
    @Binds
    @IntoSet
    fun refresher(impl: WidgetRefresher): DataChangeListener

    @Binds
    fun homeWidgets(impl: GlanceHomeWidgets): HomeWidgets
}
