//! Streaks: purely historical information.
//!
//! A streak never changes a single point of any day's score. It only counts
//! consecutive days that satisfy the configured [`StreakRule`].

use serde::{Deserialize, Serialize};

use crate::history::{canonical, DayRecord};
use crate::state::DayState;
use crate::time::EpochDay;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct StreakRule {
    pub min_points: i64,
    /// Days in a worse state than this break the streak.
    pub worst_allowed_state: DayState,
}

impl Default for StreakRule {
    fn default() -> Self {
        Self {
            min_points: 5_000,
            worst_allowed_state: DayState::Red,
        }
    }
}

impl StreakRule {
    pub fn qualifies(&self, day: &DayRecord) -> bool {
        day.total_points >= self.min_points && day.state <= self.worst_allowed_state
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct StreakResult {
    /// Consecutive qualifying days ending today, or ending yesterday while
    /// today is still in progress and has not qualified yet.
    pub current: u32,
    pub current_start_day: Option<EpochDay>,
    pub today_qualifies: bool,
    pub longest: u32,
    pub longest_start_day: Option<EpochDay>,
    pub longest_end_day: Option<EpochDay>,
    pub qualifying_days: u32,
}

pub fn compute(days: &[DayRecord], today: EpochDay, rule: &StreakRule) -> StreakResult {
    let qualifying: Vec<EpochDay> = canonical(days, today)
        .iter()
        .filter(|d| rule.qualifies(d))
        .map(|d| d.epoch_day)
        .collect();

    let mut longest = 0u32;
    let mut longest_range: Option<(EpochDay, EpochDay)> = None;
    let mut run_start: Option<EpochDay> = None;
    let mut prev: Option<EpochDay> = None;
    let mut run_len = 0u32;
    for &day in &qualifying {
        if prev.is_some_and(|p| p + 1 == day) {
            run_len += 1;
        } else {
            run_len = 1;
            run_start = Some(day);
        }
        if run_len > longest {
            longest = run_len;
            longest_range = run_start.map(|s| (s, day));
        }
        prev = Some(day);
    }

    let today_qualifies = qualifying.last() == Some(&today);
    let anchor = if today_qualifies { today } else { today - 1 };
    let mut current = 0u32;
    let mut expected = anchor;
    for &day in qualifying.iter().rev() {
        if day > anchor {
            continue;
        }
        if day != expected {
            break;
        }
        current += 1;
        expected -= 1;
    }

    StreakResult {
        current,
        current_start_day: (current > 0).then(|| anchor - current as EpochDay + 1),
        today_qualifies,
        longest,
        longest_start_day: longest_range.map(|r| r.0),
        longest_end_day: longest_range.map(|r| r.1),
        qualifying_days: qualifying.len() as u32,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn day(epoch_day: EpochDay, points: i64, state: DayState) -> DayRecord {
        DayRecord {
            epoch_day,
            total_points: points,
            state,
            category_points: vec![],
        }
    }

    fn rule() -> StreakRule {
        StreakRule {
            min_points: 100,
            worst_allowed_state: DayState::Orange,
        }
    }

    #[test]
    fn counts_consecutive_days_through_today() {
        let days: Vec<_> = (1..=4).map(|d| day(d, 150, DayState::Green)).collect();
        let r = compute(&days, 4, &rule());
        assert_eq!(r.current, 4);
        assert_eq!(r.current_start_day, Some(1));
        assert!(r.today_qualifies);
        assert_eq!(r.longest, 4);
    }

    #[test]
    fn today_in_progress_does_not_break_streak() {
        let mut days: Vec<_> = (1..=3).map(|d| day(d, 150, DayState::Green)).collect();
        days.push(day(4, 20, DayState::Green));
        let r = compute(&days, 4, &rule());
        assert_eq!(r.current, 3);
        assert!(!r.today_qualifies);
    }

    #[test]
    fn gaps_and_bad_states_break_streaks() {
        let days = vec![
            day(1, 150, DayState::Green),
            day(2, 150, DayState::Green),
            day(3, 150, DayState::Green),
            day(4, 500, DayState::Red),
            day(5, 150, DayState::Green),
            day(7, 150, DayState::Orange),
            day(8, 150, DayState::Green),
        ];
        let r = compute(&days, 8, &rule());
        assert_eq!(r.current, 2);
        assert_eq!(r.current_start_day, Some(7));
        assert_eq!(r.longest, 3);
        assert_eq!((r.longest_start_day, r.longest_end_day), (Some(1), Some(3)));
        assert_eq!(r.qualifying_days, 6);
    }

    #[test]
    fn stale_streak_is_zero() {
        let days = vec![day(1, 150, DayState::Green), day(2, 150, DayState::Green)];
        let r = compute(&days, 10, &rule());
        assert_eq!(r.current, 0);
        assert_eq!(r.current_start_day, None);
        assert_eq!(r.longest, 2);
    }

    #[test]
    fn empty_history() {
        let r = compute(&[], 10, &rule());
        assert_eq!(r.current, 0);
        assert_eq!(r.longest, 0);
        assert_eq!(r.longest_start_day, None);
    }
}
