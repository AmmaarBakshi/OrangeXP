//! Schedule adherence: how much of the planned work actually happened.
//!
//! Each plan item is matched against actual work with the same key, and credit
//! is capped at the planned amount, so over-studying one topic can't hide
//! skipping another.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct WorkBlock {
    /// What the work was for, e.g. a topic id or timetable slot id.
    pub key: String,
    pub minutes: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct AdherenceResult {
    pub planned_minutes: u32,
    pub matched_minutes: u32,
    pub unplanned_minutes: u32,
    /// `None` when nothing was planned.
    pub percent: Option<f64>,
}

fn totals(blocks: &[WorkBlock]) -> BTreeMap<&str, u64> {
    let mut map = BTreeMap::new();
    for b in blocks {
        *map.entry(b.key.as_str()).or_insert(0u64) += u64::from(b.minutes);
    }
    map
}

pub fn compute(planned: &[WorkBlock], actual: &[WorkBlock]) -> AdherenceResult {
    let planned = totals(planned);
    let actual = totals(actual);
    let planned_total: u64 = planned.values().sum();
    let matched: u64 = planned
        .iter()
        .map(|(k, p)| (*p).min(actual.get(k).copied().unwrap_or(0)))
        .sum();
    let actual_total: u64 = actual.values().sum();
    AdherenceResult {
        planned_minutes: planned_total as u32,
        matched_minutes: matched as u32,
        unplanned_minutes: (actual_total - matched) as u32,
        percent: (planned_total > 0).then(|| matched as f64 / planned_total as f64 * 100.0),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn b(key: &str, minutes: u32) -> WorkBlock {
        WorkBlock {
            key: key.into(),
            minutes,
        }
    }

    #[test]
    fn caps_credit_per_key() {
        let r = compute(&[b("a", 60), b("b", 60)], &[b("a", 120), b("c", 30)]);
        assert_eq!(r.planned_minutes, 120);
        assert_eq!(r.matched_minutes, 60);
        assert_eq!(r.unplanned_minutes, 90);
        assert_eq!(r.percent, Some(50.0));
    }

    #[test]
    fn nothing_planned() {
        assert_eq!(compute(&[], &[b("a", 10)]).percent, None);
    }
}
