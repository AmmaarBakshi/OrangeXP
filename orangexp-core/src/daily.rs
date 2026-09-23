//! One day in, one explained result out.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::category::Category;
use crate::config::EngineConfig;
use crate::heatmap::{self, Heatmap};
use crate::history::{CategoryPoints, DayRecord};
use crate::normalization::{normalize, Measurement};
use crate::scoring::{evaluate_rules, RuleContribution};
use crate::state::{classify, StateEvaluation};
use crate::statistics::{self, Statistics};
use crate::streak::{self, StreakResult};
use crate::time::EpochDay;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct DayInput {
    pub epoch_day: EpochDay,
    pub measurements: Vec<Measurement>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct DayEvaluation {
    pub epoch_day: EpochDay,
    /// The single daily score: the plain sum of all contributions.
    pub total_points: i64,
    pub contributions: Vec<RuleContribution>,
    pub category_points: Vec<CategoryPoints>,
    pub state: StateEvaluation,
    /// Normalized metric values the rules were applied to.
    pub metrics: Vec<Measurement>,
    pub warnings: Vec<String>,
}

impl DayEvaluation {
    pub fn to_record(&self) -> DayRecord {
        DayRecord {
            epoch_day: self.epoch_day,
            total_points: self.total_points,
            state: self.state.state,
            category_points: self.category_points.clone(),
        }
    }
}

pub fn evaluate_day(input: &DayInput, config: &EngineConfig) -> DayEvaluation {
    let normalized = normalize(&input.measurements, config.stride_length_meters);
    let contributions = evaluate_rules(&config.scoring_rules, &normalized.values);
    let state = classify(&config.state_rules, &normalized.values);

    let mut by_category: BTreeMap<Category, i64> = BTreeMap::new();
    for c in &contributions {
        *by_category.entry(c.category).or_default() += c.points;
    }

    DayEvaluation {
        epoch_day: input.epoch_day,
        total_points: contributions.iter().map(|c| c.points).sum(),
        category_points: by_category
            .into_iter()
            .map(|(category, points)| CategoryPoints { category, points })
            .collect(),
        contributions,
        state,
        metrics: normalized
            .values
            .into_iter()
            .map(|(metric, value)| Measurement { metric, value })
            .collect(),
        warnings: normalized.warnings,
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct HistoryInput {
    pub days: Vec<DayRecord>,
    pub today: EpochDay,
    pub heatmap_start_day: EpochDay,
    pub heatmap_end_day: EpochDay,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct HistoryEvaluation {
    pub streak: StreakResult,
    pub heatmap: Heatmap,
    pub statistics: Statistics,
}

/// Everything the history screen needs, computed in a single call.
pub fn evaluate_history(input: &HistoryInput, config: &EngineConfig) -> HistoryEvaluation {
    HistoryEvaluation {
        streak: streak::compute(&input.days, input.today, &config.streak),
        heatmap: heatmap::build(
            &input.days,
            input.heatmap_start_day,
            input.heatmap_end_day,
            input.today,
            &config.heatmap,
        ),
        statistics: statistics::compute(&input.days, input.today, config.heatmap.week_start),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::metrics::keys;
    use crate::state::DayState;

    fn m(metric: &str, value: f64) -> Measurement {
        Measurement {
            metric: metric.into(),
            value,
        }
    }

    #[test]
    fn realistic_day_with_default_rules() {
        let input = DayInput {
            epoch_day: 20_720,
            measurements: vec![
                m(keys::SLEEP_MINUTES, 381.0), // 6h21m
                m(keys::AWAKE_MINUTES, 14.0 * 60.0 + 32.0),
                m(keys::STEPS, 6_267.0), // ≈4.7 km
                m(keys::STUDY_MINUTES, 160.0),
                m(keys::SYLLABUS_COMPLETED_MINUTES, 120.0),
                m(keys::CLASSES_ATTENDED, 3.0),
                m(keys::CLASSES_SCHEDULED, 4.0),
                m(keys::SCHEDULE_ADHERENCE_PERCENT, 92.0),
            ],
        };
        let eval = evaluate_day(&input, &EngineConfig::default());
        let points = |id: &str| {
            eval.contributions
                .iter()
                .find(|c| c.rule_id == id)
                .map(|c| c.points)
        };
        assert_eq!(points("sleep.duration"), Some(2610));
        assert_eq!(points("walking.steps"), Some(2507));
        assert_eq!(points("study.time"), Some(2400));
        assert_eq!(points("syllabus.progress"), Some(1200));
        assert_eq!(points("attendance.classes"), Some(1200));
        assert_eq!(points("schedule.adherence"), Some(1680));
        assert_eq!(points("tasks.completed"), None);
        assert_eq!(eval.total_points, 2610 + 2507 + 2400 + 1200 + 1200 + 1680);
        assert_eq!(
            eval.total_points,
            eval.category_points.iter().map(|c| c.points).sum::<i64>()
        );
        assert_eq!(eval.state.state, DayState::Green);
        let meters = eval
            .metrics
            .iter()
            .find(|x| x.metric == keys::WALKING_METERS)
            .unwrap()
            .value;
        assert!((meters - 4_700.25).abs() < 1e-6);
    }

    #[test]
    fn identical_inputs_give_identical_outputs() {
        let input = DayInput {
            epoch_day: 1,
            measurements: vec![m(keys::STEPS, 12_345.0), m(keys::SLEEP_MINUTES, 200.0)],
        };
        let config = EngineConfig::default();
        let first = evaluate_day(&input, &config);
        assert_eq!(first.state.state, DayState::Red);
        for _ in 0..100 {
            assert_eq!(evaluate_day(&input, &config), first);
        }
    }

    #[test]
    fn caps_prevent_farming() {
        let eval = evaluate_day(
            &DayInput {
                epoch_day: 1,
                measurements: vec![m(keys::STEPS, 1_000_000.0)],
            },
            &EngineConfig::default(),
        );
        assert_eq!(eval.total_points, 5_000);
        assert!(eval.contributions[0].limited);
    }

    #[test]
    fn history_bundle() {
        let config = EngineConfig::default();
        let days: Vec<DayRecord> = (0..5)
            .map(|i| DayRecord {
                epoch_day: 100 + i,
                total_points: 6_000,
                state: DayState::Green,
                category_points: vec![],
            })
            .collect();
        let h = evaluate_history(
            &HistoryInput {
                days,
                today: 104,
                heatmap_start_day: 90,
                heatmap_end_day: 104,
            },
            &config,
        );
        assert_eq!(h.streak.current, 5);
        assert_eq!(h.heatmap.cells.len(), 15);
        assert_eq!(h.statistics.lifetime_points, 30_000);
    }
}
