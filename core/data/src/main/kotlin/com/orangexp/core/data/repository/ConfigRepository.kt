package com.orangexp.core.data.repository

import com.orangexp.core.common.coroutines.Dispatcher
import com.orangexp.core.common.coroutines.OxDispatchers
import com.orangexp.core.database.dao.KeyValueDao
import com.orangexp.core.database.model.KeyValueEntity
import com.orangexp.core.engine.OrangeEngine
import com.orangexp.core.engine.ffi.ConfigIssue
import com.orangexp.core.engine.ffi.EngineConfig
import com.orangexp.core.engine.ffi.EngineException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The user's rules: scoring curves, state thresholds and algorithm parameters. */
interface ConfigRepository {
    val config: Flow<EngineConfig>

    suspend fun current(): EngineConfig

    /** Applies [transform] if the result is valid; otherwise returns the issues and changes nothing. */
    suspend fun update(transform: (EngineConfig) -> EngineConfig): List<ConfigIssue>

    suspend fun resetToDefaults()

    suspend fun exportJson(): String

    /** Replaces the configuration with [json] if it parses and validates. */
    suspend fun importJson(json: String): List<ConfigIssue>
}

@Singleton
internal class OfflineConfigRepository @Inject constructor(
    private val keyValues: KeyValueDao,
    private val engine: OrangeEngine,
    @Dispatcher(OxDispatchers.Default) private val dispatcher: CoroutineDispatcher,
) : ConfigRepository {

    override val config: Flow<EngineConfig> = keyValues.observe(StorageKeys.ENGINE_CONFIG)
        .distinctUntilChanged()
        .map(::decode)
        .flowOn(dispatcher)

    override suspend fun current(): EngineConfig = config.first()

    override suspend fun update(transform: (EngineConfig) -> EngineConfig): List<ConfigIssue> =
        withContext(dispatcher) {
            val updated = transform(current())
            val issues = engine.validate(updated)
            if (issues.isEmpty()) store(updated)
            issues
        }

    override suspend fun resetToDefaults() = withContext(dispatcher) { store(engine.defaultConfig()) }

    override suspend fun exportJson(): String = withContext(dispatcher) { engine.serializeConfig(current()) }

    override suspend fun importJson(json: String): List<ConfigIssue> = withContext(dispatcher) {
        val parsed = try {
            engine.parseConfig(json)
        } catch (e: EngineException) {
            return@withContext listOf(ConfigIssue(path = "", message = e.message ?: "Invalid configuration"))
        }
        val issues = engine.validate(parsed)
        if (issues.isEmpty()) store(parsed)
        issues
    }

    /** Falls back to defaults if stored JSON is missing or unreadable. */
    private fun decode(json: String?): EngineConfig =
        json?.let { runCatching { engine.parseConfig(it) }.getOrNull() } ?: engine.defaultConfig()

    private suspend fun store(config: EngineConfig) =
        keyValues.put(KeyValueEntity(StorageKeys.ENGINE_CONFIG, engine.serializeConfig(config)))
}

internal object StorageKeys {
    const val ENGINE_CONFIG = "engine_config"
    const val USAGE_CURSOR_MS = "usage_cursor_ms"
    const val STEP_BASELINE = "step_baseline"
    const val LAST_CHARGING = "last_charging"
    const val SLEEP_AWAKE_SINCE_MS = "sleep_awake_since_ms"
    const val SLEEP_ONGOING_SINCE_MS = "sleep_ongoing_since_ms"
    const val SLEEP_LAST_MINUTES = "sleep_last_minutes"
    const val SLEEP_LAST_CONFIDENCE = "sleep_last_confidence"
    const val PLAN_GENERATED_FOR_DAY = "plan_generated_for_day"
    const val LAST_FINALIZED_DAY = "last_finalized_day"
    const val MOVEMENT_ENABLED = "movement_enabled"
    const val HOLSTROM_SETTINGS = "holstrom_settings"
}
