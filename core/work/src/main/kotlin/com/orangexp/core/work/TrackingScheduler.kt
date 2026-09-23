package com.orangexp.core.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackingScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /**
     * Every 15 minutes (the platform minimum). WorkManager batches this with
     * other work and survives reboots, so no boot receiver is needed.
     */
    fun schedulePeriodic() {
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TrackingWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    /** An immediate pass, e.g. when the app comes to the foreground. */
    fun syncNow() {
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TrackingWorker>().build(),
        )
    }

    private companion object {
        const val PERIODIC_WORK = "orangexp.tracking.periodic"
        const val IMMEDIATE_WORK = "orangexp.tracking.now"
    }
}
