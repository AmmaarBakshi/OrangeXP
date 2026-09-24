//! Competition planning and teammate ranking.
//!
//! # How many competitions fit at once
//!
//! Each upcoming competition needs `prep_hours` of work between its preparation
//! start and its event start, plus a slot during the event itself. Available time
//! comes from configured daily hours. A set of competitions is feasible when:
//!
//! - **time:** for every window `[r, d]` built from the competitions' own start
//!   and deadline days, the prep hours of competitions that must happen entirely
//!   inside it fit the hours available in it. For preemptive work with release
//!   times and deadlines this interval condition is exact, not a heuristic;
//! - **attention:** no day has more than `max_concurrent` competitions active.
//!
//! Registered competitions are commitments and always kept. Among the planned
//! ones the planner picks the set with the highest total importance that stays
//! feasible: exhaustively for up to 16 candidates, greedily beyond that. Ties go
//! to fewer hours, then to ids, so the answer is deterministic.
//!
//! # Teammate score
//!
//! Every completed competition gives each teammate points for the result
//! (win 100, podium 60, finalist 30, participated 10), decayed by age with a
//! configurable half-life, so recent success counts more when choosing a team.

use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};

use crate::time::{weekday_of, EpochDay, Weekday};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CompetitionStatus {
    /// Considering it; the planner decides whether it fits.
    Planned,
    /// Committed; always kept in the plan.
    Registered,
    Completed,
    Withdrawn,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CompetitionResult {
    Won,
    /// Second or third place.
    Podium,
    Finalist,
    Participated,
}

impl CompetitionResult {
    pub fn points(self) -> f64 {
        match self {
            CompetitionResult::Won => 100.0,
            CompetitionResult::Podium => 60.0,
            CompetitionResult::Finalist => 30.0,
            CompetitionResult::Participated => 10.0,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct Competition {
    pub id: String,
    pub name: String,
    /// First day preparation can start (e.g. when the problem statement is released).
    pub prep_start_day: EpochDay,
    pub event_start_day: EpochDay,
    pub event_end_day: EpochDay,
    /// Estimated hours of preparation before the event.
    pub prep_hours: f64,
    /// 1 (nice to have) ..= 5 (must do).
    pub importance: u8,
    pub status: CompetitionStatus,
    pub result: Option<CompetitionResult>,
    pub member_ids: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
#[serde(default)]
pub struct CompetitionConfig {
    /// Hours per weekday you can spend on competitions besides studying.
    pub weekday_hours: f64,
    pub weekend_hours: f64,
    /// Never more than this many competitions active on the same day.
    pub max_concurrent: u32,
    /// Age at which a result counts half in the teammate score.
    pub teammate_half_life_days: u32,
}

impl Default for CompetitionConfig {
    fn default() -> Self {
        Self {
            weekday_hours: 1.5,
            weekend_hours: 4.0,
            max_concurrent: 3,
            teammate_half_life_days: 365,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CompetitionVerdict {
    /// Registered and it fits alongside everything else.
    Committed,
    /// Registered, but your commitments together need more time than you have.
    Overcommitted,
    /// Planned and it fits: go for it.
    Recommended,
    /// Planned but there isn't enough prep time next to higher-priority ones.
    NotEnoughTime,
    /// Planned but it would exceed the concurrent-competition limit.
    TooManyAtOnce,
    /// Already over, completed or withdrawn.
    Inactive,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct CompetitionAssessment {
    pub id: String,
    pub verdict: CompetitionVerdict,
    /// Prep hours still needed from today.
    pub remaining_prep_hours: f64,
    /// Hours available in its own prep window, ignoring other competitions.
    pub window_hours: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct CompetitionPlan {
    pub assessments: Vec<CompetitionAssessment>,
    /// Ids of registered + recommended competitions.
    pub selected_ids: Vec<String>,
    /// Most competitions of the selected set active on one day.
    pub peak_concurrency: u32,
    /// Most competitions you could run in parallel right now: the concurrency
    /// limit, reduced if even an average-sized competition no longer fits.
    pub max_parallel: u32,
    /// Competition hours left over in the next 30 days after the selected prep.
    pub spare_hours_next_30_days: f64,
    /// How many more typical competitions (median prep of your list, 10 h if
    /// none) would fit in those spare hours, within the concurrency limit.
    pub additional_capacity: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct TeammateStats {
    pub member_id: String,
    pub competitions: u32,
    pub wins: u32,
    pub podiums: u32,
    /// Laplace-smoothed win rate in percent: (wins + 1) / (competitions + 2).
    pub win_rate_percent: f64,
    /// Recency-weighted result points; higher is a stronger pick.
    pub score: f64,
    pub last_competition_day: Option<EpochDay>,
}

fn hours_on(day: EpochDay, config: &CompetitionConfig) -> f64 {
    match weekday_of(day) {
        Weekday::Saturday | Weekday::Sunday => config.weekend_hours.max(0.0),
        _ => config.weekday_hours.max(0.0),
    }
}

fn hours_between(from: EpochDay, to: EpochDay, config: &CompetitionConfig) -> f64 {
    if to < from {
        return 0.0;
    }
    (from..=to).map(|d| hours_on(d, config)).sum()
}

/// A competition as a scheduling job from today's point of view.
#[derive(Debug, Clone)]
struct Job {
    index: usize,
    release: EpochDay,
    /// Last prep day: the day before the event starts.
    deadline: EpochDay,
    hours: f64,
    active_from: EpochDay,
    active_to: EpochDay,
    value: u32,
}

fn job(index: usize, c: &Competition, today: EpochDay) -> Job {
    let release = c.prep_start_day.max(today);
    let deadline = c.event_start_day - 1;
    // Once the event is running, preparation is over.
    let hours = if c.event_start_day <= today {
        0.0
    } else {
        c.prep_hours.max(0.0)
    };
    Job {
        index,
        release,
        deadline,
        hours,
        active_from: release.min(c.event_start_day),
        active_to: c.event_end_day,
        value: u32::from(c.importance.clamp(1, 5)).pow(2),
    }
}

fn time_feasible(jobs: &[&Job], config: &CompetitionConfig) -> bool {
    let releases: BTreeSet<EpochDay> = jobs.iter().map(|j| j.release).collect();
    let deadlines: BTreeSet<EpochDay> = jobs.iter().map(|j| j.deadline).collect();
    for &r in &releases {
        for &d in deadlines.iter().filter(|&&d| d >= r) {
            let demand: f64 = jobs
                .iter()
                .filter(|j| j.release >= r && j.deadline <= d)
                .map(|j| j.hours)
                .sum();
            if demand > hours_between(r, d, config) + 1e-9 {
                return false;
            }
        }
    }
    // Work due before it can start is only feasible if there is none.
    jobs.iter()
        .all(|j| j.deadline >= j.release || j.hours == 0.0)
}

fn peak(jobs: &[&Job]) -> u32 {
    let mut events: BTreeMap<EpochDay, i32> = BTreeMap::new();
    for j in jobs {
        *events.entry(j.active_from).or_default() += 1;
        *events.entry(j.active_to + 1).or_default() -= 1;
    }
    let mut running = 0;
    let mut max = 0;
    for delta in events.values() {
        running += delta;
        max = max.max(running);
    }
    max.max(0) as u32
}

pub fn plan(
    competitions: &[Competition],
    today: EpochDay,
    config: &CompetitionConfig,
) -> CompetitionPlan {
    let mut order: Vec<usize> = (0..competitions.len()).collect();
    order.sort_by(|&a, &b| competitions[a].id.cmp(&competitions[b].id));

    let active = |c: &Competition| {
        matches!(
            c.status,
            CompetitionStatus::Planned | CompetitionStatus::Registered
        ) && c.event_end_day >= today
    };
    let jobs: Vec<Job> = order
        .iter()
        .map(|&i| job(i, &competitions[i], today))
        .collect();
    let committed: Vec<&Job> = jobs
        .iter()
        .filter(|j| {
            active(&competitions[j.index])
                && competitions[j.index].status == CompetitionStatus::Registered
        })
        .collect();
    let candidates: Vec<&Job> = jobs
        .iter()
        .filter(|j| {
            active(&competitions[j.index])
                && competitions[j.index].status == CompetitionStatus::Planned
        })
        .collect();

    let limit = config.max_concurrent.max(1);
    let committed_ok = time_feasible(&committed, config);
    let fits = |extra: &[&Job]| {
        let mut all = committed.clone();
        all.extend_from_slice(extra);
        // While commitments already overrun, nothing more is recommended.
        time_feasible(&all, config) && peak(&all) <= limit.max(peak(&committed))
    };

    let chosen: Vec<&Job> = if candidates.len() <= 16 {
        let mut best: Option<(u32, f64, Vec<usize>)> = None;
        for mask in 0u32..(1 << candidates.len()) {
            let subset: Vec<&Job> = (0..candidates.len())
                .filter(|b| mask & (1 << b) != 0)
                .map(|b| candidates[b])
                .collect();
            if !fits(&subset) {
                continue;
            }
            let value: u32 = subset.iter().map(|j| j.value).sum();
            let hours: f64 = subset.iter().map(|j| j.hours).sum();
            let ids: Vec<usize> = subset.iter().map(|j| j.index).collect();
            let better = match &best {
                None => true,
                Some((bv, bh, bids)) => {
                    value > *bv
                        || (value == *bv
                            && (hours < bh - 1e-9 || ((hours - bh).abs() < 1e-9 && ids < *bids)))
                }
            };
            if better {
                best = Some((value, hours, ids));
            }
        }
        let ids = best.map(|b| b.2).unwrap_or_default();
        candidates
            .iter()
            .copied()
            .filter(|j| ids.contains(&j.index))
            .collect()
    } else {
        let mut sorted = candidates.clone();
        sorted.sort_by(|a, b| {
            b.value
                .cmp(&a.value)
                .then(a.deadline.cmp(&b.deadline))
                .then(a.index.cmp(&b.index))
        });
        let mut picked: Vec<&Job> = Vec::new();
        for j in sorted {
            picked.push(j);
            if !fits(&picked) {
                picked.pop();
            }
        }
        picked
    };

    let chosen_idx: BTreeSet<usize> = chosen.iter().map(|j| j.index).collect();
    let mut selected: Vec<&Job> = committed.clone();
    selected.extend(chosen.iter().copied());

    let assessments = jobs
        .iter()
        .map(|j| {
            let c = &competitions[j.index];
            let verdict = if !active(c) {
                CompetitionVerdict::Inactive
            } else if c.status == CompetitionStatus::Registered {
                if committed_ok {
                    CompetitionVerdict::Committed
                } else {
                    CompetitionVerdict::Overcommitted
                }
            } else if chosen_idx.contains(&j.index) {
                CompetitionVerdict::Recommended
            } else {
                let mut with = selected.clone();
                with.push(j);
                if peak(&with) > limit && time_feasible(&with, config) {
                    CompetitionVerdict::TooManyAtOnce
                } else {
                    CompetitionVerdict::NotEnoughTime
                }
            };
            CompetitionAssessment {
                id: c.id.clone(),
                verdict,
                remaining_prep_hours: j.hours,
                window_hours: hours_between(j.release, j.deadline, config),
            }
        })
        .collect();

    let horizon = today + 29;
    let committed_hours_30: f64 = selected
        .iter()
        .map(|j| {
            if j.hours == 0.0 || j.deadline < j.release {
                return 0.0;
            }
            // Spread each job's prep evenly over its window; count the share inside the horizon.
            let window = hours_between(j.release, j.deadline, config);
            if window <= 0.0 {
                return j.hours;
            }
            j.hours * hours_between(j.release, j.deadline.min(horizon), config) / window
        })
        .sum();
    let spare = (hours_between(today, horizon, config) - committed_hours_30).max(0.0);

    let mut sizes: Vec<f64> = competitions
        .iter()
        .map(|c| c.prep_hours)
        .filter(|h| *h > 0.0)
        .collect();
    sizes.sort_by(|a, b| a.total_cmp(b));
    let typical = if sizes.is_empty() {
        10.0
    } else {
        sizes[sizes.len() / 2]
    };
    let current = peak(&selected);
    let additional = ((spare / typical).floor() as u32).min(limit.saturating_sub(current));

    CompetitionPlan {
        assessments,
        selected_ids: selected
            .iter()
            .map(|j| competitions[j.index].id.clone())
            .collect(),
        peak_concurrency: current,
        max_parallel: (current + additional).min(limit),
        spare_hours_next_30_days: spare,
        additional_capacity: additional,
    }
}

pub fn rank_teammates(
    competitions: &[Competition],
    today: EpochDay,
    config: &CompetitionConfig,
) -> Vec<TeammateStats> {
    let half_life = f64::from(config.teammate_half_life_days.max(1));
    let mut stats: BTreeMap<String, TeammateStats> = BTreeMap::new();
    for c in competitions
        .iter()
        .filter(|c| c.status == CompetitionStatus::Completed)
    {
        let age = f64::from((today - c.event_end_day).max(0));
        let decay = 0.5f64.powf(age / half_life);
        let points = c.result.map_or(0.0, CompetitionResult::points);
        for member in &c.member_ids {
            let s = stats
                .entry(member.clone())
                .or_insert_with(|| TeammateStats {
                    member_id: member.clone(),
                    competitions: 0,
                    wins: 0,
                    podiums: 0,
                    win_rate_percent: 0.0,
                    score: 0.0,
                    last_competition_day: None,
                });
            s.competitions += 1;
            s.wins += u32::from(c.result == Some(CompetitionResult::Won));
            s.podiums += u32::from(c.result == Some(CompetitionResult::Podium));
            s.score += points * decay;
            s.last_competition_day = Some(
                s.last_competition_day
                    .map_or(c.event_end_day, |d| d.max(c.event_end_day)),
            );
        }
    }
    let mut out: Vec<TeammateStats> = stats
        .into_values()
        .map(|mut s| {
            s.win_rate_percent =
                (f64::from(s.wins) + 1.0) / (f64::from(s.competitions) + 2.0) * 100.0;
            s.score = (s.score * 10.0).round() / 10.0;
            s
        })
        .collect();
    out.sort_by(|a, b| {
        b.score
            .total_cmp(&a.score)
            .then(b.wins.cmp(&a.wins))
            .then(a.member_id.cmp(&b.member_id))
    });
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::time::days_from_civil;

    fn comp(
        id: &str,
        prep_start: EpochDay,
        start: EpochDay,
        hours: f64,
        importance: u8,
        status: CompetitionStatus,
    ) -> Competition {
        Competition {
            id: id.into(),
            name: id.into(),
            prep_start_day: prep_start,
            event_start_day: start,
            event_end_day: start + 1,
            prep_hours: hours,
            importance,
            status,
            result: None,
            member_ids: vec![],
        }
    }

    /// 10 hours on every day, to keep arithmetic obvious.
    fn flat(max_concurrent: u32) -> CompetitionConfig {
        CompetitionConfig {
            weekday_hours: 10.0,
            weekend_hours: 10.0,
            max_concurrent,
            teammate_half_life_days: 365,
        }
    }

    fn verdict(plan: &CompetitionPlan, id: &str) -> CompetitionVerdict {
        plan.assessments
            .iter()
            .find(|a| a.id == id)
            .unwrap()
            .verdict
    }

    #[test]
    fn everything_fits_when_there_is_time() {
        let t = 100;
        let list = vec![
            comp("a", t, t + 10, 20.0, 3, CompetitionStatus::Planned),
            comp("b", t, t + 20, 30.0, 3, CompetitionStatus::Planned),
        ];
        let p = plan(&list, t, &flat(3));
        assert_eq!(p.selected_ids, vec!["a", "b"]);
        assert_eq!(verdict(&p, "a"), CompetitionVerdict::Recommended);
    }

    #[test]
    fn picks_the_more_important_competition_when_time_is_short() {
        let t = 100;
        // Both need 80 h in the same 10-day window of 100 h.
        let list = vec![
            comp("hackathon", t, t + 10, 80.0, 5, CompetitionStatus::Planned),
            comp("quiz", t, t + 10, 80.0, 2, CompetitionStatus::Planned),
        ];
        let p = plan(&list, t, &flat(3));
        assert_eq!(verdict(&p, "hackathon"), CompetitionVerdict::Recommended);
        assert_eq!(verdict(&p, "quiz"), CompetitionVerdict::NotEnoughTime);
    }

    #[test]
    fn registered_competitions_are_always_kept() {
        let t = 100;
        let list = vec![
            comp("reg", t, t + 10, 90.0, 1, CompetitionStatus::Registered),
            comp("plan", t, t + 10, 20.0, 5, CompetitionStatus::Planned),
        ];
        let p = plan(&list, t, &flat(3));
        assert_eq!(verdict(&p, "reg"), CompetitionVerdict::Committed);
        assert_eq!(verdict(&p, "plan"), CompetitionVerdict::NotEnoughTime);
    }

    #[test]
    fn overcommitment_is_reported() {
        let t = 100;
        let list = vec![
            comp("a", t, t + 5, 40.0, 3, CompetitionStatus::Registered),
            comp("b", t, t + 5, 40.0, 3, CompetitionStatus::Registered),
        ];
        let p = plan(&list, t, &flat(3));
        assert_eq!(verdict(&p, "a"), CompetitionVerdict::Overcommitted);
    }

    #[test]
    fn concurrency_limit_caps_parallel_competitions() {
        let t = 100;
        let list: Vec<Competition> = (0..4)
            .map(|i| {
                comp(
                    &format!("c{i}"),
                    t,
                    t + 20,
                    5.0,
                    3,
                    CompetitionStatus::Planned,
                )
            })
            .collect();
        let p = plan(&list, t, &flat(2));
        assert_eq!(p.peak_concurrency, 2);
        assert_eq!(p.max_parallel, 2);
        assert_eq!(
            p.assessments
                .iter()
                .filter(|a| a.verdict == CompetitionVerdict::TooManyAtOnce)
                .count(),
            2
        );
    }

    #[test]
    fn nested_windows_use_the_exact_interval_condition() {
        let t = 100;
        // Outer: 50 h over 10 days. Inner: 45 h inside days 2..=6 (50 h available).
        // Individually fine; together 95 h ≤ 100 h overall and 45 ≤ 50 inside, so feasible.
        let outer = comp("outer", t, t + 10, 50.0, 3, CompetitionStatus::Planned);
        let inner = comp("inner", t + 2, t + 7, 45.0, 3, CompetitionStatus::Planned);
        let p = plan(&[outer.clone(), inner.clone()], t, &flat(3));
        assert_eq!(p.selected_ids.len(), 2);
        // Inner needs 55 h in a 50 h window: infeasible even alone.
        let tight = Competition {
            prep_hours: 55.0,
            ..inner
        };
        let p = plan(&[outer, tight], t, &flat(3));
        assert_eq!(verdict(&p, "inner"), CompetitionVerdict::NotEnoughTime);
    }

    #[test]
    fn weekends_add_capacity() {
        let monday = days_from_civil(2026, 9, 28);
        let config = CompetitionConfig::default();
        // Mon..Sun: 5 × 1.5 + 2 × 4 = 15.5 h.
        assert!((hours_between(monday, monday + 6, &config) - 15.5).abs() < 1e-9);
    }

    #[test]
    fn spare_capacity_suggests_more() {
        let t = 100;
        let p = plan(
            &[comp("a", t, t + 10, 10.0, 3, CompetitionStatus::Planned)],
            t,
            &flat(3),
        );
        assert!(p.spare_hours_next_30_days > 250.0);
        assert_eq!(p.additional_capacity, 2);
        assert_eq!(p.max_parallel, 3);
    }

    #[test]
    fn past_and_completed_are_inactive() {
        let t = 100;
        let mut done = comp("done", 50, 60, 10.0, 3, CompetitionStatus::Completed);
        done.result = Some(CompetitionResult::Won);
        let old = comp("old", 50, 60, 10.0, 3, CompetitionStatus::Planned);
        let p = plan(&[done, old], t, &flat(3));
        assert!(p
            .assessments
            .iter()
            .all(|a| a.verdict == CompetitionVerdict::Inactive));
        assert!(p.selected_ids.is_empty());
    }

    #[test]
    fn teammates_ranked_by_recent_results() {
        let today = 1_000;
        let mut won_recently = comp("w", 0, today - 10, 0.0, 3, CompetitionStatus::Completed);
        won_recently.result = Some(CompetitionResult::Won);
        won_recently.member_ids = vec!["asha".into(), "bilal".into()];
        let mut won_long_ago = comp("w2", 0, today - 730, 0.0, 3, CompetitionStatus::Completed);
        won_long_ago.result = Some(CompetitionResult::Won);
        won_long_ago.member_ids = vec!["chen".into()];
        let mut participated = comp("p", 0, today - 10, 0.0, 3, CompetitionStatus::Completed);
        participated.result = Some(CompetitionResult::Participated);
        participated.member_ids = vec!["bilal".into()];

        let ranking = rank_teammates(
            &[won_recently, won_long_ago, participated],
            today,
            &CompetitionConfig::default(),
        );
        let ids: Vec<&str> = ranking.iter().map(|s| s.member_id.as_str()).collect();
        assert_eq!(ids, vec!["bilal", "asha", "chen"]);
        let bilal = &ranking[0];
        assert_eq!((bilal.competitions, bilal.wins), (2, 1));
        assert!((bilal.win_rate_percent - 50.0).abs() < 1e-9);
        // Two years old at a one-year half-life counts a quarter.
        assert!((ranking[2].score - 25.0).abs() < 0.2);
    }
}
