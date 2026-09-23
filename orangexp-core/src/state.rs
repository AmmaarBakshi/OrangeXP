//! Green / Orange / Red / Black daily state classification.
//!
//! Each [`StateRule`] classifies one metric against three configurable
//! thresholds. The overall state is the most severe individual finding. The
//! thresholds are personal rules, not medical guidance.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

/// Ordered from best to worst so `max()` yields the most severe state.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DayState {
    Green,
    Orange,
    Red,
    Black,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ThresholdDirection {
    /// Values at or above the thresholds are worse (e.g. hours awake).
    HigherIsWorse,
    /// Values at or below the thresholds are worse (e.g. hours slept).
    LowerIsWorse,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct StateRule {
    pub id: String,
    pub label: String,
    pub metric: String,
    pub direction: ThresholdDirection,
    pub orange_at: f64,
    pub red_at: f64,
    pub black_at: f64,
    pub enabled: bool,
}

impl StateRule {
    pub fn classify(&self, value: f64) -> DayState {
        let reached = |threshold: f64| match self.direction {
            ThresholdDirection::HigherIsWorse => value >= threshold,
            ThresholdDirection::LowerIsWorse => value <= threshold,
        };
        if reached(self.black_at) {
            DayState::Black
        } else if reached(self.red_at) {
            DayState::Red
        } else if reached(self.orange_at) {
            DayState::Orange
        } else {
            DayState::Green
        }
    }

    /// Thresholds must be monotonic in the direction of severity.
    pub fn thresholds_are_ordered(&self) -> bool {
        match self.direction {
            ThresholdDirection::HigherIsWorse => {
                self.orange_at <= self.red_at && self.red_at <= self.black_at
            }
            ThresholdDirection::LowerIsWorse => {
                self.orange_at >= self.red_at && self.red_at >= self.black_at
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct StateFinding {
    pub rule_id: String,
    pub label: String,
    pub metric: String,
    pub value: f64,
    pub state: DayState,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct StateEvaluation {
    pub state: DayState,
    /// The first rule (in configuration order) that produced the overall state,
    /// or `None` when nothing was evaluated or everything is green.
    pub decisive_rule_id: Option<String>,
    pub findings: Vec<StateFinding>,
}

pub fn classify(rules: &[StateRule], metrics: &BTreeMap<String, f64>) -> StateEvaluation {
    let findings: Vec<StateFinding> = rules
        .iter()
        .filter(|r| r.enabled)
        .filter_map(|r| {
            metrics.get(&r.metric).map(|&value| StateFinding {
                rule_id: r.id.clone(),
                label: r.label.clone(),
                metric: r.metric.clone(),
                value,
                state: r.classify(value),
            })
        })
        .collect();
    let state = findings
        .iter()
        .map(|f| f.state)
        .max()
        .unwrap_or(DayState::Green);
    let decisive_rule_id = if state == DayState::Green {
        None
    } else {
        findings
            .iter()
            .find(|f| f.state == state)
            .map(|f| f.rule_id.clone())
    };
    StateEvaluation {
        state,
        decisive_rule_id,
        findings,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn awake_rule() -> StateRule {
        StateRule {
            id: "awake".into(),
            label: "Awake".into(),
            metric: "awake.minutes".into(),
            direction: ThresholdDirection::HigherIsWorse,
            orange_at: 16.0 * 60.0,
            red_at: 18.0 * 60.0,
            black_at: 21.0 * 60.0,
            enabled: true,
        }
    }

    fn sleep_rule() -> StateRule {
        StateRule {
            id: "sleep".into(),
            label: "Sleep".into(),
            metric: "sleep.minutes".into(),
            direction: ThresholdDirection::LowerIsWorse,
            orange_at: 360.0,
            red_at: 270.0,
            black_at: 180.0,
            enabled: true,
        }
    }

    #[test]
    fn awake_example_bands() {
        let r = awake_rule();
        assert_eq!(r.classify(10.0 * 60.0), DayState::Green);
        assert_eq!(r.classify(16.0 * 60.0), DayState::Orange);
        assert_eq!(r.classify(19.0 * 60.0), DayState::Red);
        assert_eq!(r.classify(21.0 * 60.0), DayState::Black);
    }

    #[test]
    fn lower_is_worse_bands() {
        let r = sleep_rule();
        assert_eq!(r.classify(480.0), DayState::Green);
        assert_eq!(r.classify(360.0), DayState::Orange);
        assert_eq!(r.classify(200.0), DayState::Red);
        assert_eq!(r.classify(60.0), DayState::Black);
        assert!(r.thresholds_are_ordered());
    }

    #[test]
    fn overall_state_is_worst_finding() {
        let metrics = BTreeMap::from([
            ("awake.minutes".to_string(), 1_000.0),
            ("sleep.minutes".to_string(), 250.0),
        ]);
        let eval = classify(&[awake_rule(), sleep_rule()], &metrics);
        assert_eq!(eval.state, DayState::Red);
        assert_eq!(eval.decisive_rule_id.as_deref(), Some("sleep"));
        assert_eq!(eval.findings.len(), 2);
    }

    #[test]
    fn missing_metrics_are_not_evaluated() {
        let eval = classify(&[awake_rule()], &BTreeMap::new());
        assert_eq!(eval.state, DayState::Green);
        assert!(eval.findings.is_empty());
        assert!(eval.decisive_rule_id.is_none());
    }
}
