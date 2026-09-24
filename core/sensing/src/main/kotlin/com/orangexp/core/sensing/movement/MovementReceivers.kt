package com.orangexp.core.sensing.movement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.LocationResult
import com.orangexp.core.engine.ffi.ActivityHint
import com.orangexp.core.engine.ffi.LocationFix
import com.orangexp.core.sensing.StepCounterSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Runs [block] off the main thread while keeping the broadcast alive until it finishes. */
private fun BroadcastReceiver.handleAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            block()
        } catch (e: Exception) {
            Log.w("MovementReceiver", "Failed to handle movement update", e)
        } finally {
            pending.finish()
        }
    }
}

/** Start/stop of walking, riding and standing still, reported by activity recognition. */
@AndroidEntryPoint
class ActivityTransitionReceiver : BroadcastReceiver() {
    @Inject lateinit var sink: MovementSink
    @Inject lateinit var tracker: MovementTracker

    override fun onReceive(context: Context, intent: Intent) {
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        // Event times are on the elapsed-realtime clock; convert to wall-clock time.
        val offsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val transitions = result.transitionEvents.map { event ->
            (event.elapsedRealTimeNanos / 1_000_000 + offsetMs) to PlayServicesMovementTracker.hintOf(event.activityType)
        }
        handleAsync {
            sink.onTransitions(transitions)
            val latest = transitions.maxByOrNull { it.first }?.second ?: return@handleAsync
            if (latest == ActivityHint.STILL) tracker.stopLocationUpdates() else tracker.startLocationUpdates()
        }
    }
}

/** GPS fixes delivered while moving, each paired with a step-counter reading. */
@AndroidEntryPoint
class LocationUpdateReceiver : BroadcastReceiver() {
    @Inject lateinit var sink: MovementSink
    @Inject lateinit var steps: StepCounterSource

    override fun onReceive(context: Context, intent: Intent) {
        val result = LocationResult.extractResult(intent) ?: return
        val fixes = result.locations.map {
            LocationFix(timestampMs = it.time, latitude = it.latitude, longitude = it.longitude, accuracyM = it.accuracy.toDouble())
        }
        handleAsync {
            sink.onLocations(fixes, steps.readCumulativeSteps(), System.currentTimeMillis())
        }
    }
}
