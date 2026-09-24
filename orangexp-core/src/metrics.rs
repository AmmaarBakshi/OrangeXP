//! The metric catalog: every measurable quantity the engine understands.
//!
//! Metrics are identified by stable string keys so that platform code, stored
//! configuration and rules can reference them without sharing enums. Adding a new
//! metric means adding a key and a catalog entry here, then producing it on the
//! platform side; no other engine code needs to change.

use serde::{Deserialize, Serialize};

use crate::category::Category;

pub mod keys {
    pub const SLEEP_MINUTES: &str = "sleep.minutes";
    pub const AWAKE_MINUTES: &str = "awake.minutes";
    pub const STEPS: &str = "walking.steps";
    pub const WALKING_METERS: &str = "walking.meters";
    pub const WALKING_MINUTES: &str = "walking.minutes";
    pub const VEHICLE_MINUTES: &str = "travel.vehicle_minutes";
    pub const VEHICLE_METERS: &str = "travel.vehicle_meters";
    pub const ACTIVE_MINUTES: &str = "activity.active_minutes";
    pub const STUDY_MINUTES: &str = "study.minutes";
    pub const SYLLABUS_COMPLETED_MINUTES: &str = "syllabus.completed_minutes";
    pub const TOPICS_COMPLETED: &str = "syllabus.topics_completed";
    pub const CLASSES_ATTENDED: &str = "attendance.classes_attended";
    pub const CLASSES_SCHEDULED: &str = "attendance.classes_scheduled";
    pub const ATTENDANCE_PERCENT: &str = "attendance.percent";
    pub const SCHEDULE_ADHERENCE_PERCENT: &str = "schedule.adherence_percent";
    pub const ON_TIME_DEPARTURES: &str = "travel.on_time_departures";
    pub const TASKS_COMPLETED: &str = "tasks.completed";
    pub const SCREEN_MINUTES: &str = "phone.screen_minutes";
    pub const UNLOCKS: &str = "phone.unlocks";
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum MetricUnit {
    Minutes,
    Count,
    Steps,
    Meters,
    Percent,
}

/// How repeated measurements of the same metric within one day are combined.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Aggregation {
    Sum,
    Max,
    Last,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct MetricDefinition {
    pub key: String,
    pub label: String,
    pub category: Category,
    pub unit: MetricUnit,
    pub aggregation: Aggregation,
}

fn def(
    key: &str,
    label: &str,
    category: Category,
    unit: MetricUnit,
    aggregation: Aggregation,
) -> MetricDefinition {
    MetricDefinition {
        key: key.to_owned(),
        label: label.to_owned(),
        category,
        unit,
        aggregation,
    }
}

/// All metrics known to this engine version.
pub fn catalog() -> Vec<MetricDefinition> {
    use Aggregation::*;
    use Category as C;
    use MetricUnit as U;
    vec![
        def(keys::SLEEP_MINUTES, "Sleep", C::Sleep, U::Minutes, Sum),
        def(
            keys::AWAKE_MINUTES,
            "Longest awake period",
            C::Wakefulness,
            U::Minutes,
            Max,
        ),
        def(keys::STEPS, "Steps", C::Walking, U::Steps, Sum),
        def(
            keys::WALKING_METERS,
            "Walking distance",
            C::Walking,
            U::Meters,
            Sum,
        ),
        def(
            keys::WALKING_MINUTES,
            "Walking time",
            C::Walking,
            U::Minutes,
            Sum,
        ),
        def(
            keys::VEHICLE_MINUTES,
            "Time in vehicles",
            C::Travel,
            U::Minutes,
            Sum,
        ),
        def(
            keys::VEHICLE_METERS,
            "Distance by vehicle",
            C::Travel,
            U::Meters,
            Sum,
        ),
        def(
            keys::ACTIVE_MINUTES,
            "Active time",
            C::PhysicalActivity,
            U::Minutes,
            Sum,
        ),
        def(keys::STUDY_MINUTES, "Study time", C::Study, U::Minutes, Sum),
        def(
            keys::SYLLABUS_COMPLETED_MINUTES,
            "Syllabus work completed",
            C::Syllabus,
            U::Minutes,
            Sum,
        ),
        def(
            keys::TOPICS_COMPLETED,
            "Topics completed",
            C::Syllabus,
            U::Count,
            Sum,
        ),
        def(
            keys::CLASSES_ATTENDED,
            "Classes attended",
            C::Attendance,
            U::Count,
            Sum,
        ),
        def(
            keys::CLASSES_SCHEDULED,
            "Classes scheduled",
            C::Attendance,
            U::Count,
            Sum,
        ),
        def(
            keys::ATTENDANCE_PERCENT,
            "Attendance",
            C::Attendance,
            U::Percent,
            Last,
        ),
        def(
            keys::SCHEDULE_ADHERENCE_PERCENT,
            "Schedule adherence",
            C::Schedule,
            U::Percent,
            Last,
        ),
        def(
            keys::ON_TIME_DEPARTURES,
            "On-time departures",
            C::Travel,
            U::Count,
            Sum,
        ),
        def(
            keys::TASKS_COMPLETED,
            "Tasks completed",
            C::TaskCompletion,
            U::Count,
            Sum,
        ),
        def(
            keys::SCREEN_MINUTES,
            "Screen time",
            C::PhoneUsage,
            U::Minutes,
            Sum,
        ),
        def(keys::UNLOCKS, "Unlocks", C::PhoneUsage, U::Count, Sum),
    ]
}

pub fn find(key: &str) -> Option<MetricDefinition> {
    catalog().into_iter().find(|d| d.key == key)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn every_key_is_in_the_catalog() {
        use keys::*;
        let all = [
            SLEEP_MINUTES,
            AWAKE_MINUTES,
            STEPS,
            WALKING_METERS,
            WALKING_MINUTES,
            VEHICLE_MINUTES,
            VEHICLE_METERS,
            ACTIVE_MINUTES,
            STUDY_MINUTES,
            SYLLABUS_COMPLETED_MINUTES,
            TOPICS_COMPLETED,
            CLASSES_ATTENDED,
            CLASSES_SCHEDULED,
            ATTENDANCE_PERCENT,
            SCHEDULE_ADHERENCE_PERCENT,
            ON_TIME_DEPARTURES,
            TASKS_COMPLETED,
            SCREEN_MINUTES,
            UNLOCKS,
        ];
        for key in all {
            assert!(find(key).is_some(), "{key} missing from catalog");
        }
        assert_eq!(catalog().len(), all.len());
    }

    #[test]
    fn keys_are_unique() {
        let all = catalog();
        let unique: HashSet<_> = all.iter().map(|d| d.key.as_str()).collect();
        assert_eq!(unique.len(), all.len());
    }
}
