package com.orangexp.core.sensing

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.orangexp.core.engine.ffi.DeviceEvent
import com.orangexp.core.engine.ffi.DeviceEventKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Historical device-usage events. */
interface DeviceUsageSource {
    fun hasAccess(): Boolean

    /** Events in `[fromMs, toMs)` mapped to the engine's vocabulary, oldest first. */
    fun events(fromMs: Long, toMs: Long): List<DeviceEvent>
}

/**
 * Reads the system's own usage history instead of running a background service:
 * zero cost while idle, and nothing is missed while the app is not running.
 *
 * Mapping:
 * - `SCREEN_INTERACTIVE` → screen on (may be a notification; not activity by itself)
 * - `SCREEN_NON_INTERACTIVE` → screen off
 * - `KEYGUARD_HIDDEN` → unlock
 * - `ACTIVITY_RESUMED` of a real app (not the launcher or System UI) → interaction
 */
@Singleton
class UsageStatsDeviceUsageSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceUsageSource {

    private val usageStats = context.getSystemService(UsageStatsManager::class.java)

    private val ignoredPackages: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = context.packageManager
            .queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
        launchers.toSet() + SYSTEM_UI
    }

    override fun hasAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        // The replacement APIs are newer than minSdk; these remain the supported check.
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun events(fromMs: Long, toMs: Long): List<DeviceEvent> {
        if (!hasAccess() || toMs <= fromMs) return emptyList()
        val result = ArrayList<DeviceEvent>()
        val iterator = usageStats.queryEvents(fromMs, toMs)
        val event = UsageEvents.Event()
        while (iterator.hasNextEvent()) {
            iterator.getNextEvent(event)
            val kind = when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> DeviceEventKind.SCREEN_ON
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> DeviceEventKind.SCREEN_OFF
                UsageEvents.Event.KEYGUARD_HIDDEN -> DeviceEventKind.UNLOCK
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    if (event.packageName in ignoredPackages) null else DeviceEventKind.INTERACTION
                else -> null
            } ?: continue
            result += DeviceEvent(event.timeStamp, kind)
        }
        return result
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
    }
}
