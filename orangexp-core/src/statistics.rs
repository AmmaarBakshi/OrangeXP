//! Historical aggregation: lifetime counters, distributions, rolling averages,
//! consistency and trend. Missing days count as zero wherever a calendar window
//! is involved, so averages reflect reality rather than only good days.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::category::{Category, XpPool};
use crate::history::{canonical, DayRecord};
use crate::state::DayState;
use crate::time::{civil_from_days, weekday_of, EpochDay, Weekday};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct PoolTotal {
    pub pool: XpPool,
    pub points: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct CategoryTotal {
    pub category: Category,
    pub points: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct StateCount {
    pub state: DayState,
    pub days: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct PeriodTotal {
    /// First day of the period (a week start or the 1st of a month).
    pub start_day: EpochDay,
    pub points: i64,
    pub active_days: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct DayPoints {
    pub epoch_day: EpochDay,
    pub points: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct Statistics {
    pub lifetime_points: i64,
    pub lifetime_by_pool: Vec<PoolTotal>,
    pub lifetime_by_category: Vec<CategoryTotal>,
    pub days_tracked: u32,
    pub active_days: u32,
    pub mean_points: f64,
    pub median_points: f64,
    pub std_dev_points: f64,
    pub best_day: Option<DayPoints>,
    pub rolling_average_7: f64,
    pub rolling_average_30: f64,
    /// Share of the last 30 calendar days with positive points, 0..=100.
    pub consistency_percent_30: f64,
    /// Least-squares slope of daily points over the last 30 days (points/day).
    pub trend_per_day_30: f64,
    pub state_distribution: Vec<StateCount>,
    /// The last 12 weeks, oldest first.
    pub weekly: Vec<PeriodTotal>,
    /// The last 12 months, oldest first.
    pub monthly: Vec<PeriodTotal>,
}

pub fn compute(days: &[DayRecord], today: EpochDay, week_start: Weekday) -> Statistics {
    let days = canonical(days, today);
    let by_day: BTreeMap<EpochDay, i64> =
        days.iter().map(|d| (d.epoch_day, d.total_points)).collect();
    let points_on = |day: EpochDay| by_day.get(&day).copied().unwrap_or(0);

    let mut by_category: BTreeMap<Category, i64> = BTreeMap::new();
    let mut by_pool: BTreeMap<XpPool, i64> = BTreeMap::new();
    let mut by_state: BTreeMap<DayState, u32> = BTreeMap::new();
    for d in &days {
        for cp in &d.category_points {
            *by_category.entry(cp.category).or_default() += cp.points;
            *by_pool.entry(cp.category.pool()).or_default() += cp.points;
        }
        *by_state.entry(d.state).or_default() += 1;
    }

    let mut scores: Vec<i64> = days.iter().map(|d| d.total_points).collect();
    scores.sort_unstable();
    let n = scores.len();
    let mean = if n == 0 {
        0.0
    } else {
        scores.iter().sum::<i64>() as f64 / n as f64
    };
    let median = match n {
        0 => 0.0,
        _ if n % 2 == 1 => scores[n / 2] as f64,
        _ => (scores[n / 2 - 1] + scores[n / 2]) as f64 / 2.0,
    };
    let variance = if n == 0 {
        0.0
    } else {
        scores
            .iter()
            .map(|&s| (s as f64 - mean).powi(2))
            .sum::<f64>()
            / n as f64
    };

    let window = |len: i32| {
        (today - len + 1..=today)
            .map(points_on)
            .collect::<Vec<i64>>()
    };
    let last7 = window(7);
    let last30 = window(30);

    let week_offset = weekday_of(today).days_since(week_start) as EpochDay;
    let this_week = today - week_offset;
    let weekly = (0..12)
        .rev()
        .map(|i| {
            let start = this_week - 7 * i;
            period(&by_day, start, start + 6)
        })
        .collect();

    let (year, month, _) = civil_from_days(today);
    let monthly = (0..12)
        .rev()
        .map(|i| {
            let index = year * 12 + month as i32 - 1 - i;
            let (y, m) = (index.div_euclid(12), index.rem_euclid(12) as u32 + 1);
            let next = index + 1;
            let start = crate::time::days_from_civil(y, m, 1);
            let end = crate::time::days_from_civil(
                next.div_euclid(12),
                next.rem_euclid(12) as u32 + 1,
                1,
            ) - 1;
            period(&by_day, start, end)
        })
        .collect();

    Statistics {
        lifetime_points: days.iter().map(|d| d.total_points).sum(),
        lifetime_by_pool: [
            XpPool::Sleep,
            XpPool::Study,
            XpPool::Physical,
            XpPool::Discipline,
        ]
        .into_iter()
        .map(|pool| PoolTotal {
            pool,
            points: by_pool.get(&pool).copied().unwrap_or(0),
        })
        .collect(),
        lifetime_by_category: by_category
            .into_iter()
            .map(|(category, points)| CategoryTotal { category, points })
            .collect(),
        days_tracked: n as u32,
        active_days: days.iter().filter(|d| d.total_points > 0).count() as u32,
        mean_points: mean,
        median_points: median,
        std_dev_points: variance.sqrt(),
        best_day: days
            .iter()
            .max_by(|a, b| {
                a.total_points
                    .cmp(&b.total_points)
                    .then(b.epoch_day.cmp(&a.epoch_day))
            })
            .map(|d| DayPoints {
                epoch_day: d.epoch_day,
                points: d.total_points,
            }),
        rolling_average_7: average(&last7),
        rolling_average_30: average(&last30),
        consistency_percent_30: last30.iter().filter(|&&p| p > 0).count() as f64 / 30.0 * 100.0,
        trend_per_day_30: slope(&last30),
        state_distribution: by_state
            .into_iter()
            .map(|(state, days)| StateCount { state, days })
            .collect(),
        weekly,
        monthly,
    }
}

fn period(by_day: &BTreeMap<EpochDay, i64>, start: EpochDay, end: EpochDay) -> PeriodTotal {
    let range = by_day.range(start..=end);
    PeriodTotal {
        start_day: start,
        points: range.clone().map(|(_, p)| p).sum(),
        active_days: range.filter(|(_, p)| **p > 0).count() as u32,
    }
}

fn average(values: &[i64]) -> f64 {
    if values.is_empty() {
        0.0
    } else {
        values.iter().sum::<i64>() as f64 / values.len() as f64
    }
}

/// Ordinary least-squares slope with x = 0..n.
fn slope(values: &[i64]) -> f64 {
    let n = values.len() as f64;
    if values.len() < 2 {
        return 0.0;
    }
    let mean_x = (n - 1.0) / 2.0;
    let mean_y = average(values);
    let (num, den) = values
        .iter()
        .enumerate()
        .fold((0.0, 0.0), |(num, den), (i, &y)| {
            let dx = i as f64 - mean_x;
            (num + dx * (y as f64 - mean_y), den + dx * dx)
        });
    if den == 0.0 {
        0.0
    } else {
        num / den
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::history::CategoryPoints;
    use crate::time::days_from_civil;

    fn record(day: EpochDay, points: i64, state: DayState) -> DayRecord {
        DayRecord {
            epoch_day: day,
            total_points: points,
            state,
            category_points: vec![
                CategoryPoints {
                    category: Category::Sleep,
                    points: points / 2,
                },
                CategoryPoints {
                    category: Category::Walking,
                    points: points - points / 2,
                },
            ],
        }
    }

    #[test]
    fn lifetime_and_distribution() {
        let today = days_from_civil(2026, 9, 24);
        let days = vec![
            record(today - 2, 1000, DayState::Green),
            record(today - 1, 3000, DayState::Orange),
            record(today, 2000, DayState::Green),
            record(today + 1, 9999, DayState::Black), // future: ignored
        ];
        let s = compute(&days, today, Weekday::Monday);
        assert_eq!(s.lifetime_points, 6000);
        assert_eq!(s.days_tracked, 3);
        assert_eq!(s.mean_points, 2000.0);
        assert_eq!(s.median_points, 2000.0);
        assert_eq!(
            s.best_day,
            Some(DayPoints {
                epoch_day: today - 1,
                points: 3000
            })
        );
        let sleep = s
            .lifetime_by_pool
            .iter()
            .find(|p| p.pool == XpPool::Sleep)
            .unwrap();
        assert_eq!(sleep.points, 3000);
        assert_eq!(
            s.state_distribution,
            vec![
                StateCount {
                    state: DayState::Green,
                    days: 2
                },
                StateCount {
                    state: DayState::Orange,
                    days: 1
                }
            ]
        );
        assert!((s.rolling_average_7 - 6000.0 / 7.0).abs() < 1e-9);
        assert!((s.consistency_percent_30 - 10.0).abs() < 1e-9);
    }

    #[test]
    fn trend_detects_improvement() {
        let today = 1000;
        let days: Vec<_> = (0..30)
            .map(|i| record(today - 29 + i, i64::from(i) * 100, DayState::Green))
            .collect();
        let s = compute(&days, today, Weekday::Monday);
        assert!((s.trend_per_day_30 - 100.0).abs() < 1e-9);
    }

    #[test]
    fn weekly_and_monthly_windows() {
        let today = days_from_civil(2026, 9, 24); // Thursday
        let days = vec![
            record(days_from_civil(2026, 9, 21), 100, DayState::Green), // Monday this week
            record(days_from_civil(2026, 9, 20), 50, DayState::Green),  // Sunday last week
            record(days_from_civil(2026, 8, 31), 70, DayState::Green),
        ];
        let s = compute(&days, today, Weekday::Monday);
        assert_eq!(s.weekly.len(), 12);
        let this_week = s.weekly.last().unwrap();
        assert_eq!(
            (this_week.start_day, this_week.points),
            (days_from_civil(2026, 9, 21), 100)
        );
        assert_eq!(s.weekly[10].points, 50);
        let sep = s.monthly.last().unwrap();
        assert_eq!(
            (sep.start_day, sep.points, sep.active_days),
            (days_from_civil(2026, 9, 1), 150, 2)
        );
        assert_eq!(s.monthly[10].start_day, days_from_civil(2026, 8, 1));
        assert_eq!(s.monthly[10].points, 70);
        assert_eq!(s.monthly[0].start_day, days_from_civil(2025, 10, 1));
    }

    #[test]
    fn empty_history_is_all_zero() {
        let s = compute(&[], 100, Weekday::Monday);
        assert_eq!(s.lifetime_points, 0);
        assert_eq!(s.best_day, None);
        assert_eq!(s.trend_per_day_30, 0.0);
    }
}
