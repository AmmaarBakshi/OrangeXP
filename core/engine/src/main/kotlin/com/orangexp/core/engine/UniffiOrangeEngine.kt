package com.orangexp.core.engine

import com.orangexp.core.engine.ffi.AdherenceResult
import com.orangexp.core.engine.ffi.CapacityInput
import com.orangexp.core.engine.ffi.Competition
import com.orangexp.core.engine.ffi.CompetitionConfig
import com.orangexp.core.engine.ffi.CompetitionPlan
import com.orangexp.core.engine.ffi.ConfigIssue
import com.orangexp.core.engine.ffi.DayCapacity
import com.orangexp.core.engine.ffi.DayEvaluation
import com.orangexp.core.engine.ffi.DayInput
import com.orangexp.core.engine.ffi.DeparturePlan
import com.orangexp.core.engine.ffi.DeviceEvent
import com.orangexp.core.engine.ffi.EngineConfig
import com.orangexp.core.engine.ffi.HistoryEvaluation
import com.orangexp.core.engine.ffi.HistoryInput
import com.orangexp.core.engine.ffi.MetricDefinition
import com.orangexp.core.engine.ffi.MovementConfig
import com.orangexp.core.engine.ffi.MovementInput
import com.orangexp.core.engine.ffi.MovementSummary
import com.orangexp.core.engine.ffi.ScheduleInput
import com.orangexp.core.engine.ffi.SleepConfig
import com.orangexp.core.engine.ffi.SleepDayReport
import com.orangexp.core.engine.ffi.StudySchedule
import com.orangexp.core.engine.ffi.TeammateStats
import com.orangexp.core.engine.ffi.TimetableConflict
import com.orangexp.core.engine.ffi.TimetableSlot
import com.orangexp.core.engine.ffi.TravelConfig
import com.orangexp.core.engine.ffi.Weekday
import com.orangexp.core.engine.ffi.WorkBlock
import javax.inject.Inject
import javax.inject.Singleton
import com.orangexp.core.engine.ffi.analyzeMovement as ffiAnalyzeMovement
import com.orangexp.core.engine.ffi.analyzeSleepDay as ffiAnalyzeSleepDay
import com.orangexp.core.engine.ffi.computeAdherence as ffiComputeAdherence
import com.orangexp.core.engine.ffi.computeStudyCapacity as ffiComputeStudyCapacity
import com.orangexp.core.engine.ffi.configFromJson as ffiConfigFromJson
import com.orangexp.core.engine.ffi.configToJson as ffiConfigToJson
import com.orangexp.core.engine.ffi.defaultConfig as ffiDefaultConfig
import com.orangexp.core.engine.ffi.engineVersion as ffiEngineVersion
import com.orangexp.core.engine.ffi.evaluateDay as ffiEvaluateDay
import com.orangexp.core.engine.ffi.evaluateHistory as ffiEvaluateHistory
import com.orangexp.core.engine.ffi.findTimetableConflicts as ffiFindTimetableConflicts
import com.orangexp.core.engine.ffi.generateStudySchedule as ffiGenerateStudySchedule
import com.orangexp.core.engine.ffi.metricCatalog as ffiMetricCatalog
import com.orangexp.core.engine.ffi.planCompetitions as ffiPlanCompetitions
import com.orangexp.core.engine.ffi.planFirstDeparture as ffiPlanFirstDeparture
import com.orangexp.core.engine.ffi.rankTeammates as ffiRankTeammates
import com.orangexp.core.engine.ffi.validateConfig as ffiValidateConfig

/** [OrangeEngine] backed by the native `liborangexp_core.so` through UniFFI. */
@Singleton
class UniffiOrangeEngine @Inject constructor() : OrangeEngine {

    override val version: String by lazy { ffiEngineVersion() }

    override fun defaultConfig(): EngineConfig = ffiDefaultConfig()

    override fun parseConfig(json: String): EngineConfig = ffiConfigFromJson(json)

    override fun serializeConfig(config: EngineConfig): String = ffiConfigToJson(config)

    override fun validate(config: EngineConfig): List<ConfigIssue> = ffiValidateConfig(config)

    override fun metricCatalog(): List<MetricDefinition> = ffiMetricCatalog()

    override fun evaluateDay(input: DayInput, config: EngineConfig): DayEvaluation = ffiEvaluateDay(input, config)

    override fun evaluateHistory(input: HistoryInput, config: EngineConfig): HistoryEvaluation =
        ffiEvaluateHistory(input, config)

    override fun analyzeSleepDay(
        events: List<DeviceEvent>,
        dayStartMs: Long,
        dayEndMs: Long,
        nowMs: Long,
        utcOffsetMinutes: Int,
        config: SleepConfig,
    ): SleepDayReport = ffiAnalyzeSleepDay(events, dayStartMs, dayEndMs, nowMs, utcOffsetMinutes, config)

    override fun planFirstDeparture(
        slots: List<TimetableSlot>,
        weekday: Weekday,
        travelHistoryMinutes: List<UInt>,
        config: TravelConfig,
    ): DeparturePlan? = ffiPlanFirstDeparture(slots, weekday, travelHistoryMinutes, config)

    override fun findTimetableConflicts(slots: List<TimetableSlot>): List<TimetableConflict> =
        ffiFindTimetableConflicts(slots)

    override fun studyCapacity(input: CapacityInput): List<DayCapacity> = ffiComputeStudyCapacity(input)

    override fun generateStudySchedule(input: ScheduleInput): StudySchedule = ffiGenerateStudySchedule(input)

    override fun adherence(planned: List<WorkBlock>, actual: List<WorkBlock>): AdherenceResult =
        ffiComputeAdherence(planned, actual)

    override fun analyzeMovement(input: MovementInput, config: MovementConfig): MovementSummary =
        ffiAnalyzeMovement(input, config)

    override fun planCompetitions(competitions: List<Competition>, today: Int, config: CompetitionConfig): CompetitionPlan =
        ffiPlanCompetitions(competitions, today, config)

    override fun rankTeammates(competitions: List<Competition>, today: Int, config: CompetitionConfig): List<TeammateStats> =
        ffiRankTeammates(competitions, today, config)
}
