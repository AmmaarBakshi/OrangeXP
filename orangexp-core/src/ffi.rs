//! The UniFFI boundary. Thin, coarse-grained functions: the platform sends one
//! structured input and receives one structured result per call.
//!
//! Nothing here contains logic beyond delegation; all behaviour lives in the
//! platform-independent modules and is tested there.

use crate::adherence::{self, AdherenceResult, WorkBlock};
use crate::commands::{self, CommandInput, ParsedCommand, ReminderFire, ReminderSchedule};
use crate::competitions::{self, Competition, CompetitionConfig, CompetitionPlan, TeammateStats};
use crate::config::{ConfigIssue, EngineConfig};
use crate::daily::{self, DayEvaluation, DayInput, HistoryEvaluation, HistoryInput};
use crate::error::EngineError;
use crate::metrics::{self, MetricDefinition};
use crate::movement::{self, MovementConfig, MovementInput, MovementSummary};
use crate::sleep::{
    self, DeviceEvent, SleepAnalysis, SleepConfig, SleepDayReport, SleepDaySummary,
};
use crate::syllabus::{self, ScheduleInput, StudySchedule};
use crate::time::{EpochDay, Weekday};
use crate::timetable::{self, CapacityInput, DayCapacity, TimetableConflict, TimetableSlot};
use crate::travel::{self, DepartureInput, DeparturePlan, TravelConfig};

#[uniffi::export]
pub fn engine_version() -> String {
    crate::ENGINE_VERSION.to_owned()
}

#[uniffi::export]
pub fn default_config() -> EngineConfig {
    EngineConfig::default()
}

#[uniffi::export]
pub fn config_from_json(json: String) -> Result<EngineConfig, EngineError> {
    EngineConfig::from_json(&json)
}

#[uniffi::export]
pub fn config_to_json(config: EngineConfig) -> String {
    config.to_json()
}

#[uniffi::export]
pub fn validate_config(config: EngineConfig) -> Vec<ConfigIssue> {
    config.validate()
}

#[uniffi::export]
pub fn metric_catalog() -> Vec<MetricDefinition> {
    metrics::catalog()
}

#[uniffi::export]
pub fn evaluate_day(input: DayInput, config: EngineConfig) -> DayEvaluation {
    daily::evaluate_day(&input, &config)
}

#[uniffi::export]
pub fn evaluate_history(input: HistoryInput, config: EngineConfig) -> HistoryEvaluation {
    daily::evaluate_history(&input, &config)
}

#[uniffi::export]
pub fn analyze_sleep(
    events: Vec<DeviceEvent>,
    now_ms: i64,
    utc_offset_minutes: i32,
    config: SleepConfig,
) -> SleepAnalysis {
    sleep::analyze(&events, now_ms, utc_offset_minutes, &config)
}

#[uniffi::export]
pub fn analyze_sleep_day(
    events: Vec<DeviceEvent>,
    day_start_ms: i64,
    day_end_ms: i64,
    now_ms: i64,
    utc_offset_minutes: i32,
    config: SleepConfig,
) -> SleepDayReport {
    sleep::analyze_day(
        &events,
        day_start_ms,
        day_end_ms,
        now_ms,
        utc_offset_minutes,
        &config,
    )
}

#[uniffi::export]
pub fn summarize_sleep_day(
    analysis: SleepAnalysis,
    day_start_ms: i64,
    day_end_ms: i64,
    now_ms: i64,
    config: SleepConfig,
) -> SleepDaySummary {
    sleep::summarize_day(&analysis, day_start_ms, day_end_ms, now_ms, &config)
}

#[uniffi::export]
pub fn plan_departure(input: DepartureInput, config: TravelConfig) -> DeparturePlan {
    travel::plan_departure(&input, &config)
}

#[uniffi::export]
pub fn plan_first_departure(
    slots: Vec<TimetableSlot>,
    weekday: Weekday,
    travel_history_minutes: Vec<u32>,
    config: TravelConfig,
) -> Option<DeparturePlan> {
    travel::plan_first_departure(&slots, weekday, &travel_history_minutes, &config)
}

#[uniffi::export]
pub fn find_timetable_conflicts(slots: Vec<TimetableSlot>) -> Vec<TimetableConflict> {
    timetable::find_conflicts(&slots)
}

#[uniffi::export]
pub fn compute_study_capacity(input: CapacityInput) -> Vec<DayCapacity> {
    timetable::study_capacity(&input)
}

#[uniffi::export]
pub fn generate_study_schedule(input: ScheduleInput) -> StudySchedule {
    syllabus::generate(&input)
}

#[uniffi::export]
pub fn compute_adherence(planned: Vec<WorkBlock>, actual: Vec<WorkBlock>) -> AdherenceResult {
    adherence::compute(&planned, &actual)
}

#[uniffi::export]
pub fn weekday_of_epoch_day(day: EpochDay) -> Weekday {
    crate::time::weekday_of(day)
}

#[uniffi::export]
pub fn analyze_movement(input: MovementInput, config: MovementConfig) -> MovementSummary {
    movement::analyze(&input, &config)
}

#[uniffi::export]
pub fn plan_competitions(
    competitions: Vec<Competition>,
    today: EpochDay,
    config: CompetitionConfig,
) -> CompetitionPlan {
    competitions::plan(&competitions, today, &config)
}

#[uniffi::export]
pub fn rank_teammates(
    competitions: Vec<Competition>,
    today: EpochDay,
    config: CompetitionConfig,
) -> Vec<TeammateStats> {
    competitions::rank_teammates(&competitions, today, &config)
}

#[uniffi::export]
pub fn parse_command(input: CommandInput) -> ParsedCommand {
    commands::parse_command(&input)
}

#[uniffi::export]
pub fn next_reminder_fire(
    schedule: ReminderSchedule,
    after_ms: i64,
    utc_offset_minutes: i32,
) -> Option<ReminderFire> {
    commands::next_fire(&schedule, after_ms, utc_offset_minutes)
}
