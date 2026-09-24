package com.orangexp.feature.settings

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orangexp.core.common.widgets.HomeWidgetKind
import com.orangexp.core.common.widgets.HomeWidgets
import com.orangexp.core.data.repository.ConfigRepository
import com.orangexp.core.data.repository.MovementRepository
import com.orangexp.core.engine.OrangeEngine
import com.orangexp.core.engine.ffi.ConfigIssue
import com.orangexp.core.engine.ffi.DayState
import com.orangexp.core.engine.ffi.EngineConfig
import com.orangexp.core.engine.ffi.HeatmapScale
import com.orangexp.core.engine.ffi.ScoreCurve
import com.orangexp.core.sensing.TrackingPermissionChecker
import com.orangexp.core.sensing.TrackingPermissions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val config: EngineConfig? = null,
    val permissions: TrackingPermissions? = null,
    val engineVersion: String = "",
    val issues: List<ConfigIssue> = emptyList(),
    val movementEnabled: Boolean = false,
    /** Location/activity permissions walking detection still needs. */
    val movementMissingPermissions: List<String> = emptyList(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val permissionChecker: TrackingPermissionChecker,
    private val homeWidgets: HomeWidgets,
    private val movement: MovementRepository,
    engine: OrangeEngine,
) : ViewModel() {

    val movementSupported: Boolean = movement.isSupported()
    private val movementMissing = MutableStateFlow(movement.missingPermissions())

    val canPinWidgets: Boolean = homeWidgets.canPin()

    fun addWidget(kind: HomeWidgetKind) = launch { homeWidgets.requestPin(kind) }


    private val permissions = MutableStateFlow(permissionChecker.current())
    private val issues = MutableStateFlow<List<ConfigIssue>>(emptyList())
    private val exports = Channel<String>(Channel.BUFFERED)

    /** One-shot JSON exports for the share sheet. */
    val exportedJson: Flow<String> = exports.receiveAsFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        configRepository.config,
        permissions,
        issues,
        movement.enabled,
        movementMissing,
    ) { config, perms, problems, movementOn, missing ->
        SettingsUiState(config, perms, engine.version, problems, movementOn, missing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun refreshPermissions() {
        permissions.value = permissionChecker.current()
        movementMissing.value = movement.missingPermissions()
    }

    fun setMovementEnabled(enabled: Boolean) = launch {
        movementMissing.value = movement.missingPermissions()
        movement.setEnabled(enabled)
    }

    fun updateMovement(maxWalkingKmh: Double, vehicleKmh: Double) = update { config ->
        config.copy(movement = config.movement.copy(maxWalkingSpeedKmh = maxWalkingKmh, vehicleSpeedKmh = vehicleKmh))
    }

    fun updateCompetitions(weekdayHours: Double, weekendHours: Double, maxConcurrent: Int) = update { config ->
        config.copy(
            competitions = config.competitions.copy(
                weekdayHours = weekdayHours,
                weekendHours = weekendHours,
                maxConcurrent = maxConcurrent.coerceAtLeast(1).toUInt(),
            ),
        )
    }

    fun usageAccessIntent(): Intent = permissionChecker.usageAccessSettingsIntent()
    fun appSettingsIntent(): Intent = permissionChecker.appSettingsIntent()

    fun setScoringRuleEnabled(id: String, enabled: Boolean) = update { config ->
        config.copy(scoringRules = config.scoringRules.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun updateLinearRule(id: String, pointsPerUnit: Double, maxPoints: Double?) = update { config ->
        config.copy(
            scoringRules = config.scoringRules.map { rule ->
                val curve = rule.curve
                if (rule.id == id && curve is ScoreCurve.Linear) {
                    rule.copy(curve = curve.copy(pointsPerUnit = pointsPerUnit), maxPoints = maxPoints)
                } else {
                    rule
                }
            },
        )
    }

    fun setStateRuleEnabled(id: String, enabled: Boolean) = update { config ->
        config.copy(stateRules = config.stateRules.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun updateStateRule(id: String, orange: Double, red: Double, black: Double) = update { config ->
        config.copy(
            stateRules = config.stateRules.map {
                if (it.id == id) it.copy(orangeAt = orange, redAt = red, blackAt = black) else it
            },
        )
    }

    fun updateSleep(thresholdMinutes: Int, awakeResetMinutes: Int, windowStart: Int, windowEnd: Int) = update { config ->
        config.copy(
            sleep = config.sleep.copy(
                inactivityThresholdMinutes = thresholdMinutes.toUInt(),
                awakeResetMinutes = awakeResetMinutes.toUInt(),
                typicalWindowStartMinute = windowStart.toUInt(),
                typicalWindowEndMinute = windowEnd.toUInt(),
            ),
        )
    }

    fun updateTravel(defaultTravel: Int, preparation: Int, packing: Int, buffer: Int) = update { config ->
        config.copy(
            travel = config.travel.copy(
                defaultTravelMinutes = defaultTravel.toUInt(),
                preparationMinutes = preparation.toUInt(),
                packingMinutes = packing.toUInt(),
                safetyBufferMinutes = buffer.toUInt(),
            ),
        )
    }

    fun updateStudy(chunk: Int, maxPerSubject: Int, revisionDays: Int) = update { config ->
        config.copy(
            study = config.study.copy(
                chunkMinutes = chunk.toUInt(),
                maxMinutesPerSubjectPerDay = maxPerSubject.toUInt(),
                revisionDaysBeforeDeadline = revisionDays.toUInt(),
            ),
        )
    }

    fun updateStreak(minPoints: Long, worstState: DayState) = update { config ->
        config.copy(streak = config.streak.copy(minPoints = minPoints, worstAllowedState = worstState))
    }

    fun updateHeatmapTarget(target: Long) = update { config ->
        config.copy(heatmap = config.heatmap.copy(scale = HeatmapScale.TargetRelative(target)))
    }

    fun useQuantileHeatmap() = update { config -> config.copy(heatmap = config.heatmap.copy(scale = HeatmapScale.Quantile)) }

    fun exportConfig() = launch { exports.send(configRepository.exportJson()) }

    fun importConfig(json: String) = launch { issues.value = configRepository.importJson(json) }

    fun resetConfig() = launch {
        configRepository.resetToDefaults()
        issues.value = emptyList()
    }

    private fun update(transform: (EngineConfig) -> EngineConfig) = launch {
        issues.value = configRepository.update(transform)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "Settings action failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
