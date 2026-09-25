package com.orangexp.core.data.repository

import com.orangexp.core.data.model.HolstromSettings
import com.orangexp.core.database.dao.KeyValueDao
import com.orangexp.core.database.model.KeyValueEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface HolstromSettingsRepository {
    val settings: Flow<HolstromSettings>

    suspend fun current(): HolstromSettings

    suspend fun update(transform: (HolstromSettings) -> HolstromSettings)
}

/** Stored as `key=value` lines in one key-value row; unknown or missing keys fall back to defaults. */
@Singleton
internal class OfflineHolstromSettingsRepository @Inject constructor(
    private val keyValues: KeyValueDao,
) : HolstromSettingsRepository {

    override val settings: Flow<HolstromSettings> =
        keyValues.observe(StorageKeys.HOLSTROM_SETTINGS).distinctUntilChanged().map(::decode)

    override suspend fun current(): HolstromSettings = settings.first()

    override suspend fun update(transform: (HolstromSettings) -> HolstromSettings) {
        val updated = transform(current()).let {
            it.copy(
                nudgeMinute = it.nudgeMinute.coerceIn(0, 1_439),
                defaultMinute = it.defaultMinute.coerceIn(0, 1_439),
                defaultDelayMinutes = it.defaultDelayMinutes.coerceIn(1, 24 * 60),
            )
        }
        keyValues.put(KeyValueEntity(StorageKeys.HOLSTROM_SETTINGS, encode(updated)))
    }

    internal companion object {
        fun encode(s: HolstromSettings): String = listOf(
            "nudge_minute" to s.nudgeMinute,
            "default_minute" to s.defaultMinute,
            "default_delay_minutes" to s.defaultDelayMinutes,
            "speak_replies" to s.speakReplies,
            "use_gpu" to s.useGpu,
            "use_model" to s.useModel,
        ).joinToString("\n") { (k, v) -> "$k=$v" }

        fun decode(text: String?): HolstromSettings {
            val values = text.orEmpty().lines().mapNotNull { line ->
                line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
            }.toMap()
            val defaults = HolstromSettings()
            return HolstromSettings(
                nudgeMinute = values["nudge_minute"]?.toIntOrNull() ?: defaults.nudgeMinute,
                defaultMinute = values["default_minute"]?.toIntOrNull() ?: defaults.defaultMinute,
                defaultDelayMinutes = values["default_delay_minutes"]?.toIntOrNull() ?: defaults.defaultDelayMinutes,
                speakReplies = values["speak_replies"]?.toBooleanStrictOrNull() ?: defaults.speakReplies,
                useGpu = values["use_gpu"]?.toBooleanStrictOrNull() ?: defaults.useGpu,
                useModel = values["use_model"]?.toBooleanStrictOrNull() ?: defaults.useModel,
            )
        }
    }
}
