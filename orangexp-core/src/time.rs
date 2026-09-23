//! Calendar arithmetic on *epoch days* (days since 1970-01-01).
//!
//! The engine never reads the system clock or time zone database. Callers pass
//! explicit timestamps (epoch milliseconds) and explicit UTC offsets, which keeps
//! every calculation deterministic and reproducible in tests.

use serde::{Deserialize, Serialize};

/// Days since 1970-01-01 in the user's local calendar.
pub type EpochDay = i32;

pub const MS_PER_MINUTE: i64 = 60_000;
pub const MS_PER_DAY: i64 = 86_400_000;
pub const MINUTES_PER_DAY: u32 = 1_440;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Weekday {
    Monday,
    Tuesday,
    Wednesday,
    Thursday,
    Friday,
    Saturday,
    Sunday,
}

impl Weekday {
    pub const ALL: [Weekday; 7] = [
        Weekday::Monday,
        Weekday::Tuesday,
        Weekday::Wednesday,
        Weekday::Thursday,
        Weekday::Friday,
        Weekday::Saturday,
        Weekday::Sunday,
    ];

    /// Zero-based index with Monday = 0.
    pub fn index(self) -> u32 {
        self as u32
    }

    pub fn from_index(index: u32) -> Weekday {
        Self::ALL[(index % 7) as usize]
    }

    /// Number of days from `start` to `self`, going forward (0..=6).
    pub fn days_since(self, start: Weekday) -> u32 {
        (self.index() + 7 - start.index()) % 7
    }
}

/// Weekday of an epoch day. 1970-01-01 was a Thursday.
pub fn weekday_of(day: EpochDay) -> Weekday {
    Weekday::from_index((i64::from(day) + 3).rem_euclid(7) as u32)
}

/// Local epoch day containing `timestamp_ms`, for a fixed UTC offset.
pub fn epoch_day_at(timestamp_ms: i64, utc_offset_minutes: i32) -> EpochDay {
    (timestamp_ms + i64::from(utc_offset_minutes) * MS_PER_MINUTE).div_euclid(MS_PER_DAY)
        as EpochDay
}

/// Local minute of day (0..1440) of `timestamp_ms`, for a fixed UTC offset.
pub fn minute_of_day_at(timestamp_ms: i64, utc_offset_minutes: i32) -> u32 {
    ((timestamp_ms + i64::from(utc_offset_minutes) * MS_PER_MINUTE).rem_euclid(MS_PER_DAY)
        / MS_PER_MINUTE) as u32
}

/// UTC epoch milliseconds of local midnight starting `day`.
pub fn local_midnight_ms(day: EpochDay, utc_offset_minutes: i32) -> i64 {
    i64::from(day) * MS_PER_DAY - i64::from(utc_offset_minutes) * MS_PER_MINUTE
}

/// Epoch day of a proleptic Gregorian date (Howard Hinnant's algorithm).
pub fn days_from_civil(year: i32, month: u32, day: u32) -> EpochDay {
    let y = i64::from(year) - i64::from(month <= 2);
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let m = i64::from(month);
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + i64::from(day) - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    (era * 146_097 + doe - 719_468) as EpochDay
}

/// Gregorian `(year, month, day)` of an epoch day.
pub fn civil_from_days(day: EpochDay) -> (i32, u32, u32) {
    let z = i64::from(day) + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    ((y + i64::from(m <= 2)) as i32, m as u32, d as u32)
}

pub(crate) fn minutes_between(start_ms: i64, end_ms: i64) -> u32 {
    ((end_ms - start_ms).max(0) / MS_PER_MINUTE).min(i64::from(u32::MAX)) as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn civil_round_trip() {
        for day in -800_000..800_000 {
            let (y, m, d) = civil_from_days(day);
            assert_eq!(days_from_civil(y, m, d), day);
        }
    }

    #[test]
    fn known_dates() {
        assert_eq!(days_from_civil(1970, 1, 1), 0);
        assert_eq!(days_from_civil(2000, 3, 1), 11_017);
        assert_eq!(civil_from_days(days_from_civil(2024, 2, 29)), (2024, 2, 29));
        assert_eq!(weekday_of(0), Weekday::Thursday);
        assert_eq!(weekday_of(days_from_civil(2026, 9, 24)), Weekday::Thursday);
        assert_eq!(weekday_of(-1), Weekday::Wednesday);
    }

    #[test]
    fn local_day_boundaries_respect_offset() {
        // 2026-09-23T20:00Z is already 2026-09-24 in IST (+05:30).
        let day = days_from_civil(2026, 9, 24);
        let ts = local_midnight_ms(day, 0) - 4 * 3_600_000;
        assert_eq!(epoch_day_at(ts, 0), day - 1);
        assert_eq!(epoch_day_at(ts, 330), day);
        assert_eq!(minute_of_day_at(ts, 330), 90);
        assert_eq!(epoch_day_at(local_midnight_ms(day, 330), 330), day);
        assert_eq!(
            epoch_day_at(local_midnight_ms(day, -300) - 1, -300),
            day - 1
        );
    }

    #[test]
    fn weekday_distance() {
        assert_eq!(Weekday::Monday.days_since(Weekday::Sunday), 1);
        assert_eq!(Weekday::Sunday.days_since(Weekday::Monday), 6);
        assert_eq!(Weekday::Friday.days_since(Weekday::Friday), 0);
    }
}
