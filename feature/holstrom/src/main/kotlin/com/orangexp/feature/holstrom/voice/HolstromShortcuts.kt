package com.orangexp.feature.holstrom.voice

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.orangexp.feature.holstrom.R

/** Long-press the launcher icon → "Talk to Holstrom". Published at runtime so debug builds work too. */
object HolstromShortcuts {
    fun publish(context: Context) {
        val shortcut = ShortcutInfoCompat.Builder(context, "holstrom_talk")
            .setShortLabel(context.getString(R.string.shortcut_talk_short))
            .setLongLabel(context.getString(R.string.shortcut_talk_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_holstrom_shortcut))
            .setIntent(Intent(context, VoiceCommandActivity::class.java).setAction(Intent.ACTION_VIEW))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }
}
