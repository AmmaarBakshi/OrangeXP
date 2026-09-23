//! Leave-home / preparation planning.
//!
//! The plan deliberately targets arriving *early*:
//!
//! ```text
//! leave            = floor_to(event_start − safety_buffer − travel, rounding)
//! start_preparing  = leave − preparation − packing
//! ```
//!
//! Travel time comes from the slot, the configuration, or — once enough trips
//! have been recorded — a high percentile of the user's real travel history.

use serde::{Deserialize, Serialize};

use crate::heatmap::nearest_rank;
use crate::time::Weekday;
use crate::timetable::{slots_on, TimetableSlot};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
#[serde(default)]
pub struct TravelConfig {
    pub default_travel_minutes: u32,
    pub preparation_minutes: u32,
    pub packing_minutes: u32,
    pub safety_buffer_minutes: u32,
    /// Percentile of recorded trips used as the travel estimate (1..=100).
    pub history_percentile: u32,
    pub min_history_samples: u32,
    /// Leave time is rounded *down* to a multiple of this.
    pub rounding_minutes: u32,
}

impl Default for TravelConfig {
    fn default() -> Self {
        Self {
            default_travel_minutes: 45,
            preparation_minutes: 20,
            packing_minutes: 10,
            safety_buffer_minutes: 30,
            history_percentile: 80,
            min_history_samples: 3,
            rounding_minutes: 5,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TravelEstimateSource {
    Configured,
    SlotOverride,
    History,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct DepartureInput {
    pub slot_id: Option<String>,
    /// Local minute of day the event starts. May be combined with negative
    /// results (the plan then starts on the previous day).
    pub event_start_minute: i32,
    pub travel_minutes: Option<u32>,
    pub preparation_minutes: Option<u32>,
    /// Durations of previously recorded trips to this kind of event.
    pub travel_history_minutes: Vec<u32>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct DeparturePlan {
    pub slot_id: Option<String>,
    pub event_start_minute: i32,
    pub start_preparing_minute: i32,
    pub leave_minute: i32,
    pub expected_arrival_minute: i32,
    /// Minutes between expected arrival and the event start (≥ safety buffer).
    pub early_by_minutes: i32,
    pub travel_minutes: u32,
    pub travel_source: TravelEstimateSource,
    pub preparation_minutes: u32,
    pub packing_minutes: u32,
    pub safety_buffer_minutes: u32,
}

pub fn estimate_travel(
    input: &DepartureInput,
    config: &TravelConfig,
) -> (u32, TravelEstimateSource) {
    let samples: Vec<i64> = {
        let mut v: Vec<i64> = input
            .travel_history_minutes
            .iter()
            .map(|&m| i64::from(m))
            .collect();
        v.sort_unstable();
        v
    };
    if !samples.is_empty() && samples.len() as u32 >= config.min_history_samples.max(1) {
        let p = nearest_rank(&samples, config.history_percentile.clamp(1, 100));
        return (p as u32, TravelEstimateSource::History);
    }
    match input.travel_minutes {
        Some(m) => (m, TravelEstimateSource::SlotOverride),
        None => (
            config.default_travel_minutes,
            TravelEstimateSource::Configured,
        ),
    }
}

pub fn plan_departure(input: &DepartureInput, config: &TravelConfig) -> DeparturePlan {
    let (travel, source) = estimate_travel(input, config);
    let preparation = input
        .preparation_minutes
        .unwrap_or(config.preparation_minutes);
    let rounding = config.rounding_minutes.max(1) as i32;
    let latest_leave =
        input.event_start_minute - config.safety_buffer_minutes as i32 - travel as i32;
    let leave = latest_leave.div_euclid(rounding) * rounding;
    let arrival = leave + travel as i32;
    DeparturePlan {
        slot_id: input.slot_id.clone(),
        event_start_minute: input.event_start_minute,
        start_preparing_minute: leave - preparation as i32 - config.packing_minutes as i32,
        leave_minute: leave,
        expected_arrival_minute: arrival,
        early_by_minutes: input.event_start_minute - arrival,
        travel_minutes: travel,
        travel_source: source,
        preparation_minutes: preparation,
        packing_minutes: config.packing_minutes,
        safety_buffer_minutes: config.safety_buffer_minutes,
    }
}

/// Plan for the first travel-requiring class of `weekday`, if any.
pub fn plan_first_departure(
    slots: &[TimetableSlot],
    weekday: Weekday,
    travel_history_minutes: &[u32],
    config: &TravelConfig,
) -> Option<DeparturePlan> {
    slots_on(slots, weekday)
        .into_iter()
        .find(|s| s.requires_travel)
        .map(|slot| {
            plan_departure(
                &DepartureInput {
                    slot_id: Some(slot.id.clone()),
                    event_start_minute: slot.start_minute as i32,
                    travel_minutes: slot.travel_minutes,
                    preparation_minutes: slot.preparation_minutes,
                    travel_history_minutes: travel_history_minutes.to_vec(),
                },
                config,
            )
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(start: i32, travel: Option<u32>, history: Vec<u32>) -> DepartureInput {
        DepartureInput {
            slot_id: None,
            event_start_minute: start,
            travel_minutes: travel,
            preparation_minutes: None,
            travel_history_minutes: history,
        }
    }

    #[test]
    fn spec_example_lecture_0815() {
        // 08:15 lecture, 60 min travel, 20 min prep, 30 min buffer.
        let config = TravelConfig {
            packing_minutes: 0,
            ..TravelConfig::default()
        };
        let plan = plan_departure(&input(8 * 60 + 15, Some(60), vec![]), &config);
        assert_eq!(plan.leave_minute, 6 * 60 + 45);
        assert_eq!(plan.start_preparing_minute, 6 * 60 + 25);
        assert_eq!(plan.expected_arrival_minute, 7 * 60 + 45);
        assert_eq!(plan.early_by_minutes, 30);
        assert_eq!(plan.travel_source, TravelEstimateSource::SlotOverride);
    }

    #[test]
    fn rounds_leave_time_down() {
        let plan = plan_departure(
            &input(8 * 60 + 17, Some(43), vec![]),
            &TravelConfig::default(),
        );
        // 08:17 − 30 − 43 = 07:04 → 07:00
        assert_eq!(plan.leave_minute, 7 * 60);
        assert_eq!(plan.early_by_minutes, 34);
    }

    #[test]
    fn uses_history_percentile_when_enough_samples() {
        let config = TravelConfig::default();
        let (travel, source) =
            estimate_travel(&input(600, Some(30), vec![40, 55, 42, 48, 70]), &config);
        assert_eq!((travel, source), (55, TravelEstimateSource::History));
        let (travel, source) = estimate_travel(&input(600, None, vec![40, 55]), &config);
        assert_eq!((travel, source), (45, TravelEstimateSource::Configured));
    }

    #[test]
    fn early_morning_plan_can_cross_midnight() {
        let plan = plan_departure(&input(30, Some(60), vec![]), &TravelConfig::default());
        assert_eq!(plan.leave_minute, -60);
        assert!(plan.start_preparing_minute < plan.leave_minute);
    }

    #[test]
    fn first_travel_slot_of_day() {
        let slots = vec![
            crate::timetable::tests::slot("online", Weekday::Monday, 7 * 60, 8 * 60, false),
            crate::timetable::tests::slot("late", Weekday::Monday, 11 * 60, 12 * 60, true),
            crate::timetable::tests::slot("early", Weekday::Monday, 9 * 60, 10 * 60, true),
        ];
        let plan =
            plan_first_departure(&slots, Weekday::Monday, &[], &TravelConfig::default()).unwrap();
        assert_eq!(plan.slot_id.as_deref(), Some("early"));
        assert!(
            plan_first_departure(&slots, Weekday::Sunday, &[], &TravelConfig::default()).is_none()
        );
    }
}
