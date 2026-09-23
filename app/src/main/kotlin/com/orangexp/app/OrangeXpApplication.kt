package com.orangexp.app

import android.app.Application
import androidx.core.content.edit
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.orangexp.core.common.coroutines.ApplicationScope
import com.orangexp.core.work.NotificationChannels
import com.orangexp.core.work.TrackingScheduler
import com.orangexp.feature.widgets.OrangeXpWidgets
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class OrangeXpApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var trackingScheduler: TrackingScheduler

    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
        trackingScheduler.schedulePeriodic()
        publishWidgetPreviewsOncePerVersion()
    }

    /** Widget-picker previews are rate-limited by the platform; refresh them only after an update. */
    private fun publishWidgetPreviewsOncePerVersion() {
        val prefs = getSharedPreferences("app_state", MODE_PRIVATE)
        if (prefs.getInt(KEY_PREVIEWS_VERSION, -1) == BuildConfig.VERSION_CODE) return
        applicationScope.launch {
            OrangeXpWidgets.publishPreviews(this@OrangeXpApplication)
            prefs.edit { putInt(KEY_PREVIEWS_VERSION, BuildConfig.VERSION_CODE) }
        }
    }

    private companion object {
        const val KEY_PREVIEWS_VERSION = "widget_previews_version"
    }
}
