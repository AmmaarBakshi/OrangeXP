package com.orangexp.core.engine

import com.orangexp.core.engine.ffi.AdherenceResult
import com.orangexp.core.engine.ffi.CapacityInput
import com.orangexp.core.engine.ffi.CommandInput
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
import com.orangexp.core.engine.ffi.ParsedCommand
import com.orangexp.core.engine.ffi.ReminderFire
import com.orangexp.core.engine.ffi.ReminderSchedule
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

/**
 * The Kotlin face of the Rust computational core.
 *
 * Every call is synchronous, pure and CPU-bound: callers run it off the main
 * thread. Each function takes one structured input and returns one structured
 * result, keeping FFI crossings rare and coarse.
 *
 * The data types are generated from Rust by UniFFI (`com.orangexp.core.engine.ffi`)
 * and are the application's domain model.
 */
interface OrangeEngine {
    val version: String

    fun defaultConfig(): EngineConfig

    /** @throws com.orangexp.core.engine.ffi.EngineException on malformed JSON. */
    fun parseConfig(json: String): EngineConfig

    fun serializeConfig(config: EngineConfig): String

    fun validate(config: EngineConfig): List<ConfigIssue>

    fun metricCatalog(): List<MetricDefinition>

    fun evaluateDay(input: DayInput, config: EngineConfig): DayEvaluation

    fun evaluateHistory(input: HistoryInput, config: EngineConfig): HistoryEvaluation

    fun analyzeSleepDay(
        events: List<DeviceEvent>,
        dayStartMs: Long,
        dayEndMs: Long,
        nowMs: Long,
        utcOffsetMinutes: Int,
        config: SleepConfig,
    ): SleepDayReport

    fun planFirstDeparture(
        slots: List<TimetableSlot>,
        weekday: Weekday,
        travelHistoryMinutes: List<UInt>,
        config: TravelConfig,
    ): DeparturePlan?

    fun findTimetableConflicts(slots: List<TimetableSlot>): List<TimetableConflict>

    fun studyCapacity(input: CapacityInput): List<DayCapacity>

    fun generateStudySchedule(input: ScheduleInput): StudySchedule

    fun adherence(planned: List<WorkBlock>, actual: List<WorkBlock>): AdherenceResult

    fun analyzeMovement(input: MovementInput, config: MovementConfig): MovementSummary

    fun planCompetitions(competitions: List<Competition>, today: Int, config: CompetitionConfig): CompetitionPlan

    fun rankTeammates(competitions: List<Competition>, today: Int, config: CompetitionConfig): List<TeammateStats>

    /** Reads a typed or spoken sentence as a reminder, deadline, phone action or question. */
    fun parseCommand(input: CommandInput): ParsedCommand

    /** Next alarm of a stored reminder strictly after [afterMs], or `null` when it is over. */
    fun nextReminderFire(schedule: ReminderSchedule, afterMs: Long, utcOffsetMinutes: Int): ReminderFire?
}
