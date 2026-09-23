//! Turns raw platform measurements into one clean value per metric.
//!
//! - non-finite values are dropped, negative values clamped to zero;
//! - repeated measurements are combined by the metric's [`Aggregation`];
//! - percentages are clamped to 0..=100;
//! - a few metrics are derived when the platform did not supply them.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::metrics::{self, keys, Aggregation, MetricUnit};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct Measurement {
    pub metric: String,
    pub value: f64,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct NormalizedMetrics {
    pub values: BTreeMap<String, f64>,
    pub warnings: Vec<String>,
}

pub fn normalize(measurements: &[Measurement], stride_length_meters: f64) -> NormalizedMetrics {
    let catalog: BTreeMap<String, metrics::MetricDefinition> = metrics::catalog()
        .into_iter()
        .map(|d| (d.key.clone(), d))
        .collect();
    let mut out = NormalizedMetrics::default();

    for m in measurements {
        if !m.value.is_finite() {
            out.warnings
                .push(format!("{}: ignored non-finite value", m.metric));
            continue;
        }
        let mut value = m.value;
        if value < 0.0 {
            out.warnings.push(format!(
                "{}: clamped negative value {} to 0",
                m.metric, value
            ));
            value = 0.0;
        }
        let definition = catalog.get(&m.metric);
        if definition.is_some_and(|d| d.unit == MetricUnit::Percent) {
            value = value.min(100.0);
        }
        let aggregation = definition.map_or(Aggregation::Sum, |d| d.aggregation);
        out.values
            .entry(m.metric.clone())
            .and_modify(|existing| {
                *existing = match aggregation {
                    Aggregation::Sum => *existing + value,
                    Aggregation::Max => existing.max(value),
                    Aggregation::Last => value,
                }
            })
            .or_insert(value);
    }

    derive_missing(&mut out.values, stride_length_meters);
    out
}

fn derive_missing(values: &mut BTreeMap<String, f64>, stride_length_meters: f64) {
    if !values.contains_key(keys::WALKING_METERS) {
        if let Some(steps) = values.get(keys::STEPS).copied() {
            values.insert(
                keys::WALKING_METERS.to_owned(),
                steps * stride_length_meters,
            );
        }
    }
    if !values.contains_key(keys::ATTENDANCE_PERCENT) {
        let scheduled = values.get(keys::CLASSES_SCHEDULED).copied().unwrap_or(0.0);
        if scheduled > 0.0 {
            let attended = values
                .get(keys::CLASSES_ATTENDED)
                .copied()
                .unwrap_or(0.0)
                .min(scheduled);
            values.insert(
                keys::ATTENDANCE_PERCENT.to_owned(),
                attended / scheduled * 100.0,
            );
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn m(metric: &str, value: f64) -> Measurement {
        Measurement {
            metric: metric.into(),
            value,
        }
    }

    #[test]
    fn aggregates_by_metric_definition() {
        let n = normalize(
            &[
                m(keys::STUDY_MINUTES, 30.0),
                m(keys::STUDY_MINUTES, 45.0),
                m(keys::AWAKE_MINUTES, 600.0),
                m(keys::AWAKE_MINUTES, 400.0),
                m("custom.metric", 1.0),
                m("custom.metric", 2.0),
            ],
            0.75,
        );
        assert_eq!(n.values[keys::STUDY_MINUTES], 75.0);
        assert_eq!(n.values[keys::AWAKE_MINUTES], 600.0);
        assert_eq!(n.values["custom.metric"], 3.0);
    }

    #[test]
    fn sanitizes_values() {
        let n = normalize(
            &[
                m(keys::STEPS, f64::NAN),
                m(keys::STUDY_MINUTES, -5.0),
                m(keys::SCHEDULE_ADHERENCE_PERCENT, 130.0),
            ],
            0.75,
        );
        assert!(!n.values.contains_key(keys::STEPS));
        assert_eq!(n.values[keys::STUDY_MINUTES], 0.0);
        assert_eq!(n.values[keys::SCHEDULE_ADHERENCE_PERCENT], 100.0);
        assert_eq!(n.warnings.len(), 2);
    }

    #[test]
    fn derives_distance_and_attendance() {
        let n = normalize(
            &[
                m(keys::STEPS, 1000.0),
                m(keys::CLASSES_ATTENDED, 5.0),
                m(keys::CLASSES_SCHEDULED, 4.0),
            ],
            0.8,
        );
        assert_eq!(n.values[keys::WALKING_METERS], 800.0);
        assert_eq!(n.values[keys::ATTENDANCE_PERCENT], 100.0);

        let explicit = normalize(
            &[m(keys::STEPS, 1000.0), m(keys::WALKING_METERS, 500.0)],
            0.8,
        );
        assert_eq!(explicit.values[keys::WALKING_METERS], 500.0);
    }
}
