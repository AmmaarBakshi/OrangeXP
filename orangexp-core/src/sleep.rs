//! Sleep estimation from phone usage events.
//!
//! The platform reports raw device events. This module turns them into
//! *estimated* sleep sessions:
//!
//! 1. **Active intervals** start at an unlock or interaction and end at the next
//!    screen-off. A screen that lights up for a notification is *not* activity.
//! 2. Active intervals separated by less than the inactivity threshold form one
//!    **episode** of phone use.
//! 3. The gap between two episodes is at least the threshold long and is a sleep
//!    **candidate**. Waking up requires an unlock *and* intentional interaction
//!    (an app brought to the foreground). A short episode (≤
//!    `brief_wake_tolerance_minutes`) with no interaction, followed by more
//!    inactivity, is an **interruption** rather than a wake-up.
//! 4. Sleep starts either at the last activity or once the threshold elapsed
//!    ([`SleepOnset`]) and ends at the first unlock of the waking episode.
//!
//! Phone inactivity cannot prove sleep, so every session carries a transparent
//! confidence score and the UI must label it as an estimate.

use serde::{Deserialize, Serialize};

use crate::time::{epoch_day_at, local_midnight_ms, minutes_between, MS_PER_MINUTE};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DeviceEventKind {
    /// Screen turned on, possibly by a notification. Not activity by itself.
    ScreenOn,
    /// Screen turned off; ends an active interval.
    ScreenOff,
    /// Keyguard dismissed by the user.
    Unlock,
    /// The user brought an app to the foreground or otherwise interacted.
    Interaction,
    /// A notification was posted. Never counts as activity.
    Notification,
    PowerConnected,
    PowerDisconnected,
}

impl DeviceEventKind {
    /// Tie-break order for events sharing a timestamp: ending activity first
    /// keeps zero-length artefacts from merging distinct intervals.
    fn tie_rank(self) -> u8 {
        match self {
            DeviceEventKind::ScreenOff => 0,
            DeviceEventKind::PowerConnected | DeviceEventKind::PowerDisconnected => 1,
            DeviceEventKind::Notification | DeviceEventKind::ScreenOn => 2,
            DeviceEventKind::Unlock => 3,
            DeviceEventKind::Interaction => 4,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct DeviceEvent {
    pub timestamp_ms: i64,
    pub kind: DeviceEventKind,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SleepOnset {
    /// Sleep starts once the inactivity threshold has elapsed (conservative).
    AfterThreshold,
    /// Sleep starts at the last meaningful activity.
    AtLastActivity,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
#[serde(default)]
pub struct SleepConfig {
    pub inactivity_threshold_minutes: u32,
    pub onset: SleepOnset,
    /// Unlock-only episodes (no app interaction) no longer than this,
    /// followed by more inactivity, are interruptions rather than wake-ups.
    pub brief_wake_tolerance_minutes: u32,
    /// Estimated sessions shorter than this are discarded.
    pub min_sleep_minutes: u32,
    /// Sessions longer than this are kept but receive low confidence.
    pub max_plausible_sleep_minutes: u32,
    /// Only rest at least this long resets the continuous-awake clock, so a
    /// quiet hour or a short nap doesn't erase a long day.
    pub awake_reset_minutes: u32,
    /// Local minute of day at which the usual sleep window starts (may wrap midnight).
    pub typical_window_start_minute: u32,
    pub typical_window_end_minute: u32,
}

impl Default for SleepConfig {
    fn default() -> Self {
        Self {
            inactivity_threshold_minutes: 45,
            onset: SleepOnset::AfterThreshold,
            brief_wake_tolerance_minutes: 10,
            min_sleep_minutes: 15,
            max_plausible_sleep_minutes: 14 * 60,
            awake_reset_minutes: 90,
            typical_window_start_minute: 21 * 60,
            typical_window_end_minute: 11 * 60,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SleepDetectionMethod {
    InactivityGap,
    InactivityGapWithInterruptions,
    Manual,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ConfidenceLevel {
    Low,
    Medium,
    High,
}

impl ConfidenceLevel {
    pub fn from_score(score: u8) -> Self {
        match score {
            70.. => ConfidenceLevel::High,
            40.. => ConfidenceLevel::Medium,
            _ => ConfidenceLevel::Low,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct SleepSession {
    pub last_activity_ms: i64,
    pub start_ms: i64,
    pub end_ms: i64,
    /// Estimated asleep minutes, excluding interruptions.
    pub duration_minutes: u32,
    pub interruption_count: u32,
    pub interruption_minutes: u32,
    /// 0..=100, see [`confidence`].
    pub confidence: u8,
    pub confidence_level: ConfidenceLevel,
    pub method: SleepDetectionMethod,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct OngoingSleep {
    pub last_activity_ms: i64,
    pub start_ms: i64,
    pub elapsed_minutes: u32,
    pub interruption_count: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct SleepAnalysis {
    pub sessions: Vec<SleepSession>,
    pub ongoing: Option<OngoingSleep>,
    /// End of the most recent completed sleep, if the user is awake.
    pub awake_since_ms: Option<i64>,
    pub current_awake_minutes: Option<u32>,
    /// Total active (screen-on, unlocked) minutes observed in the input.
    pub active_minutes: u32,
    pub unlock_count: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Span {
    start: i64,
    end: i64,
}

/// A span of active use and whether it contained intentional interaction.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Activity {
    span: Span,
    interacted: bool,
}

impl Span {
    fn len(self) -> i64 {
        self.end - self.start
    }
}

/// Unlock/interaction .. screen-off intervals. An interval still open at the end
/// of the input is considered active until `now_ms` (the screen is on).
fn active_intervals(events: &[DeviceEvent], now_ms: i64) -> (Vec<Activity>, bool) {
    let mut out = Vec::new();
    let mut open: Option<(i64, bool)> = None;
    for e in events {
        match e.kind {
            DeviceEventKind::Unlock => {
                open.get_or_insert((e.timestamp_ms, false));
            }
            DeviceEventKind::Interaction => {
                open.get_or_insert((e.timestamp_ms, true)).1 = true;
            }
            DeviceEventKind::ScreenOff => {
                if let Some((start, interacted)) = open.take() {
                    out.push(Activity {
                        span: Span {
                            start,
                            end: e.timestamp_ms,
                        },
                        interacted,
                    });
                }
            }
            _ => {}
        }
    }
    let still_active = open.is_some();
    if let Some((start, interacted)) = open {
        out.push(Activity {
            span: Span {
                start,
                end: now_ms.max(start),
            },
            interacted,
        });
    }
    (out, still_active)
}

/// Groups active intervals whose separating gap is shorter than `threshold_ms`.
fn episodes(intervals: &[Activity], threshold_ms: i64) -> Vec<Activity> {
    let mut out: Vec<Activity> = Vec::new();
    for &a in intervals {
        match out.last_mut() {
            Some(last) if a.span.start - last.span.end < threshold_ms => {
                last.span.end = last.span.end.max(a.span.end);
                last.interacted |= a.interacted;
            }
            _ => out.push(a),
        }
    }
    out
}

pub fn analyze(
    events: &[DeviceEvent],
    now_ms: i64,
    utc_offset_minutes: i32,
    config: &SleepConfig,
) -> SleepAnalysis {
    let mut sorted: Vec<DeviceEvent> = events
        .iter()
        .copied()
        .filter(|e| e.timestamp_ms <= now_ms)
        .collect();
    sorted.sort_by_key(|e| (e.timestamp_ms, e.kind.tie_rank()));

    let threshold_ms = i64::from(config.inactivity_threshold_minutes.max(1)) * MS_PER_MINUTE;
    let tolerance_ms = i64::from(config.brief_wake_tolerance_minutes) * MS_PER_MINUTE;

    let (intervals, still_active) = active_intervals(&sorted, now_ms);
    let eps = episodes(&intervals, threshold_ms);

    // Gaps between consecutive episodes, plus a trailing "ongoing" gap when the
    // phone has been idle for at least the threshold up to now.
    let mut gaps: Vec<(Span, bool)> = eps
        .windows(2)
        .map(|w| {
            (
                Span {
                    start: w[0].span.end,
                    end: w[1].span.start,
                },
                false,
            )
        })
        .collect();
    if let Some(last) = eps.last() {
        if !still_active && now_ms - last.span.end >= threshold_ms {
            gaps.push((
                Span {
                    start: last.span.end,
                    end: now_ms,
                },
                true,
            ));
        }
    }
    let is_interruption = |e: &Activity| !e.interacted && e.span.len() <= tolerance_ms;

    let mut sessions = Vec::new();
    let mut ongoing = None;
    let mut k = 0;
    while k < gaps.len() {
        let first_gap = gaps[k].0;
        let mut interruptions = 0u32;
        let mut interruption_ms = 0i64;
        // Episode k+1 separates gap k from gap k+1.
        while !gaps[k].1 && k + 1 < gaps.len() && is_interruption(&eps[k + 1]) {
            interruptions += 1;
            interruption_ms += eps[k + 1].span.len();
            k += 1;
        }
        let last_activity_ms = first_gap.start;
        let start_ms = match config.onset {
            SleepOnset::AfterThreshold => first_gap.start + threshold_ms,
            SleepOnset::AtLastActivity => first_gap.start,
        };
        let (gap_end, is_ongoing) = gaps[k];
        if is_ongoing {
            ongoing = Some(OngoingSleep {
                last_activity_ms,
                start_ms,
                elapsed_minutes: minutes_between(start_ms, now_ms),
                interruption_count: interruptions,
            });
            break;
        }
        let end_ms = gap_end.end;
        let duration_minutes = minutes_between(start_ms, end_ms - interruption_ms);
        if duration_minutes >= config.min_sleep_minutes {
            let power_on = charging_throughout(&sorted, start_ms, end_ms);
            let confidence = confidence(
                start_ms,
                end_ms,
                duration_minutes,
                interruptions,
                power_on,
                utc_offset_minutes,
                config,
            );
            sessions.push(SleepSession {
                last_activity_ms,
                start_ms,
                end_ms,
                duration_minutes,
                interruption_count: interruptions,
                interruption_minutes: minutes_between(0, interruption_ms),
                confidence,
                confidence_level: ConfidenceLevel::from_score(confidence),
                method: if interruptions > 0 {
                    SleepDetectionMethod::InactivityGapWithInterruptions
                } else {
                    SleepDetectionMethod::InactivityGap
                },
            });
        }
        k += 1;
    }

    let resets_awake = |minutes: u32| minutes >= config.awake_reset_minutes;
    let awake_since_ms = if ongoing
        .as_ref()
        .is_some_and(|o| resets_awake(o.elapsed_minutes))
    {
        None
    } else {
        sessions
            .iter()
            .rev()
            .find(|s| resets_awake(s.duration_minutes))
            .map(|s| s.end_ms)
    };
    SleepAnalysis {
        current_awake_minutes: awake_since_ms.map(|since| minutes_between(since, now_ms)),
        awake_since_ms,
        sessions,
        ongoing,
        active_minutes: minutes_between(0, intervals.iter().map(|a| a.span.len()).sum()),
        unlock_count: sorted
            .iter()
            .filter(|e| e.kind == DeviceEventKind::Unlock)
            .count() as u32,
    }
}

fn charging_throughout(events: &[DeviceEvent], start_ms: i64, end_ms: i64) -> bool {
    let mut connected = false;
    for e in events {
        if e.timestamp_ms > end_ms {
            break;
        }
        match e.kind {
            DeviceEventKind::PowerConnected if e.timestamp_ms <= start_ms => connected = true,
            DeviceEventKind::PowerDisconnected if e.timestamp_ms <= start_ms => connected = false,
            DeviceEventKind::PowerDisconnected => return false,
            _ => {}
        }
    }
    connected
}

/// Transparent confidence heuristic (0..=100):
/// - base 40;
/// - +20 for a typical night length (3–11 h), −10 for < 90 min, −25 beyond the plausible maximum;
/// - up to +35 proportional to overlap with the configured sleep window;
/// - −6 per interruption (at most −24);
/// - +5 when the phone was charging for the whole session.
pub fn confidence(
    start_ms: i64,
    end_ms: i64,
    duration_minutes: u32,
    interruptions: u32,
    charging: bool,
    utc_offset_minutes: i32,
    config: &SleepConfig,
) -> u8 {
    let mut score: i32 = 40;
    if duration_minutes > config.max_plausible_sleep_minutes {
        score -= 25;
    } else if (180..=660).contains(&duration_minutes) {
        score += 20;
    } else if duration_minutes < 90 {
        score -= 10;
    }
    let span = (end_ms - start_ms).max(1);
    let overlap = window_overlap_ms(start_ms, end_ms, utc_offset_minutes, config);
    score += (35.0 * overlap as f64 / span as f64).round() as i32;
    score -= (6 * interruptions as i32).min(24);
    if charging {
        score += 5;
    }
    score.clamp(0, 100) as u8
}

fn window_overlap_ms(
    start_ms: i64,
    end_ms: i64,
    utc_offset_minutes: i32,
    config: &SleepConfig,
) -> i64 {
    let ws = i64::from(config.typical_window_start_minute % 1440);
    let we = i64::from(config.typical_window_end_minute % 1440);
    let length_min = if we > ws { we - ws } else { 1440 - ws + we };
    let first = epoch_day_at(start_ms, utc_offset_minutes) - 1;
    let last = epoch_day_at(end_ms, utc_offset_minutes);
    (first..=last)
        .map(|day| {
            let w_start = local_midnight_ms(day, utc_offset_minutes) + ws * MS_PER_MINUTE;
            let w_end = w_start + length_min * MS_PER_MINUTE;
            (end_ms.min(w_end) - start_ms.max(w_start)).max(0)
        })
        .sum()
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct SleepDaySummary {
    /// Minutes of sleep from sessions that *ended* within the day (wake-day attribution).
    pub sleep_minutes: u32,
    pub session_count: u32,
    pub main_session: Option<SleepSession>,
    /// Longest continuous awake period touching the day, measured in full
    /// (it may have started the previous day). `None` if no sleep is known.
    pub longest_awake_minutes: Option<u32>,
}

pub fn summarize_day(
    analysis: &SleepAnalysis,
    day_start_ms: i64,
    day_end_ms: i64,
    now_ms: i64,
    config: &SleepConfig,
) -> SleepDaySummary {
    let in_day: Vec<&SleepSession> = analysis
        .sessions
        .iter()
        .filter(|s| s.end_ms >= day_start_ms && s.end_ms < day_end_ms)
        .collect();
    let main_session = in_day
        .iter()
        .max_by_key(|s| (s.duration_minutes, s.end_ms))
        .map(|s| (*s).clone());

    // Awake periods run between rests long enough to reset the awake clock.
    let rests: Vec<&SleepSession> = analysis
        .sessions
        .iter()
        .filter(|s| s.duration_minutes >= config.awake_reset_minutes)
        .collect();
    let mut awake: Vec<Span> = rests
        .windows(2)
        .map(|w| Span {
            start: w[0].end_ms,
            end: w[1].start_ms,
        })
        .collect();
    if let Some(last) = rests.last() {
        let end = analysis
            .ongoing
            .as_ref()
            .filter(|o| o.elapsed_minutes >= config.awake_reset_minutes)
            .map_or(now_ms, |o| o.start_ms);
        awake.push(Span {
            start: last.end_ms,
            end,
        });
    }
    let window_end = day_end_ms.min(now_ms);
    let longest_awake_minutes = awake
        .iter()
        .filter(|a| a.start < window_end && a.end.min(now_ms) > day_start_ms)
        .map(|a| minutes_between(a.start, a.end.min(now_ms)))
        .max();

    SleepDaySummary {
        sleep_minutes: in_day.iter().map(|s| s.duration_minutes).sum(),
        session_count: in_day.len() as u32,
        main_session,
        longest_awake_minutes,
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct PhoneUsage {
    /// Minutes of active (unlocked, screen-on) use inside the window.
    pub screen_minutes: u32,
    pub unlocks: u32,
}

/// Active use and unlocks clipped to `[start_ms, min(end_ms, now_ms))`.
pub fn usage_in_window(
    events: &[DeviceEvent],
    start_ms: i64,
    end_ms: i64,
    now_ms: i64,
) -> PhoneUsage {
    let mut sorted: Vec<DeviceEvent> = events
        .iter()
        .copied()
        .filter(|e| e.timestamp_ms <= now_ms)
        .collect();
    sorted.sort_by_key(|e| (e.timestamp_ms, e.kind.tie_rank()));
    let end = end_ms.min(now_ms);
    let (intervals, _) = active_intervals(&sorted, now_ms);
    let active_ms: i64 = intervals
        .iter()
        .map(|a| (a.span.end.min(end) - a.span.start.max(start_ms)).max(0))
        .sum();
    PhoneUsage {
        screen_minutes: minutes_between(0, active_ms),
        unlocks: sorted
            .iter()
            .filter(|e| {
                e.kind == DeviceEventKind::Unlock
                    && e.timestamp_ms >= start_ms
                    && e.timestamp_ms < end
            })
            .count() as u32,
    }
}

/// Everything the platform needs about one day's sleep and phone use.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct SleepDayReport {
    pub analysis: SleepAnalysis,
    pub summary: SleepDaySummary,
    pub usage: PhoneUsage,
}

/// Analyzes `events` (which should reach back well before `day_start_ms` so the
/// preceding night is visible) and summarizes the local day `[day_start_ms, day_end_ms)`.
pub fn analyze_day(
    events: &[DeviceEvent],
    day_start_ms: i64,
    day_end_ms: i64,
    now_ms: i64,
    utc_offset_minutes: i32,
    config: &SleepConfig,
) -> SleepDayReport {
    let analysis = analyze(events, now_ms, utc_offset_minutes, config);
    SleepDayReport {
        summary: summarize_day(&analysis, day_start_ms, day_end_ms, now_ms, config),
        usage: usage_in_window(events, day_start_ms, day_end_ms, now_ms),
        analysis,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn naps_and_quiet_hours_do_not_reset_the_awake_clock() {
        let mut events = use_phone(DAY - 2 * H, 10);
        for i in 0..16 {
            events.extend(use_phone(DAY + 6 * H + i * 30 * M, 10)); // 06:00–13:40
        }
        events.extend(use_phone(DAY + 14 * H + 40 * M, 10)); // after a quiet hour

        // At 15:00 the short "nap" must not reset the awake clock.
        let a = analyze(&events, DAY + 15 * H, 0, &cfg());
        assert_eq!(a.sessions.len(), 2);
        assert_eq!(a.awake_since_ms, Some(DAY + 6 * H + 2_000));
        assert_eq!(a.current_awake_minutes, Some(8 * 60 + 59));

        // Idle until 20:00: a long rest in progress stops the awake clock.
        let later = analyze(&events, DAY + 20 * H, 0, &cfg());
        assert!(later.awake_since_ms.is_none());
        let summary = summarize_day(&later, DAY, DAY + 24 * H, DAY + 20 * H, &cfg());
        // Awake from 06:00 until the evening rest began at 15:35 (14:50 + 45 min).
        assert_eq!(summary.longest_awake_minutes, Some(9 * 60 + 34));
    }

    #[test]
    fn usage_is_clipped_to_window() {
        let events = vec![
            ev(DAY - 30 * M, Unlock),
            ev(DAY + 30 * M, ScreenOff),
            ev(DAY + 2 * H, Unlock),
            ev(DAY + 2 * H + 10 * M, ScreenOff),
            ev(DAY + 3 * H, ScreenOn),
            ev(DAY + 3 * H + M, ScreenOff),
        ];
        let usage = usage_in_window(&events, DAY, DAY + 24 * H, DAY + 5 * H);
        assert_eq!(usage.screen_minutes, 40);
        assert_eq!(usage.unlocks, 1);
        let report = analyze_day(&events, DAY, DAY + 24 * H, DAY + 5 * H, 0, &cfg());
        assert_eq!(report.usage, usage);
    }
    use DeviceEventKind::*;

    const H: i64 = 3_600_000;
    const M: i64 = MS_PER_MINUTE;
    /// 2026-09-24 00:00 UTC.
    const DAY: i64 = 20_720 * crate::time::MS_PER_DAY;

    fn ev(t: i64, kind: DeviceEventKind) -> DeviceEvent {
        DeviceEvent {
            timestamp_ms: t,
            kind,
        }
    }

    fn use_phone(at: i64, minutes: i64) -> Vec<DeviceEvent> {
        vec![
            ev(at, ScreenOn),
            ev(at + 2_000, Unlock),
            ev(at + 5_000, Interaction),
            ev(at + minutes * M, ScreenOff),
        ]
    }

    fn cfg() -> SleepConfig {
        SleepConfig::default()
    }

    #[test]
    fn spec_example_last_activity_4am_wake_6am() {
        // Last activity 04:00, threshold 45 min → sleep from 04:45; wake 06:00 → 75 min.
        let mut events = use_phone(DAY + 3 * H + 30 * M, 30);
        events.extend(use_phone(DAY + 6 * H, 20));
        // A 75 minute sleep counts as rest once the awake reset is lowered below it.
        let config = SleepConfig {
            awake_reset_minutes: 60,
            ..cfg()
        };
        let a = analyze(&events, DAY + 7 * H, 0, &config);
        assert_eq!(a.sessions.len(), 1);
        let s = &a.sessions[0];
        assert_eq!(s.last_activity_ms, DAY + 4 * H);
        assert_eq!(s.start_ms, DAY + 4 * H + 45 * M);
        assert_eq!(s.end_ms, DAY + 6 * H + 2_000);
        assert_eq!(s.duration_minutes, 75);
        assert_eq!(s.method, SleepDetectionMethod::InactivityGap);
        assert_eq!(a.awake_since_ms, Some(DAY + 6 * H + 2_000));
        assert_eq!(a.current_awake_minutes, Some(59));
    }

    #[test]
    fn at_last_activity_onset() {
        let mut events = use_phone(DAY, 10);
        events.extend(use_phone(DAY + 8 * H, 10));
        let config = SleepConfig {
            onset: SleepOnset::AtLastActivity,
            ..cfg()
        };
        let a = analyze(&events, DAY + 9 * H, 0, &config);
        assert_eq!(a.sessions[0].start_ms, DAY + 10 * M);
        assert_eq!(a.sessions[0].duration_minutes, 7 * 60 + 50);
    }

    #[test]
    fn notifications_do_not_wake() {
        let mut events = use_phone(DAY, 10);
        // Screen lights up for notifications, no unlock.
        for i in 1..6 {
            events.push(ev(DAY + i * H, Notification));
            events.push(ev(DAY + i * H + 1, ScreenOn));
            events.push(ev(DAY + i * H + 10_000, ScreenOff));
        }
        events.extend(use_phone(DAY + 7 * H, 10));
        let a = analyze(&events, DAY + 8 * H, 0, &cfg());
        assert_eq!(a.sessions.len(), 1);
        assert_eq!(a.sessions[0].interruption_count, 0);
        assert_eq!(a.sessions[0].end_ms, DAY + 7 * H + 2_000);
    }

    #[test]
    fn brief_night_check_is_an_interruption() {
        let mut events = use_phone(DAY, 10);
        // 3 AM: unlock, glance at the home screen, lock again. No app opened.
        events.extend([ev(DAY + 3 * H, Unlock), ev(DAY + 3 * H + 2 * M, ScreenOff)]);
        events.extend(use_phone(DAY + 7 * H, 15));
        let a = analyze(&events, DAY + 8 * H, 0, &cfg());
        assert_eq!(a.sessions.len(), 1);
        let s = &a.sessions[0];
        assert_eq!(s.interruption_count, 1);
        assert_eq!(
            s.method,
            SleepDetectionMethod::InactivityGapWithInterruptions
        );
        // 00:55 → 07:00 minus the 2 min interruption.
        assert_eq!(s.duration_minutes, 6 * 60 + 5 - 2);
    }

    #[test]
    fn opening_an_app_at_night_is_a_wake() {
        let mut events = use_phone(DAY, 10);
        events.extend(use_phone(DAY + 3 * H, 2));
        events.extend(use_phone(DAY + 7 * H, 15));
        let a = analyze(&events, DAY + 8 * H, 0, &cfg());
        assert_eq!(a.sessions.len(), 2);
        assert!(a.sessions.iter().all(|s| s.interruption_count == 0));
    }

    #[test]
    fn short_morning_check_then_idle_is_awake() {
        // Wake 08:00 with a 5 minute check, then no phone use for two hours.
        let mut events = use_phone(DAY, 10);
        events.extend(use_phone(DAY + 8 * H, 5));
        events.extend(use_phone(DAY + 10 * H, 5));
        let a = analyze(&events, DAY + 10 * H + 10 * M, 0, &cfg());
        assert_eq!(a.sessions[0].end_ms, DAY + 8 * H + 2_000);
    }

    #[test]
    fn long_night_use_is_a_real_wake() {
        let mut events = use_phone(DAY, 10);
        events.extend(use_phone(DAY + 3 * H, 40));
        events.extend(use_phone(DAY + 8 * H, 10));
        let a = analyze(&events, DAY + 9 * H, 0, &cfg());
        assert_eq!(a.sessions.len(), 2);
    }

    #[test]
    fn watching_video_is_not_sleep() {
        // Unlock, then 2 h of screen-on with no interaction events.
        let events = vec![ev(DAY + 20 * H, Unlock), ev(DAY + 22 * H, ScreenOff)];
        let a = analyze(&events, DAY + 22 * H + 30 * M, 0, &cfg());
        assert!(a.sessions.is_empty());
        assert!(a.ongoing.is_none());
        assert_eq!(a.active_minutes, 120);
    }

    #[test]
    fn ongoing_sleep_is_reported() {
        let events = use_phone(DAY, 10);
        let a = analyze(&events, DAY + 3 * H, 0, &cfg());
        let o = a.ongoing.expect("ongoing");
        assert_eq!(o.start_ms, DAY + 55 * M);
        assert_eq!(o.elapsed_minutes, 2 * 60 + 5);
        assert!(a.awake_since_ms.is_none());
    }

    #[test]
    fn short_gaps_are_not_sleep() {
        let mut events = use_phone(DAY, 10);
        events.extend(use_phone(DAY + 10 * M + 50 * M, 10)); // 50 min gap → 5 min "sleep"
        let a = analyze(&events, DAY + 2 * H, 0, &cfg());
        assert!(a.sessions.is_empty());
    }

    #[test]
    fn events_are_order_independent() {
        let mut events = use_phone(DAY, 10);
        events.extend(use_phone(DAY + 7 * H, 10));
        let forward = analyze(&events, DAY + 8 * H, 0, &cfg());
        events.reverse();
        assert_eq!(analyze(&events, DAY + 8 * H, 0, &cfg()), forward);
    }

    #[test]
    fn confidence_favours_night_sleep() {
        let config = cfg();
        let night = confidence(DAY - 2 * H, DAY + 6 * H, 480, 0, true, 0, &config);
        let afternoon_nap = confidence(DAY + 14 * H, DAY + 15 * H, 60, 0, false, 0, &config);
        assert!(night >= 70, "night {night}");
        assert!(afternoon_nap < 40, "nap {afternoon_nap}");
        // IST offset shifts the window: 17:00-01:00 UTC is 22:30-06:30 IST.
        let ist = confidence(DAY + 17 * H, DAY + 25 * H, 480, 0, false, 330, &config);
        assert!(ist >= 90, "ist {ist}");
    }

    #[test]
    fn charging_detection() {
        let events = vec![ev(DAY, PowerConnected), ev(DAY + 5 * H, PowerDisconnected)];
        assert!(charging_throughout(&events, DAY + H, DAY + 4 * H));
        assert!(!charging_throughout(&events, DAY + H, DAY + 6 * H));
    }

    #[test]
    fn day_summary_attributes_to_wake_day_and_finds_longest_awake() {
        let mut events = use_phone(DAY - 2 * H, 10); // 22:00 previous day
                                                     // Wake 06:00, then regular use every 40 minutes until 22:50.
        for i in 0..=25 {
            events.extend(use_phone(DAY + 6 * H + i * 40 * M, 10));
        }
        let now = DAY + 23 * H + 20 * M;
        let a = analyze(&events, now, 0, &cfg());
        let summary = summarize_day(&a, DAY, DAY + 24 * H, now, &cfg());
        assert_eq!(summary.session_count, 1);
        assert_eq!(summary.sleep_minutes, a.sessions[0].duration_minutes);
        assert_eq!(summary.longest_awake_minutes, Some(17 * 60 + 19));
    }
}
