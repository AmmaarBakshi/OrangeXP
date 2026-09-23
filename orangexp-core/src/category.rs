//! Activity categories and the lifetime XP pools they roll up into.

use serde::{Deserialize, Serialize};

/// A measurable area of life. New categories can be appended without breaking
/// existing configurations (serialized by name, not ordinal).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Category {
    Sleep,
    Wakefulness,
    Study,
    Syllabus,
    Attendance,
    Walking,
    PhysicalActivity,
    Schedule,
    Travel,
    TaskCompletion,
    PhoneUsage,
}

/// Lifetime counters shown on the history screen.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum XpPool {
    Sleep,
    Study,
    Physical,
    Discipline,
}

impl Category {
    pub const ALL: [Category; 11] = [
        Category::Sleep,
        Category::Wakefulness,
        Category::Study,
        Category::Syllabus,
        Category::Attendance,
        Category::Walking,
        Category::PhysicalActivity,
        Category::Schedule,
        Category::Travel,
        Category::TaskCompletion,
        Category::PhoneUsage,
    ];

    pub fn pool(self) -> XpPool {
        match self {
            Category::Sleep => XpPool::Sleep,
            Category::Study | Category::Syllabus => XpPool::Study,
            Category::Walking | Category::PhysicalActivity => XpPool::Physical,
            Category::Wakefulness
            | Category::Attendance
            | Category::Schedule
            | Category::Travel
            | Category::TaskCompletion
            | Category::PhoneUsage => XpPool::Discipline,
        }
    }
}
