package com.orangexp.core.sensing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** The hardware step counter: cumulative steps since the last reboot. */
interface StepCounterSource {
    fun isAvailable(): Boolean
    fun hasPermission(): Boolean

    /** Current cumulative count, or `null` if unavailable within a short timeout. */
    suspend fun readCumulativeSteps(): Long?
}

/**
 * Reads `TYPE_STEP_COUNTER` once and unregisters immediately. The sensor counts
 * in low-power hardware regardless of listeners, so sampling it periodically
 * costs almost nothing compared to keeping a listener registered.
 */
@Singleton
class HardwareStepCounterSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : StepCounterSource {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? by lazy { sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }

    override fun isAvailable(): Boolean = sensor != null

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun readCumulativeSteps(): Long? {
        val stepSensor = sensor ?: return null
        if (!hasPermission()) return null
        return withTimeoutOrNull(READ_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sensorManager.unregisterListener(this)
                        if (continuation.isActive) continuation.resume(event.values[0].toLong())
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensorManager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
                continuation.invokeOnCancellation { sensorManager.unregisterListener(listener) }
            }
        }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 10_000L
    }
}
