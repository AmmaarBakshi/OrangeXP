package com.orangexp.core.common.holstrom

import android.content.Context
import android.content.Intent

/**
 * How widgets, tiles and notifications reach Holstrom without depending on the
 * module that implements it: an action handled by the voice activity, and an
 * extra that opens the main app on the Holstrom tab.
 */
object HolstromIntents {
    /** Opens the voice overlay and starts listening straight away. */
    const val ACTION_LISTEN = "com.orangexp.action.HOLSTROM_LISTEN"

    const val EXTRA_DESTINATION = "com.orangexp.extra.DESTINATION"
    const val DESTINATION_HOLSTROM = "holstrom"

    /** Explicit (package-scoped) intent for the voice overlay. */
    fun listen(context: Context): Intent =
        Intent(ACTION_LISTEN).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The app's launcher activity, opened on the Holstrom tab. */
    fun openHolstrom(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.putExtra(EXTRA_DESTINATION, DESTINATION_HOLSTROM)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
