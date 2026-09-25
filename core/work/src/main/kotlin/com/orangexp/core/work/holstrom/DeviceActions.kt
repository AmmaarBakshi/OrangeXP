package com.orangexp.core.work.holstrom

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import com.orangexp.core.common.time.TimeSource
import com.orangexp.core.engine.ffi.DeviceAction
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalTime
import java.time.ZoneId
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

enum class ActionResult {
    DONE,

    /** Changing the ringer or Do Not Disturb needs "Do Not Disturb access". */
    NEEDS_POLICY_ACCESS,

    /** The clock app must be opened from the foreground (alarms and timers). */
    NEEDS_FOREGROUND,
    UNSUPPORTED,
    FAILED,
}

/**
 * Carries out Holstrom's phone actions. Ringer and Do Not Disturb changes work
 * from the background (alarms fire while the phone sleeps); alarms and timers
 * are handed to the clock app, which then owns them.
 */
@Singleton
class DeviceActions @Inject constructor(
    @ApplicationContext private val context: Context,
    private val time: TimeSource,
) {
    private val audio get() = context.getSystemService(AudioManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    fun hasPolicyAccess(): Boolean = notifications.isNotificationPolicyAccessGranted

    fun policyAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * @param atMs when an alarm should ring (for [DeviceAction.SET_ALARM]).
     * @param foreground whether an activity of the app is visible, so the clock app may be opened.
     */
    fun perform(action: DeviceAction, atMs: Long? = null, durationMinutes: Int? = null, foreground: Boolean = false): ActionResult =
        try {
            when (action) {
                DeviceAction.SILENT -> ringer(AudioManager.RINGER_MODE_SILENT)
                DeviceAction.VIBRATE -> ringer(AudioManager.RINGER_MODE_VIBRATE)
                DeviceAction.RING -> ringer(AudioManager.RINGER_MODE_NORMAL)
                DeviceAction.DND_ON -> interruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                DeviceAction.DND_OFF -> interruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                DeviceAction.FLASHLIGHT_ON -> torch(true)
                DeviceAction.FLASHLIGHT_OFF -> torch(false)
                DeviceAction.SET_ALARM -> if (foreground) setAlarm(atMs) else ActionResult.NEEDS_FOREGROUND
                DeviceAction.SET_TIMER -> if (foreground) setTimer(durationMinutes) else ActionResult.NEEDS_FOREGROUND
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed: $action", e)
            ActionResult.NEEDS_POLICY_ACCESS
        } catch (e: Exception) {
            Log.w(TAG, "Failed: $action", e)
            ActionResult.FAILED
        }

    private fun ringer(mode: Int): ActionResult {
        // Leaving or entering silent toggles Do Not Disturb on modern Android, which needs access.
        if (!hasPolicyAccess() && (mode == AudioManager.RINGER_MODE_SILENT || audio.ringerMode == AudioManager.RINGER_MODE_SILENT)) {
            return ActionResult.NEEDS_POLICY_ACCESS
        }
        audio.ringerMode = mode
        return ActionResult.DONE
    }

    private fun interruptionFilter(filter: Int): ActionResult {
        if (!hasPolicyAccess()) return ActionResult.NEEDS_POLICY_ACCESS
        notifications.setInterruptionFilter(filter)
        return ActionResult.DONE
    }

    private fun torch(on: Boolean): ActionResult {
        val camera = context.getSystemService(CameraManager::class.java)
        val id = camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ActionResult.UNSUPPORTED
        camera.setTorchMode(id, on)
        return ActionResult.DONE
    }

    private fun setAlarm(atMs: Long?): ActionResult {
        val local = LocalTime.ofInstant(Instant.ofEpochMilli(atMs ?: return ActionResult.FAILED), zone())
        return startClockApp(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, local.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, local.minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Holstrom")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
        )
    }

    private fun setTimer(minutes: Int?): ActionResult {
        val seconds = (minutes ?: return ActionResult.FAILED).coerceIn(1, 24 * 60) * 60
        return startClockApp(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Holstrom")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
        )
    }

    private fun startClockApp(intent: Intent): ActionResult = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ActionResult.DONE
    } catch (e: ActivityNotFoundException) {
        ActionResult.UNSUPPORTED
    }

    private fun zone(): ZoneId = time.zone()

    private companion object {
        const val TAG = "DeviceActions"
    }
}
