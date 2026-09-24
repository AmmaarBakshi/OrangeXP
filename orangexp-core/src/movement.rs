//! Walking vs. vehicle classification from GPS fixes, step-counter samples and
//! (optionally) the platform's activity-recognition hints.
//!
//! Speed alone is a poor classifier: slow traffic moves at walking pace, brisk
//! walking can exceed a slow walker's normal speed, and a single bad GPS fix
//! produces absurd speeds. The engine therefore combines signals per interval
//! between consecutive fixes:
//!
//! 1. **Speed** is the median of neighbouring interval speeds, so one bad fix
//!    cannot flip a classification; movement within the fixes' accuracy radius
//!    counts as no movement.
//! 2. **Step cadence** (steps per minute from the step counter) is the strongest
//!    evidence of walking: at walking cadence the interval is walking even if
//!    GPS speed is somewhat above the personal walking speed, as long as it stays
//!    below the unambiguous vehicle speed.
//! 3. Without walking cadence, faster than the personal maximum walking speed
//!    means a vehicle. Slow movement with no steps is ambiguous and decided by
//!    the activity hint or by the neighbouring segments.
//! 4. **Segments** shorter than `min_segment_seconds` are absorbed into their
//!    longer neighbour, so a traffic stop doesn't split a bus ride and a GPS spike
//!    doesn't split a walk.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
#[serde(default)]
pub struct MovementConfig {
    /// Your normal top walking speed. Faster movement without walking cadence is a vehicle.
    pub max_walking_speed_kmh: f64,
    /// At or above this speed movement is always a vehicle, steps or not.
    pub vehicle_speed_kmh: f64,
    /// Step cadence that proves walking.
    pub min_walking_cadence_spm: f64,
    /// Fixes less accurate than this are ignored.
    pub max_fix_accuracy_m: f64,
    /// Segments shorter than this merge into their neighbours.
    pub min_segment_seconds: u32,
    /// Longer gaps between fixes are not interpolated across.
    pub max_gap_seconds: u32,
}

impl Default for MovementConfig {
    fn default() -> Self {
        Self {
            max_walking_speed_kmh: 5.5,
            vehicle_speed_kmh: 12.0,
            min_walking_cadence_spm: 50.0,
            max_fix_accuracy_m: 50.0,
            min_segment_seconds: 90,
            max_gap_seconds: 600,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct LocationFix {
    pub timestamp_ms: i64,
    pub latitude: f64,
    pub longitude: f64,
    pub accuracy_m: f64,
}

/// A reading of the cumulative step counter (which resets on reboot).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct StepSample {
    pub timestamp_ms: i64,
    pub cumulative_steps: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ActivityHint {
    Still,
    Walking,
    Running,
    OnBicycle,
    InVehicle,
    Unknown,
}

/// The platform detected that `activity` started at `timestamp_ms`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct ActivityTransition {
    pub timestamp_ms: i64,
    pub activity: ActivityHint,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum MovementMode {
    Still,
    Walking,
    Vehicle,
    Unknown,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct MovementSegment {
    pub start_ms: i64,
    pub end_ms: i64,
    pub mode: MovementMode,
    pub distance_m: f64,
    /// Steps taken during the segment, when step data covers it.
    pub steps: Option<i64>,
    pub average_speed_kmh: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct MovementSummary {
    pub segments: Vec<MovementSegment>,
    pub walking_meters: f64,
    pub walking_minutes: u32,
    pub vehicle_meters: f64,
    pub vehicle_minutes: u32,
    /// Steps the counter registered while in a vehicle (road bumps); subtract them from walking steps.
    pub steps_in_vehicle: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct MovementInput {
    pub fixes: Vec<LocationFix>,
    pub steps: Vec<StepSample>,
    pub transitions: Vec<ActivityTransition>,
    /// Only movement inside `[window_start_ms, window_end_ms)` is summarized.
    pub window_start_ms: i64,
    pub window_end_ms: i64,
}

/// Great-circle distance in meters.
pub fn haversine_m(a: &LocationFix, b: &LocationFix) -> f64 {
    const R: f64 = 6_371_000.0;
    let (lat1, lat2) = (a.latitude.to_radians(), b.latitude.to_radians());
    let dlat = lat2 - lat1;
    let dlon = (b.longitude - a.longitude).to_radians();
    let h = (dlat / 2.0).sin().powi(2) + lat1.cos() * lat2.cos() * (dlon / 2.0).sin().powi(2);
    2.0 * R * h.sqrt().min(1.0).asin()
}

/// Step counter as a monotonic function of time, tolerant of reboots.
struct StepTimeline {
    points: Vec<(i64, f64)>,
}

impl StepTimeline {
    fn new(samples: &[StepSample]) -> Self {
        let mut sorted = samples.to_vec();
        sorted.sort_by_key(|s| s.timestamp_ms);
        let mut points = Vec::with_capacity(sorted.len());
        let mut total = 0.0;
        let mut previous: Option<i64> = None;
        for s in sorted {
            total += match previous {
                None => 0.0,
                Some(p) if s.cumulative_steps >= p => (s.cumulative_steps - p) as f64,
                // The counter restarted after a reboot.
                Some(_) => s.cumulative_steps as f64,
            };
            previous = Some(s.cumulative_steps);
            points.push((s.timestamp_ms, total));
        }
        Self { points }
    }

    fn at(&self, t: i64) -> Option<f64> {
        let (first, last) = (self.points.first()?, self.points.last()?);
        if t < first.0 || t > last.0 {
            return None;
        }
        let i = self.points.partition_point(|p| p.0 <= t);
        if i == 0 {
            return Some(first.1);
        }
        let (t0, v0) = self.points[i - 1];
        match self.points.get(i) {
            Some(&(t1, v1)) if t1 > t0 => Some(v0 + (v1 - v0) * (t - t0) as f64 / (t1 - t0) as f64),
            _ => Some(v0),
        }
    }

    fn between(&self, t0: i64, t1: i64) -> Option<f64> {
        Some((self.at(t1)? - self.at(t0)?).max(0.0))
    }
}

#[derive(Debug, Clone, Copy)]
struct Interval {
    start: i64,
    end: i64,
    distance: f64,
    steps: Option<f64>,
    mode: MovementMode,
    track: usize,
}

impl Interval {
    fn seconds(&self) -> f64 {
        (self.end - self.start) as f64 / 1000.0
    }
}

fn hint_at(transitions: &[ActivityTransition], t: i64) -> ActivityHint {
    transitions
        .iter()
        .filter(|tr| tr.timestamp_ms <= t)
        .max_by_key(|tr| tr.timestamp_ms)
        .map_or(ActivityHint::Unknown, |tr| tr.activity)
}

fn classify(
    speed_kmh: f64,
    moved: bool,
    cadence: Option<f64>,
    hint: ActivityHint,
    c: &MovementConfig,
) -> MovementMode {
    let hinted_vehicle = hint == ActivityHint::InVehicle;
    let hinted_foot = matches!(hint, ActivityHint::Walking | ActivityHint::Running);
    if !moved && cadence.is_none_or(|c| c < 10.0) {
        return MovementMode::Still;
    }
    if speed_kmh >= c.vehicle_speed_kmh {
        return MovementMode::Vehicle;
    }
    match cadence {
        Some(spm) if spm >= c.min_walking_cadence_spm => MovementMode::Walking,
        Some(_) if speed_kmh > c.max_walking_speed_kmh => MovementMode::Vehicle,
        Some(spm) if spm >= 20.0 => MovementMode::Walking,
        Some(_) if hinted_vehicle => MovementMode::Vehicle,
        Some(_) => MovementMode::Unknown,
        None if speed_kmh > c.max_walking_speed_kmh => {
            if hinted_foot {
                MovementMode::Walking
            } else {
                MovementMode::Vehicle
            }
        }
        None if hinted_vehicle => MovementMode::Vehicle,
        None => MovementMode::Walking,
    }
}

/// Drops "out-and-back" fixes: a point far off the path between its neighbours
/// that would require vehicle speed to reach and leave. Real turns don't do that.
fn remove_spikes(fixes: Vec<LocationFix>, config: &MovementConfig) -> Vec<LocationFix> {
    if fixes.len() < 3 {
        return fixes;
    }
    let mut keep = vec![true; fixes.len()];
    for i in 1..fixes.len() - 1 {
        let (prev, cur, next) = (&fixes[i - 1], &fixes[i], &fixes[i + 1]);
        let detour = haversine_m(prev, cur) + haversine_m(cur, next);
        let direct = haversine_m(prev, next);
        let noise = prev.accuracy_m + 2.0 * cur.accuracy_m + next.accuracy_m;
        let dt = (next.timestamp_ms - prev.timestamp_ms) as f64 / 1000.0;
        let implied_kmh = if dt > 0.0 {
            detour / dt * 3.6
        } else {
            f64::INFINITY
        };
        if detour > 3.0 * direct + noise && implied_kmh >= config.vehicle_speed_kmh {
            keep[i] = false;
        }
    }
    fixes
        .into_iter()
        .zip(keep)
        .filter_map(|(f, k)| k.then_some(f))
        .collect()
}

pub fn analyze(input: &MovementInput, config: &MovementConfig) -> MovementSummary {
    let mut fixes: Vec<LocationFix> = input
        .fixes
        .iter()
        .copied()
        .filter(|f| f.latitude.is_finite() && f.longitude.is_finite() && f.accuracy_m.is_finite())
        .filter(|f| f.accuracy_m <= config.max_fix_accuracy_m)
        .collect();
    fixes.sort_by_key(|f| f.timestamp_ms);
    fixes.dedup_by_key(|f| f.timestamp_ms);
    let fixes = remove_spikes(fixes, config);

    let steps = StepTimeline::new(&input.steps);
    let max_gap_ms = i64::from(config.max_gap_seconds) * 1000;

    // Raw intervals between consecutive fixes, grouped into tracks at long gaps.
    let mut raw: Vec<Interval> = Vec::new();
    let mut speeds: Vec<f64> = Vec::new();
    let mut moved: Vec<bool> = Vec::new();
    let mut track = 0usize;
    for pair in fixes.windows(2) {
        let (a, b) = (pair[0], pair[1]);
        let dt = b.timestamp_ms - a.timestamp_ms;
        if dt <= 0 || dt > max_gap_ms {
            track += 1;
            continue;
        }
        let d = haversine_m(&a, &b);
        // Displacement within both fixes' uncertainty is jitter, not movement.
        let noise = a.accuracy_m + b.accuracy_m;
        let is_moving = d > noise;
        let distance = if is_moving { d } else { 0.0 };
        raw.push(Interval {
            start: a.timestamp_ms,
            end: b.timestamp_ms,
            // Raw distance is kept: if steps prove walking, a slow walk under poor
            // GPS still counts. Still intervals are zeroed after classification.
            distance: d,
            steps: steps.between(a.timestamp_ms, b.timestamp_ms),
            mode: MovementMode::Unknown,
            track,
        });
        speeds.push(distance / (dt as f64 / 1000.0) * 3.6);
        moved.push(is_moving);
    }

    for i in 0..raw.len() {
        // Median speed across neighbours in the same track resists single bad intervals.
        let mut window: Vec<f64> = [i.checked_sub(1), Some(i), Some(i + 1)]
            .into_iter()
            .flatten()
            .filter(|&j| j < raw.len() && raw[j].track == raw[i].track)
            .map(|j| speeds[j])
            .collect();
        window.sort_by(|a, b| a.total_cmp(b));
        let speed = window[(window.len() - 1) / 2];
        let cadence = raw[i].steps.map(|s| s / (raw[i].seconds() / 60.0));
        let hint = hint_at(&input.transitions, (raw[i].start + raw[i].end) / 2);
        raw[i].mode = classify(speed, moved[i], cadence, hint, config);
        if raw[i].mode == MovementMode::Still {
            raw[i].distance = 0.0;
        }
    }

    let segments = smooth(raw, config);
    summarize(segments, input.window_start_ms, input.window_end_ms)
}

#[derive(Debug, Clone)]
struct Seg {
    start: i64,
    end: i64,
    distance: f64,
    steps: Option<f64>,
    mode: MovementMode,
    track: usize,
}

impl Seg {
    fn seconds(&self) -> f64 {
        (self.end - self.start) as f64 / 1000.0
    }

    fn absorb(&mut self, other: &Seg) {
        self.start = self.start.min(other.start);
        self.end = self.end.max(other.end);
        self.distance += other.distance;
        self.steps = match (self.steps, other.steps) {
            (Some(a), Some(b)) => Some(a + b),
            (a, b) => a.or(b),
        };
    }
}

fn merge_runs(segs: Vec<Seg>) -> Vec<Seg> {
    let mut out: Vec<Seg> = Vec::with_capacity(segs.len());
    for s in segs {
        match out.last_mut() {
            Some(last) if last.mode == s.mode && last.track == s.track && last.end == s.start => {
                last.absorb(&s)
            }
            _ => out.push(s),
        }
    }
    out
}

fn smooth(intervals: Vec<Interval>, config: &MovementConfig) -> Vec<Seg> {
    let mut segs = merge_runs(
        intervals
            .into_iter()
            .map(|i| Seg {
                start: i.start,
                end: i.end,
                distance: i.distance,
                steps: i.steps,
                mode: i.mode,
                track: i.track,
            })
            .collect(),
    );
    let min = f64::from(config.min_segment_seconds);
    loop {
        // Pick the weakest segment: any Unknown first, then the shortest one below the minimum.
        let candidate = segs
            .iter()
            .enumerate()
            .filter(|(_, s)| s.mode == MovementMode::Unknown || s.seconds() < min)
            .filter(|(i, s)| {
                neighbours(&segs, *i)
                    .iter()
                    .any(|&j| segs[j].track == s.track)
            })
            .min_by(|(ia, a), (ib, b)| {
                let rank = |s: &Seg| {
                    if s.mode == MovementMode::Unknown {
                        0
                    } else {
                        1
                    }
                };
                rank(a)
                    .cmp(&rank(b))
                    .then(a.seconds().total_cmp(&b.seconds()))
                    .then(ia.cmp(ib))
            })
            .map(|(i, _)| i);
        let Some(i) = candidate else { break };
        let target = neighbours(&segs, i)
            .into_iter()
            .filter(|&j| segs[j].track == segs[i].track && segs[j].mode != MovementMode::Unknown)
            .max_by(|&a, &b| {
                segs[a]
                    .seconds()
                    .total_cmp(&segs[b].seconds())
                    .then(b.cmp(&a))
            });
        let Some(j) = target else {
            // Only Unknown neighbours: decide by speed alone and continue.
            let s = &segs[i];
            let kmh = if s.seconds() > 0.0 {
                s.distance / s.seconds() * 3.6
            } else {
                0.0
            };
            segs[i].mode = if kmh > config.max_walking_speed_kmh {
                MovementMode::Vehicle
            } else {
                MovementMode::Walking
            };
            if segs[i].seconds() < min && neighbours(&segs, i).is_empty() {
                break;
            }
            segs = merge_runs(segs);
            continue;
        };
        let absorbed = segs.remove(i);
        let j = if j > i { j - 1 } else { j };
        segs[j].absorb(&absorbed);
        segs = merge_runs(segs);
    }
    // Any remaining isolated Unknown segment falls back to speed.
    for s in &mut segs {
        if s.mode == MovementMode::Unknown {
            let kmh = if s.seconds() > 0.0 {
                s.distance / s.seconds() * 3.6
            } else {
                0.0
            };
            s.mode = if kmh > config.max_walking_speed_kmh {
                MovementMode::Vehicle
            } else {
                MovementMode::Walking
            };
        }
    }
    merge_runs(segs)
}

fn neighbours(segs: &[Seg], i: usize) -> Vec<usize> {
    let mut out = Vec::new();
    if i > 0 && segs[i - 1].end == segs[i].start {
        out.push(i - 1);
    }
    if i + 1 < segs.len() && segs[i + 1].start == segs[i].end {
        out.push(i + 1);
    }
    out
}

fn summarize(segs: Vec<Seg>, window_start: i64, window_end: i64) -> MovementSummary {
    let mut out = MovementSummary {
        segments: Vec::new(),
        walking_meters: 0.0,
        walking_minutes: 0,
        vehicle_meters: 0.0,
        vehicle_minutes: 0,
        steps_in_vehicle: 0,
    };
    let (mut walking_ms, mut vehicle_ms) = (0i64, 0i64);
    for s in segs {
        let start = s.start.max(window_start);
        let end = s.end.min(window_end);
        if end <= start {
            continue;
        }
        // Clip distance and steps proportionally to the part inside the window.
        let share = (end - start) as f64 / (s.end - s.start).max(1) as f64;
        let distance = s.distance * share;
        let steps = s.steps.map(|v| (v * share).round() as i64);
        match s.mode {
            MovementMode::Walking => {
                out.walking_meters += distance;
                walking_ms += end - start;
            }
            MovementMode::Vehicle => {
                out.vehicle_meters += distance;
                vehicle_ms += end - start;
                out.steps_in_vehicle += steps.unwrap_or(0);
            }
            _ => {}
        }
        out.segments.push(MovementSegment {
            start_ms: start,
            end_ms: end,
            mode: s.mode,
            distance_m: distance,
            steps,
            average_speed_kmh: distance / ((end - start) as f64 / 1000.0) * 3.6,
        });
    }
    out.walking_minutes = (walking_ms / 60_000) as u32;
    out.vehicle_minutes = (vehicle_ms / 60_000) as u32;
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const S: i64 = 1000;
    /// Meters per degree of latitude.
    const M_PER_DEG: f64 = 111_195.0;

    /// A straight northward track: `speed_kmh` for `minutes`, one fix every `every_s` seconds.
    struct Track {
        fixes: Vec<LocationFix>,
        steps: Vec<StepSample>,
        t: i64,
        lat: f64,
        counter: i64,
    }

    impl Track {
        fn new() -> Self {
            Self {
                fixes: vec![],
                steps: vec![],
                t: 0,
                lat: 19.0,
                counter: 5_000,
            }
        }

        fn leg(
            mut self,
            speed_kmh: f64,
            minutes: i64,
            cadence_spm: Option<f64>,
            every_s: i64,
        ) -> Self {
            let n = minutes * 60 / every_s;
            for _ in 0..n {
                self.fixes.push(LocationFix {
                    timestamp_ms: self.t,
                    latitude: self.lat,
                    longitude: 73.0,
                    accuracy_m: 8.0,
                });
                if let Some(spm) = cadence_spm {
                    self.steps.push(StepSample {
                        timestamp_ms: self.t,
                        cumulative_steps: self.counter,
                    });
                    self.counter += (spm * every_s as f64 / 60.0).round() as i64;
                } else {
                    self.steps.push(StepSample {
                        timestamp_ms: self.t,
                        cumulative_steps: self.counter,
                    });
                }
                self.lat += speed_kmh / 3.6 * every_s as f64 / M_PER_DEG;
                self.t += every_s * S;
            }
            self
        }

        fn input(self) -> MovementInput {
            MovementInput {
                window_start_ms: 0,
                window_end_ms: self.t + S,
                fixes: self.fixes,
                steps: self.steps,
                transitions: vec![],
            }
        }
    }

    fn modes(summary: &MovementSummary) -> Vec<MovementMode> {
        summary.segments.iter().map(|s| s.mode).collect()
    }

    #[test]
    fn slow_walk_is_walking() {
        let s = analyze(
            &Track::new().leg(4.2, 20, Some(95.0), 20).input(),
            &MovementConfig::default(),
        );
        assert_eq!(modes(&s), vec![MovementMode::Walking]);
        assert!(
            (s.walking_meters - 4.2 / 60.0 * 20.0 * 1000.0).abs() < 100.0,
            "{}",
            s.walking_meters
        );
        assert_eq!(s.vehicle_minutes, 0);
    }

    #[test]
    fn brisk_walk_above_personal_speed_is_not_missed() {
        // 6.5 km/h is above the 5.5 km/h personal limit, but the steps prove walking.
        let s = analyze(
            &Track::new().leg(6.5, 15, Some(120.0), 20).input(),
            &MovementConfig::default(),
        );
        assert_eq!(modes(&s), vec![MovementMode::Walking]);
    }

    #[test]
    fn slow_traffic_without_steps_is_a_vehicle() {
        // 5-10 km/h with no steps: the user can't move that fast on foot.
        let s = analyze(
            &Track::new().leg(8.0, 15, Some(0.0), 20).input(),
            &MovementConfig::default(),
        );
        assert_eq!(modes(&s), vec![MovementMode::Vehicle]);
        assert_eq!(s.walking_minutes, 0);
    }

    #[test]
    fn bus_ride_with_stop_and_bumps_is_one_vehicle_segment() {
        let s = analyze(
            &Track::new()
                .leg(4.5, 10, Some(100.0), 20) // walk to the stop
                .leg(0.0, 5, Some(0.0), 20) // wait
                .leg(28.0, 15, Some(12.0), 20) // ride: road bumps register a few "steps"
                .leg(0.0, 1, Some(0.0), 20) // traffic light
                .leg(25.0, 10, Some(8.0), 20)
                .leg(4.5, 8, Some(100.0), 20) // walk to class
                .input(),
            &MovementConfig::default(),
        );
        assert_eq!(
            modes(&s),
            vec![
                MovementMode::Walking,
                MovementMode::Still,
                MovementMode::Vehicle,
                MovementMode::Walking
            ]
        );
        assert!(s.steps_in_vehicle > 0);
        assert!(
            (24..=27).contains(&s.vehicle_minutes),
            "{}",
            s.vehicle_minutes
        );
    }

    #[test]
    fn a_single_gps_spike_does_not_break_a_walk() {
        let mut input = Track::new().leg(4.5, 20, Some(100.0), 20).input();
        input.fixes[30].latitude += 0.004; // ~450 m jump
        let s = analyze(&input, &MovementConfig::default());
        assert_eq!(modes(&s), vec![MovementMode::Walking]);
        // The spike's detour is not counted as distance walked.
        assert!(s.walking_meters < 1_600.0, "{}", s.walking_meters);
    }

    #[test]
    fn without_step_data_speed_and_hints_decide() {
        let mut input = Track::new()
            .leg(4.5, 15, None, 20)
            .leg(20.0, 15, None, 20)
            .input();
        input.steps.clear();
        let s = analyze(&input, &MovementConfig::default());
        assert_eq!(
            modes(&s),
            vec![MovementMode::Walking, MovementMode::Vehicle]
        );

        let mut slow_ride = Track::new().leg(4.0, 15, None, 20).input();
        slow_ride.steps.clear();
        slow_ride.transitions.push(ActivityTransition {
            timestamp_ms: 0,
            activity: ActivityHint::InVehicle,
        });
        assert_eq!(
            modes(&analyze(&slow_ride, &MovementConfig::default())),
            vec![MovementMode::Vehicle]
        );
    }

    #[test]
    fn inaccurate_fixes_and_jitter_are_ignored() {
        let mut input = Track::new().leg(0.0, 10, Some(0.0), 20).input();
        for (i, f) in input.fixes.iter_mut().enumerate() {
            f.latitude += if i % 2 == 0 { 0.00005 } else { -0.00005 }; // ~5 m jitter
        }
        input.fixes.push(LocationFix {
            timestamp_ms: 5 * S,
            latitude: 20.0,
            longitude: 73.0,
            accuracy_m: 500.0,
        });
        let s = analyze(&input, &MovementConfig::default());
        assert_eq!(modes(&s), vec![MovementMode::Still]);
        assert_eq!(s.walking_meters, 0.0);
    }

    #[test]
    fn step_counter_reboot_is_handled() {
        let t = StepTimeline::new(&[
            StepSample {
                timestamp_ms: 0,
                cumulative_steps: 1_000,
            },
            StepSample {
                timestamp_ms: 10,
                cumulative_steps: 1_100,
            },
            StepSample {
                timestamp_ms: 20,
                cumulative_steps: 50,
            },
        ]);
        assert_eq!(t.between(0, 20), Some(150.0));
        assert_eq!(t.between(0, 5), Some(50.0));
        assert_eq!(t.between(0, 30), None);
    }

    #[test]
    fn summary_is_clipped_to_the_window() {
        let mut input = Track::new().leg(4.5, 60, Some(100.0), 30).input();
        input.window_start_ms = 30 * 60 * S;
        let s = analyze(&input, &MovementConfig::default());
        assert!(
            (29..=30).contains(&s.walking_minutes),
            "{}",
            s.walking_minutes
        );
    }

    #[test]
    fn haversine_known_distance() {
        let a = LocationFix {
            timestamp_ms: 0,
            latitude: 0.0,
            longitude: 0.0,
            accuracy_m: 1.0,
        };
        let b = LocationFix {
            timestamp_ms: 0,
            latitude: 1.0,
            longitude: 0.0,
            accuracy_m: 1.0,
        };
        assert!((haversine_m(&a, &b) - M_PER_DEG).abs() < 1.0);
    }
}
