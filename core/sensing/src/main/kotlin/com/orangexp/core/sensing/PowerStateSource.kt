package com.orangexp.core.sensing

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface PowerStateSource {
    fun isCharging(): Boolean
}

/** Reads the sticky battery broadcast; registers nothing. */
@Singleton
class BatteryPowerStateSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : PowerStateSource {
    override fun isCharging(): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }
}
