//! Weekly timetable analysis: conflicts and free study capacity per day.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::interval::{self, Interval};
use crate::time::{weekday_of, EpochDay, Weekday};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct TimetableSlot {
    pub id: String,
    pub title: String,
    pub weekday: Weekday,
    /// Local minutes since midnight.
    pub start_minute: u32,
    pub end_minute: u32,
    pub requires_travel: bool,
    /// Per-slot override of the configured travel time.
    pub travel_minutes: Option<u32>,
    /// Per-slot override of the configured preparation time.
    pub preparation_minutes: Option<u32>,
}

/// A recurring window in which the user is willing to study.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct StudyWindow {
    pub weekday: Weekday,
    pub start_minute: u32,
    pub end_minute: u32,
}

/// Replaces the computed capacity for one specific date (holiday, exam day...).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct CapacityOverride {
    pub epoch_day: EpochDay,
    pub minutes: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct DayCapacity {
    pub epoch_day: EpochDay,
    pub minutes: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct TimetableConflict {
    pub first_id: String,
    pub second_id: String,
    pub weekday: Weekday,
    pub overlap_minutes: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct CapacityInput {
    pub slots: Vec<TimetableSlot>,
    pub windows: Vec<StudyWindow>,
    pub overrides: Vec<CapacityOverride>,
    pub from_day: EpochDay,
    pub to_day: EpochDay,
    /// Travel minutes assumed for slots without their own value.
    pub default_travel_minutes: u32,
}

pub fn slots_on(slots: &[TimetableSlot], weekday: Weekday) -> Vec<&TimetableSlot> {
    let mut day: Vec<&TimetableSlot> = slots.iter().filter(|s| s.weekday == weekday).collect();
    day.sort_by(|a, b| {
        (a.start_minute, a.end_minute, &a.id).cmp(&(b.start_minute, b.end_minute, &b.id))
    });
    day
}

pub fn find_conflicts(slots: &[TimetableSlot]) -> Vec<TimetableConflict> {
    let mut out = Vec::new();
    for weekday in Weekday::ALL {
        let day = slots_on(slots, weekday);
        for (i, a) in day.iter().enumerate() {
            for b in &day[i + 1..] {
                let overlap = a
                    .end_minute
                    .min(b.end_minute)
                    .saturating_sub(a.start_minute.max(b.start_minute));
                if overlap > 0 {
                    out.push(TimetableConflict {
                        first_id: a.id.clone(),
                        second_id: b.id.clone(),
                        weekday,
                        overlap_minutes: overlap,
                    });
                }
            }
        }
    }
    out
}

/// Free study minutes per day: study windows minus classes and the travel
/// blocks before the first and after the last travel-requiring class.
pub fn study_capacity(input: &CapacityInput) -> Vec<DayCapacity> {
    let overrides: BTreeMap<EpochDay, u32> = input
        .overrides
        .iter()
        .map(|o| (o.epoch_day, o.minutes))
        .collect();
    (input.from_day..=input.to_day)
        .map(|day| {
            let minutes = overrides
                .get(&day)
                .copied()
                .unwrap_or_else(|| weekday_capacity(input, weekday_of(day)));
            DayCapacity {
                epoch_day: day,
                minutes,
            }
        })
        .collect()
}

fn weekday_capacity(input: &CapacityInput, weekday: Weekday) -> u32 {
    let windows = interval::merge(
        input
            .windows
            .iter()
            .filter(|w| w.weekday == weekday)
            .map(|w| Interval::new(i64::from(w.start_minute), i64::from(w.end_minute)))
            .collect(),
    );
    let slots = slots_on(&input.slots, weekday);
    let mut blocked: Vec<Interval> = slots
        .iter()
        .map(|s| Interval::new(i64::from(s.start_minute), i64::from(s.end_minute)))
        .collect();
    let travel =
        |s: &TimetableSlot| i64::from(s.travel_minutes.unwrap_or(input.default_travel_minutes));
    let travelling: Vec<&&TimetableSlot> = slots.iter().filter(|s| s.requires_travel).collect();
    if let Some(first) = travelling.first() {
        let start = i64::from(first.start_minute);
        blocked.push(Interval::new(start - travel(first), start));
    }
    if let Some(last) = travelling.iter().max_by_key(|s| s.end_minute) {
        let end = i64::from(last.end_minute);
        blocked.push(Interval::new(end, end + travel(last)));
    }
    let free = interval::subtract(&windows, &interval::merge(blocked));
    interval::total(&free).max(0) as u32
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::time::days_from_civil;

    pub(crate) fn slot(
        id: &str,
        weekday: Weekday,
        start: u32,
        end: u32,
        travel: bool,
    ) -> TimetableSlot {
        TimetableSlot {
            id: id.into(),
            title: id.into(),
            weekday,
            start_minute: start,
            end_minute: end,
            requires_travel: travel,
            travel_minutes: None,
            preparation_minutes: None,
        }
    }

    #[test]
    fn detects_overlaps() {
        let slots = vec![
            slot("a", Weekday::Monday, 495, 555, true),
            slot("b", Weekday::Monday, 540, 600, true),
            slot("c", Weekday::Monday, 615, 675, true),
            slot("d", Weekday::Tuesday, 540, 600, true),
        ];
        let conflicts = find_conflicts(&slots);
        assert_eq!(conflicts.len(), 1);
        assert_eq!(
            (
                conflicts[0].first_id.as_str(),
                conflicts[0].second_id.as_str(),
                conflicts[0].overlap_minutes
            ),
            ("a", "b", 15)
        );
    }

    #[test]
    fn capacity_subtracts_classes_and_travel() {
        let monday = days_from_civil(2026, 9, 28);
        let input = CapacityInput {
            slots: vec![
                slot("dsa", Weekday::Monday, 8 * 60, 9 * 60, true),
                slot("cn", Weekday::Monday, 15 * 60, 16 * 60, true),
            ],
            windows: vec![
                StudyWindow {
                    weekday: Weekday::Monday,
                    start_minute: 6 * 60,
                    end_minute: 12 * 60,
                },
                StudyWindow {
                    weekday: Weekday::Monday,
                    start_minute: 14 * 60,
                    end_minute: 20 * 60,
                },
                StudyWindow {
                    weekday: Weekday::Tuesday,
                    start_minute: 18 * 60,
                    end_minute: 20 * 60,
                },
            ],
            overrides: vec![CapacityOverride {
                epoch_day: monday + 2,
                minutes: 0,
            }],
            from_day: monday,
            to_day: monday + 2,
            default_travel_minutes: 60,
        };
        let caps = study_capacity(&input);
        // Morning 6-12 minus travel 7-8 and class 8-9 = 4h; afternoon 14-20 minus class 15-16 and travel 16-17 = 4h.
        assert_eq!(caps[0].minutes, 480);
        assert_eq!(caps[1].minutes, 120);
        assert_eq!(caps[2].minutes, 0);
    }
}
