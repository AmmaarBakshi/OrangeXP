//! Contribution-heatmap mathematics: which intensity level (0..=5) each day gets
//! and where it sits in a week-column grid. Rendering is the platform's job.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::history::{canonical, DayRecord};
use crate::state::DayState;
use crate::time::{weekday_of, EpochDay, Weekday};

pub const MAX_LEVEL: u8 = 5;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum HeatmapScale {
    /// Levels are fixed fractions (<25%, <50%, <75%, <100%, ≥100%) of a daily
    /// target, so a square's colour always means the same thing.
    TargetRelative { target_points: i64 },
    /// Levels are quintiles of the positive scores inside the displayed range.
    Quantile,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct HeatmapConfig {
    pub scale: HeatmapScale,
    pub week_start: Weekday,
}

impl Default for HeatmapConfig {
    fn default() -> Self {
        Self {
            scale: HeatmapScale::TargetRelative {
                target_points: 12_000,
            },
            week_start: Weekday::Monday,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct HeatmapCell {
    pub epoch_day: EpochDay,
    /// Column, counted from the first (possibly partial) week of the range.
    pub week_index: u32,
    /// Row, 0 = configured first day of the week.
    pub row: u32,
    /// 0 = no activity, 1..=5 = increasing intensity.
    pub level: u8,
    pub points: i64,
    pub state: Option<DayState>,
    pub is_today: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct Heatmap {
    pub cells: Vec<HeatmapCell>,
    pub week_count: u32,
    /// Minimum points for levels 1..=5 (five entries).
    pub level_thresholds: Vec<i64>,
    pub max_points: i64,
}

/// Lower bounds for levels 1..=5.
pub fn thresholds(scale: &HeatmapScale, positive_scores: &[i64]) -> Vec<i64> {
    match scale {
        HeatmapScale::TargetRelative { target_points } => {
            let t = (*target_points).max(1) as f64;
            let mut th = vec![
                1,
                (t * 0.25).ceil() as i64,
                (t * 0.5).ceil() as i64,
                (t * 0.75).ceil() as i64,
                t.ceil() as i64,
            ];
            make_increasing(&mut th);
            th
        }
        HeatmapScale::Quantile => {
            let mut sorted: Vec<i64> = positive_scores.iter().copied().filter(|p| *p > 0).collect();
            sorted.sort_unstable();
            if sorted.is_empty() {
                return vec![1, 2, 3, 4, 5];
            }
            let mut th = vec![1];
            th.extend(
                [20u32, 40, 60, 80]
                    .iter()
                    .map(|&p| nearest_rank(&sorted, p) + 1),
            );
            make_increasing(&mut th);
            th
        }
    }
}

fn make_increasing(th: &mut [i64]) {
    for i in 1..th.len() {
        if th[i] <= th[i - 1] {
            th[i] = th[i - 1] + 1;
        }
    }
}

/// Nearest-rank percentile of a sorted, non-empty slice.
pub(crate) fn nearest_rank(sorted: &[i64], percentile: u32) -> i64 {
    let n = sorted.len();
    let rank = ((f64::from(percentile) / 100.0) * n as f64).ceil() as usize;
    sorted[rank.clamp(1, n) - 1]
}

pub fn level_for(points: i64, thresholds: &[i64]) -> u8 {
    thresholds
        .iter()
        .take_while(|&&t| points >= t)
        .count()
        .min(MAX_LEVEL as usize) as u8
}

/// Builds cells for every day in `start_day..=end_day`.
pub fn build(
    days: &[DayRecord],
    start_day: EpochDay,
    end_day: EpochDay,
    today: EpochDay,
    config: &HeatmapConfig,
) -> Heatmap {
    if end_day < start_day {
        return Heatmap {
            cells: vec![],
            week_count: 0,
            level_thresholds: thresholds(&config.scale, &[]),
            max_points: 0,
        };
    }
    let by_day: BTreeMap<EpochDay, DayRecord> = canonical(days, today)
        .into_iter()
        .filter(|d| d.epoch_day >= start_day && d.epoch_day <= end_day)
        .map(|d| (d.epoch_day, d))
        .collect();
    let scores: Vec<i64> = by_day.values().map(|d| d.total_points).collect();
    let level_thresholds = thresholds(&config.scale, &scores);
    let grid_start = start_day - weekday_of(start_day).days_since(config.week_start) as EpochDay;

    let cells: Vec<HeatmapCell> = (start_day..=end_day)
        .map(|day| {
            let record = by_day.get(&day);
            let points = record.map_or(0, |r| r.total_points);
            let offset = (day - grid_start) as u32;
            HeatmapCell {
                epoch_day: day,
                week_index: offset / 7,
                row: offset % 7,
                level: level_for(points, &level_thresholds),
                points,
                state: record.map(|r| r.state),
                is_today: day == today,
            }
        })
        .collect();

    Heatmap {
        week_count: cells.last().map_or(0, |c| c.week_index + 1),
        max_points: scores.iter().copied().max().unwrap_or(0).max(0),
        level_thresholds,
        cells,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::time::days_from_civil;

    fn record(day: EpochDay, points: i64) -> DayRecord {
        DayRecord {
            epoch_day: day,
            total_points: points,
            state: DayState::Green,
            category_points: vec![],
        }
    }

    #[test]
    fn target_relative_levels() {
        let th = thresholds(
            &HeatmapScale::TargetRelative {
                target_points: 1000,
            },
            &[],
        );
        assert_eq!(th, vec![1, 250, 500, 750, 1000]);
        assert_eq!(level_for(0, &th), 0);
        assert_eq!(level_for(-50, &th), 0);
        assert_eq!(level_for(1, &th), 1);
        assert_eq!(level_for(499, &th), 2);
        assert_eq!(level_for(750, &th), 4);
        assert_eq!(level_for(5000, &th), 5);
    }

    #[test]
    fn quantile_levels_are_strictly_increasing() {
        let th = thresholds(&HeatmapScale::Quantile, &[10, 10, 10, 10, 10]);
        assert!(th.windows(2).all(|w| w[0] < w[1]));
        let th = thresholds(&HeatmapScale::Quantile, &(1..=100).collect::<Vec<_>>());
        assert_eq!(th, vec![1, 21, 41, 61, 81]);
        assert_eq!(level_for(100, &th), 5);
    }

    #[test]
    fn grid_aligns_to_week_start() {
        // 2026-09-24 is a Thursday.
        let start = days_from_civil(2026, 9, 24);
        let config = HeatmapConfig::default();
        let map = build(
            &[record(start, 12_000), record(start + 5, 3_000)],
            start,
            start + 10,
            start + 5,
            &config,
        );
        assert_eq!(map.cells.len(), 11);
        assert_eq!((map.cells[0].week_index, map.cells[0].row), (0, 3));
        assert_eq!(map.cells[0].level, 5);
        // Monday 2026-09-28 opens week 1.
        assert_eq!((map.cells[4].week_index, map.cells[4].row), (1, 0));
        assert!(map.cells[5].is_today);
        assert_eq!(map.cells[5].level, 2);
        assert_eq!(map.week_count, 2);
        assert_eq!(map.max_points, 12_000);
    }

    #[test]
    fn sunday_week_start() {
        let start = days_from_civil(2026, 9, 27); // Sunday
        let config = HeatmapConfig {
            week_start: Weekday::Sunday,
            ..HeatmapConfig::default()
        };
        let map = build(&[], start, start + 7, start + 7, &config);
        assert_eq!((map.cells[0].week_index, map.cells[0].row), (0, 0));
        assert_eq!((map.cells[7].week_index, map.cells[7].row), (1, 0));
    }
}
