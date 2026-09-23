//! The complete, user-editable engine configuration.
//!
//! Stored by the platform as JSON. Unknown fields are ignored and missing
//! fields fall back to defaults, so configurations survive engine upgrades;
//! `schema_version` exists for explicit migrations when semantics change.

use std::collections::HashSet;

use serde::{Deserialize, Serialize};

use crate::category::Category;
use crate::error::EngineError;
use crate::heatmap::{HeatmapConfig, HeatmapScale};
use crate::metrics::keys;
use crate::scoring::{CurvePoint, CurveStep, ScoreCurve, ScoringRule};
use crate::sleep::SleepConfig;
use crate::state::{StateRule, ThresholdDirection};
use crate::streak::StreakRule;
use crate::syllabus::StudyConfig;
use crate::travel::TravelConfig;

pub const CONFIG_SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
#[serde(default)]
pub struct EngineConfig {
    pub schema_version: u32,
    pub scoring_rules: Vec<ScoringRule>,
    pub state_rules: Vec<StateRule>,
    pub sleep: SleepConfig,
    pub streak: StreakRule,
    pub heatmap: HeatmapConfig,
    pub travel: TravelConfig,
    pub study: StudyConfig,
    pub stride_length_meters: f64,
}

impl Default for EngineConfig {
    fn default() -> Self {
        Self {
            schema_version: CONFIG_SCHEMA_VERSION,
            scoring_rules: default_scoring_rules(),
            state_rules: default_state_rules(),
            sleep: SleepConfig::default(),
            streak: StreakRule::default(),
            heatmap: HeatmapConfig::default(),
            travel: TravelConfig::default(),
            study: StudyConfig::default(),
            stride_length_meters: 0.75,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct ConfigIssue {
    /// Dotted path of the offending field, e.g. `scoring_rules[2].curve`.
    pub path: String,
    pub message: String,
}

fn points(xy: &[(f64, f64)]) -> ScoreCurve {
    ScoreCurve::Piecewise {
        points: xy.iter().map(|&(x, y)| CurvePoint { x, y }).collect(),
    }
}

fn linear(points_per_unit: f64) -> ScoreCurve {
    ScoreCurve::Linear {
        points_per_unit,
        free_units: 0.0,
    }
}

fn rule(
    id: &str,
    label: &str,
    metric: &str,
    category: Category,
    curve: ScoreCurve,
    max: Option<f64>,
) -> ScoringRule {
    ScoringRule {
        id: id.into(),
        label: label.into(),
        metric: metric.into(),
        category,
        curve,
        min_points: None,
        max_points: max,
        enabled: true,
    }
}

/// Default rules. A solid day lands around 12–15k points; every rule is capped
/// so no single activity can be farmed indefinitely.
pub fn default_scoring_rules() -> Vec<ScoringRule> {
    vec![
        rule(
            "sleep.duration",
            "Sleep",
            keys::SLEEP_MINUTES,
            Category::Sleep,
            points(&[
                (0.0, 0.0),
                (180.0, 600.0),
                (360.0, 2400.0),
                (420.0, 3000.0),
                (480.0, 3200.0),
                (600.0, 2800.0),
                (720.0, 2000.0),
            ]),
            None,
        ),
        rule(
            "walking.steps",
            "Walking",
            keys::STEPS,
            Category::Walking,
            linear(0.4),
            Some(5_000.0),
        ),
        rule(
            "activity.active",
            "Physical activity",
            keys::ACTIVE_MINUTES,
            Category::PhysicalActivity,
            linear(40.0),
            Some(2_400.0),
        ),
        rule(
            "study.time",
            "Study",
            keys::STUDY_MINUTES,
            Category::Study,
            linear(15.0),
            Some(4_500.0),
        ),
        rule(
            "syllabus.progress",
            "Syllabus progress",
            keys::SYLLABUS_COMPLETED_MINUTES,
            Category::Syllabus,
            linear(10.0),
            Some(3_000.0),
        ),
        rule(
            "attendance.classes",
            "Attendance",
            keys::CLASSES_ATTENDED,
            Category::Attendance,
            linear(400.0),
            Some(2_400.0),
        ),
        rule(
            "schedule.adherence",
            "Schedule adherence",
            keys::SCHEDULE_ADHERENCE_PERCENT,
            Category::Schedule,
            points(&[(0.0, 0.0), (50.0, 400.0), (80.0, 1_200.0), (100.0, 2_000.0)]),
            None,
        ),
        rule(
            "travel.on_time",
            "Left on time",
            keys::ON_TIME_DEPARTURES,
            Category::Travel,
            ScoreCurve::Steps {
                steps: vec![CurveStep {
                    at_least: 1.0,
                    points: 500.0,
                }],
            },
            None,
        ),
        rule(
            "tasks.completed",
            "Tasks",
            keys::TASKS_COMPLETED,
            Category::TaskCompletion,
            linear(150.0),
            Some(1_500.0),
        ),
        ScoringRule {
            enabled: false,
            min_points: Some(-3_000.0),
            ..rule(
                "phone.screen_time",
                "Screen time",
                keys::SCREEN_MINUTES,
                Category::PhoneUsage,
                points(&[
                    (0.0, 0.0),
                    (240.0, 0.0),
                    (480.0, -1_500.0),
                    (720.0, -3_000.0),
                ]),
                None,
            )
        },
    ]
}

fn state_rule(
    id: &str,
    label: &str,
    metric: &str,
    direction: ThresholdDirection,
    t: (f64, f64, f64),
    enabled: bool,
) -> StateRule {
    StateRule {
        id: id.into(),
        label: label.into(),
        metric: metric.into(),
        direction,
        orange_at: t.0,
        red_at: t.1,
        black_at: t.2,
        enabled,
    }
}

/// Default personal thresholds. These are adjustable rules, not medical advice.
pub fn default_state_rules() -> Vec<StateRule> {
    vec![
        state_rule(
            "awake.continuous",
            "Continuous awake time",
            keys::AWAKE_MINUTES,
            ThresholdDirection::HigherIsWorse,
            (16.0 * 60.0, 18.0 * 60.0, 21.0 * 60.0),
            true,
        ),
        state_rule(
            "sleep.short",
            "Short sleep",
            keys::SLEEP_MINUTES,
            ThresholdDirection::LowerIsWorse,
            (360.0, 270.0, 180.0),
            true,
        ),
        state_rule(
            "phone.screen",
            "Screen time",
            keys::SCREEN_MINUTES,
            ThresholdDirection::HigherIsWorse,
            (360.0, 540.0, 720.0),
            false,
        ),
    ]
}

impl EngineConfig {
    pub fn from_json(json: &str) -> Result<Self, EngineError> {
        let config: EngineConfig =
            serde_json::from_str(json).map_err(|e| EngineError::InvalidConfig {
                reason: e.to_string(),
            })?;
        Ok(config.migrated())
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string_pretty(self).expect("EngineConfig is always serializable")
    }

    /// Upgrades older schema versions. Version 1 is the first public schema.
    pub fn migrated(mut self) -> Self {
        if self.schema_version < CONFIG_SCHEMA_VERSION {
            self.schema_version = CONFIG_SCHEMA_VERSION;
        }
        self
    }

    pub fn validate(&self) -> Vec<ConfigIssue> {
        let mut issues = Vec::new();
        let mut issue = |path: String, message: &str| {
            issues.push(ConfigIssue {
                path,
                message: message.to_owned(),
            })
        };

        let mut ids = HashSet::new();
        for (i, r) in self.scoring_rules.iter().enumerate() {
            let p = format!("scoring_rules[{i}]");
            if r.id.trim().is_empty() {
                issue(format!("{p}.id"), "must not be empty");
            } else if !ids.insert(r.id.as_str()) {
                issue(format!("{p}.id"), "duplicate rule id");
            }
            if r.metric.trim().is_empty() {
                issue(format!("{p}.metric"), "must not be empty");
            }
            if let (Some(min), Some(max)) = (r.min_points, r.max_points) {
                if min > max {
                    issue(format!("{p}.min_points"), "must not exceed max_points");
                }
            }
            if [r.min_points, r.max_points]
                .iter()
                .flatten()
                .any(|v| !v.is_finite())
            {
                issue(p.clone(), "limits must be finite");
            }
            match &r.curve {
                ScoreCurve::Linear {
                    points_per_unit,
                    free_units,
                } => {
                    if !points_per_unit.is_finite() || !free_units.is_finite() {
                        issue(format!("{p}.curve"), "linear parameters must be finite");
                    }
                }
                ScoreCurve::Steps { steps } => {
                    if steps.is_empty() {
                        issue(format!("{p}.curve"), "needs at least one step");
                    }
                    if steps
                        .iter()
                        .any(|s| !s.at_least.is_finite() || !s.points.is_finite())
                    {
                        issue(format!("{p}.curve"), "steps must be finite");
                    }
                }
                ScoreCurve::Piecewise { points } => {
                    if points.is_empty() {
                        issue(format!("{p}.curve"), "needs at least one point");
                    }
                    if points.iter().any(|c| !c.x.is_finite() || !c.y.is_finite()) {
                        issue(format!("{p}.curve"), "points must be finite");
                    }
                    if points.windows(2).any(|w| w[1].x <= w[0].x) {
                        issue(format!("{p}.curve"), "x values must be strictly increasing");
                    }
                }
            }
        }

        let mut state_ids = HashSet::new();
        for (i, r) in self.state_rules.iter().enumerate() {
            let p = format!("state_rules[{i}]");
            if r.id.trim().is_empty() {
                issue(format!("{p}.id"), "must not be empty");
            } else if !state_ids.insert(r.id.as_str()) {
                issue(format!("{p}.id"), "duplicate rule id");
            }
            if ![r.orange_at, r.red_at, r.black_at]
                .iter()
                .all(|v| v.is_finite())
            {
                issue(p.clone(), "thresholds must be finite");
            } else if !r.thresholds_are_ordered() {
                issue(
                    p.clone(),
                    "thresholds must increase in severity: orange, then red, then black",
                );
            }
        }

        if self.sleep.inactivity_threshold_minutes == 0 {
            issue(
                "sleep.inactivity_threshold_minutes".into(),
                "must be positive",
            );
        }
        if self.sleep.typical_window_start_minute >= 1440
            || self.sleep.typical_window_end_minute >= 1440
        {
            issue(
                "sleep.typical_window".into(),
                "minutes of day must be below 1440",
            );
        }
        if let HeatmapScale::TargetRelative { target_points } = self.heatmap.scale {
            if target_points <= 0 {
                issue("heatmap.scale.target_points".into(), "must be positive");
            }
        }
        if !(1..=100).contains(&self.travel.history_percentile) {
            issue("travel.history_percentile".into(), "must be within 1..=100");
        }
        if self.travel.rounding_minutes == 0 || self.travel.rounding_minutes > 60 {
            issue("travel.rounding_minutes".into(), "must be within 1..=60");
        }
        if self.study.chunk_minutes == 0 || self.study.min_block_minutes == 0 {
            issue(
                "study.chunk_minutes".into(),
                "study blocks must be positive",
            );
        } else if self.study.min_block_minutes > self.study.chunk_minutes {
            issue(
                "study.min_block_minutes".into(),
                "must not exceed chunk_minutes",
            );
        }
        if !(0.3..=2.0).contains(&self.stride_length_meters) {
            issue("stride_length_meters".into(), "must be within 0.3..=2.0");
        }
        issues
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_config_is_valid() {
        assert_eq!(EngineConfig::default().validate(), vec![]);
    }

    #[test]
    fn json_round_trip() {
        let config = EngineConfig::default();
        let parsed = EngineConfig::from_json(&config.to_json()).unwrap();
        assert_eq!(parsed, config);
    }

    #[test]
    fn partial_json_uses_defaults() {
        let parsed =
            EngineConfig::from_json(r#"{"stride_length_meters": 0.8, "future_field": true}"#)
                .unwrap();
        assert_eq!(parsed.stride_length_meters, 0.8);
        assert_eq!(parsed.scoring_rules, default_scoring_rules());
    }

    #[test]
    fn malformed_json_is_an_error() {
        assert!(matches!(
            EngineConfig::from_json("{"),
            Err(EngineError::InvalidConfig { .. })
        ));
    }

    #[test]
    fn detects_invalid_rules() {
        let mut config = EngineConfig::default();
        config.scoring_rules[1].id = config.scoring_rules[0].id.clone();
        config.scoring_rules[0].curve = points(&[(10.0, 0.0), (5.0, 1.0)]);
        config.state_rules[0].red_at = 0.0;
        config.travel.rounding_minutes = 0;
        let paths: Vec<String> = config.validate().into_iter().map(|i| i.path).collect();
        assert!(paths.contains(&"scoring_rules[1].id".to_string()));
        assert!(paths.contains(&"scoring_rules[0].curve".to_string()));
        assert!(paths.contains(&"state_rules[0]".to_string()));
        assert!(paths.contains(&"travel.rounding_minutes".to_string()));
    }
}
