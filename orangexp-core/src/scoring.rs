//! Configurable, explainable scoring rules.
//!
//! A [`ScoringRule`] maps one metric value to points through a [`ScoreCurve`],
//! then applies an optional floor/cap. Every evaluated rule produces a
//! [`RuleContribution`] so the UI can show exactly where each point came from.
//!
//! There are deliberately no streak multipliers or bonuses: a day's points are a
//! pure function of that day's measurements and the configured rules.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::category::Category;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct CurvePoint {
    pub x: f64,
    pub y: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct CurveStep {
    /// The step applies when the value is at least this much.
    pub at_least: f64,
    pub points: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ScoreCurve {
    /// `max(0, value - free_units) * points_per_unit`. A negative rate is a penalty.
    Linear {
        points_per_unit: f64,
        free_units: f64,
    },
    /// Points of the highest step whose `at_least` is satisfied, otherwise 0.
    Steps { steps: Vec<CurveStep> },
    /// Linear interpolation between points, clamped to the first/last `y`.
    /// Expresses diminishing returns, targets and penalties.
    Piecewise { points: Vec<CurvePoint> },
}

impl ScoreCurve {
    pub fn evaluate(&self, value: f64) -> f64 {
        match self {
            ScoreCurve::Linear {
                points_per_unit,
                free_units,
            } => (value - free_units).max(0.0) * points_per_unit,
            ScoreCurve::Steps { steps } => steps
                .iter()
                .filter(|s| value >= s.at_least)
                .max_by(|a, b| a.at_least.total_cmp(&b.at_least))
                .map_or(0.0, |s| s.points),
            ScoreCurve::Piecewise { points } => piecewise(points, value),
        }
    }
}

fn piecewise(points: &[CurvePoint], x: f64) -> f64 {
    let mut sorted: Vec<&CurvePoint> = points.iter().collect();
    sorted.sort_by(|a, b| a.x.total_cmp(&b.x));
    let (Some(first), Some(last)) = (sorted.first(), sorted.last()) else {
        return 0.0;
    };
    if x <= first.x {
        return first.y;
    }
    if x >= last.x {
        return last.y;
    }
    for pair in sorted.windows(2) {
        let (a, b) = (pair[0], pair[1]);
        if x >= a.x && x < b.x {
            let span = b.x - a.x;
            return if span <= 0.0 {
                b.y
            } else {
                a.y + (b.y - a.y) * (x - a.x) / span
            };
        }
    }
    last.y
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct ScoringRule {
    /// Stable identifier, unique within a configuration.
    pub id: String,
    pub label: String,
    /// Metric key from [`crate::metrics::keys`] (or any custom key).
    pub metric: String,
    pub category: Category,
    pub curve: ScoreCurve,
    /// Lower bound on the rule's points (limits penalties).
    pub min_points: Option<f64>,
    /// Upper bound on the rule's points (anti-gaming cap).
    pub max_points: Option<f64>,
    pub enabled: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct RuleContribution {
    pub rule_id: String,
    pub label: String,
    pub metric: String,
    pub category: Category,
    /// The normalized metric value the rule was applied to.
    pub value: f64,
    /// Curve output before floor/cap.
    pub raw_points: f64,
    /// Final integer points after floor/cap and rounding.
    pub points: i64,
    /// True when the cap or floor changed the result.
    pub limited: bool,
}

impl ScoringRule {
    pub fn apply(&self, value: f64) -> RuleContribution {
        let raw = self.curve.evaluate(value);
        let mut limited_value = raw;
        if let Some(max) = self.max_points {
            limited_value = limited_value.min(max);
        }
        if let Some(min) = self.min_points {
            limited_value = limited_value.max(min);
        }
        RuleContribution {
            rule_id: self.id.clone(),
            label: self.label.clone(),
            metric: self.metric.clone(),
            category: self.category,
            value,
            raw_points: raw,
            points: round_points(limited_value),
            limited: limited_value != raw,
        }
    }
}

/// Rounds half away from zero; non-finite values score nothing.
pub fn round_points(value: f64) -> i64 {
    if value.is_finite() {
        value.round() as i64
    } else {
        0
    }
}

/// Applies every enabled rule whose metric was measured, in configuration order.
pub fn evaluate_rules(
    rules: &[ScoringRule],
    metrics: &BTreeMap<String, f64>,
) -> Vec<RuleContribution> {
    rules
        .iter()
        .filter(|r| r.enabled)
        .filter_map(|r| metrics.get(&r.metric).map(|v| r.apply(*v)))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rule(curve: ScoreCurve, min: Option<f64>, max: Option<f64>) -> ScoringRule {
        ScoringRule {
            id: "r".into(),
            label: "R".into(),
            metric: "m".into(),
            category: Category::Walking,
            curve,
            min_points: min,
            max_points: max,
            enabled: true,
        }
    }

    fn pts(xy: &[(f64, f64)]) -> Vec<CurvePoint> {
        xy.iter().map(|&(x, y)| CurvePoint { x, y }).collect()
    }

    #[test]
    fn linear_with_free_units_and_cap() {
        let r = rule(
            ScoreCurve::Linear {
                points_per_unit: 40.0,
                free_units: 0.0,
            },
            None,
            Some(180.0),
        );
        assert_eq!(r.apply(2.0).points, 80);
        let c = r.apply(4.7);
        assert_eq!(c.raw_points.round(), 188.0);
        assert_eq!(c.points, 180);
        assert!(c.limited);
        let free = rule(
            ScoreCurve::Linear {
                points_per_unit: 10.0,
                free_units: 5.0,
            },
            None,
            None,
        );
        assert_eq!(free.apply(3.0).points, 0);
        assert_eq!(free.apply(8.0).points, 30);
    }

    #[test]
    fn steps_pick_highest_satisfied() {
        let curve = ScoreCurve::Steps {
            steps: vec![
                CurveStep {
                    at_least: 60.0,
                    points: 50.0,
                },
                CurveStep {
                    at_least: 45.0,
                    points: 10.0,
                },
                CurveStep {
                    at_least: 120.0,
                    points: 200.0,
                },
            ],
        };
        assert_eq!(curve.evaluate(30.0), 0.0);
        assert_eq!(curve.evaluate(45.0), 10.0);
        assert_eq!(curve.evaluate(119.0), 50.0);
        assert_eq!(curve.evaluate(500.0), 200.0);
    }

    #[test]
    fn piecewise_interpolates_and_clamps() {
        let curve = ScoreCurve::Piecewise {
            points: pts(&[
                (0.0, 0.0),
                (360.0, 2400.0),
                (480.0, 3200.0),
                (720.0, 2000.0),
            ]),
        };
        assert_eq!(curve.evaluate(-5.0), 0.0);
        assert_eq!(curve.evaluate(180.0), 1200.0);
        assert_eq!(curve.evaluate(420.0), 2800.0);
        assert_eq!(curve.evaluate(600.0), 2600.0);
        assert_eq!(curve.evaluate(10_000.0), 2000.0);
        assert_eq!(ScoreCurve::Piecewise { points: vec![] }.evaluate(5.0), 0.0);
    }

    #[test]
    fn penalties_respect_floor() {
        let r = rule(
            ScoreCurve::Piecewise {
                points: pts(&[(0.0, 0.0), (240.0, 0.0), (720.0, -3000.0)]),
            },
            Some(-1000.0),
            None,
        );
        assert_eq!(r.apply(100.0).points, 0);
        assert_eq!(r.apply(400.0).points, -1000);
    }

    #[test]
    fn unmeasured_and_disabled_rules_are_skipped() {
        let mut disabled = rule(
            ScoreCurve::Linear {
                points_per_unit: 1.0,
                free_units: 0.0,
            },
            None,
            None,
        );
        disabled.enabled = false;
        let mut other = rule(
            ScoreCurve::Linear {
                points_per_unit: 1.0,
                free_units: 0.0,
            },
            None,
            None,
        );
        other.id = "o".into();
        other.metric = "absent".into();
        let metrics = BTreeMap::from([("m".to_string(), 10.0)]);
        assert!(evaluate_rules(&[disabled, other], &metrics).is_empty());
    }

    #[test]
    fn rounding_is_half_away_from_zero() {
        assert_eq!(round_points(2.5), 3);
        assert_eq!(round_points(-2.5), -3);
        assert_eq!(round_points(f64::NAN), 0);
    }
}
