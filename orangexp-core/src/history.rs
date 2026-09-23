//! Shared historical record type consumed by streak, heatmap and statistics.

use serde::{Deserialize, Serialize};

use crate::category::Category;
use crate::state::DayState;
use crate::time::EpochDay;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct CategoryPoints {
    pub category: Category,
    pub points: i64,
}

/// One evaluated day as stored by the platform.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct DayRecord {
    pub epoch_day: EpochDay,
    pub total_points: i64,
    pub state: DayState,
    pub category_points: Vec<CategoryPoints>,
}

/// Sorts by day and keeps the last record for duplicated days, ignoring future days.
pub(crate) fn canonical(days: &[DayRecord], today: EpochDay) -> Vec<DayRecord> {
    let mut map = std::collections::BTreeMap::new();
    for d in days.iter().filter(|d| d.epoch_day <= today) {
        map.insert(d.epoch_day, d.clone());
    }
    map.into_values().collect()
}
