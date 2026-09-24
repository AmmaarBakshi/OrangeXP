package com.orangexp.core.sensing.movement

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.orangexp.core.engine.ffi.ActivityHint
import com.orangexp.core.engine.ffi.LocationFix
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Receives what the movement receivers observe. Implemented by the data layer,
 * which stores it; the engine interprets it when a day is evaluated.
 */
interface MovementSink {
    suspend fun onTransitions(transitions: List<Pair<Long, ActivityHint>>)
    suspend fun onLocations(fixes: List<LocationFix>, cumulativeSteps: Long?, atMs: Long)
}

/**
 * Battery-conscious movement capture:
 *
 * - Android's activity recognition (low-power sensors, batched by Play services)
 *   reports when you start walking, riding or stop;
 * - only while you are moving, GPS fixes are requested every ~20 s with a hard
 *   cap on duration, and each fix is paired with a step-counter reading;
 * - when activity recognition reports "still", GPS stops.
 */
interface MovementTracker {
    /** Play services are present (true on Samsung and most phones). */
    fun isSupported(): Boolean

    fun missingPermissions(): List<String>

    suspend fun start()
    suspend fun stop()

    fun startLocationUpdates()
    fun stopLocationUpdates()
}

@Singleton
class PlayServicesMovementTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) : MovementTracker {

    override fun isSupported(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    override fun missingPermissions(): List<String> = buildList {
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission") // Checked by missingPermissions().
    override suspend fun start() {
        if (!isSupported() || missingPermissions().isNotEmpty()) return
        val transitions = MOVING_TYPES.plus(DetectedActivity.STILL).map {
            ActivityTransition.Builder()
                .setActivityType(it)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        }
        ActivityRecognition.getClient(context)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), transitionIntent())
            .await()
    }

    @SuppressLint("MissingPermission")
    override suspend fun stop() {
        if (!isSupported()) return
        stopLocationUpdates()
        runCatching { ActivityRecognition.getClient(context).removeActivityTransitionUpdates(transitionIntent()).await() }
    }

    @SuppressLint("MissingPermission")
    override fun startLocationUpdates() {
        if (missingPermissions().isNotEmpty()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, FIX_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FIX_INTERVAL_MS / 2)
            // Safety net: never keep GPS on for hours if "still" is never reported.
            .setDurationMillis(MAX_TRACKING_MS)
            .build()
        LocationServices.getFusedLocationProviderClient(context).requestLocationUpdates(request, locationIntent())
    }

    override fun stopLocationUpdates() {
        LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(locationIntent())
    }

    private fun transitionIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_TRANSITIONS,
        Intent(context, ActivityTransitionReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun locationIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_LOCATIONS,
        Intent(context, LocationUpdateReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    internal companion object {
        const val FIX_INTERVAL_MS = 20_000L
        const val MAX_TRACKING_MS = 3 * 3_600_000L
        private const val REQUEST_TRANSITIONS = 7_001
        private const val REQUEST_LOCATIONS = 7_002

        val MOVING_TYPES = listOf(
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.IN_VEHICLE,
        )

        fun hintOf(activityType: Int): ActivityHint = when (activityType) {
            DetectedActivity.STILL -> ActivityHint.STILL
            DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> ActivityHint.WALKING
            DetectedActivity.RUNNING -> ActivityHint.RUNNING
            DetectedActivity.ON_BICYCLE -> ActivityHint.ON_BICYCLE
            DetectedActivity.IN_VEHICLE -> ActivityHint.IN_VEHICLE
            else -> ActivityHint.UNKNOWN
        }
    }
}
