package com.orangexp.core.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.orangexp.core.data.tracking.TrackingCoordinator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The periodic tracking pass. Short-lived by design: it reads what Android
 * already recorded, lets the engine evaluate today and schedules reminders,
 * then exits. No service stays alive between passes.
 */
@HiltWorker
class TrackingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: TrackingCoordinator,
    private val reminders: DepartureReminderScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        coordinator.sync()
        reminders.scheduleToday()
        Result.success()
    } catch (e: Exception) {
        Log.w(TAG, "Tracking pass failed", e)
        if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
    }

    private companion object {
        const val TAG = "TrackingWorker"
        const val MAX_RETRIES = 3
    }
}
