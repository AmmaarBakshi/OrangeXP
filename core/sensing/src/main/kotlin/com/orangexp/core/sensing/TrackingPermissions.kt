package com.orangexp.core.sensing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class TrackingPermissions(
    val usageAccess: Boolean,
    val activityRecognition: Boolean,
    val stepSensorAvailable: Boolean,
    val notifications: Boolean,
    val ignoringBatteryOptimizations: Boolean,
) {
    val trackingReady: Boolean get() = usageAccess && (activityRecognition || !stepSensorAvailable)
}

@Singleton
class TrackingPermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usage: DeviceUsageSource,
    private val steps: StepCounterSource,
) {
    fun current(): TrackingPermissions = TrackingPermissions(
        usageAccess = usage.hasAccess(),
        activityRecognition = steps.hasPermission(),
        stepSensorAvailable = steps.isAvailable(),
        notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED,
        ignoringBatteryOptimizations = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true,
    )

    fun usageAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
