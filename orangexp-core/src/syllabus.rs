//! Deterministic syllabus scheduler.
//!
//! Given the remaining topics of every subject, their deadlines (exam dates) and
//! the free study minutes of each day, decide which topic is studied on which day.
//!
//! # Algorithm: slack-aware greedy allocation
//!
//! Days are processed in order. Within a day, capacity is handed out in chunks.
//! Each chunk goes to the subject with the highest *pressure*:
//!
//! ```text
//! pressure(s) = remaining_minutes(s) / capacity_left_until_deadline(s) × priority_weight(s)
//! ```
//!
//! A subject that needs a large fraction of its remaining capacity is urgent; a
//! subject with plenty of slack yields. Recomputing pressure after every chunk
//! interleaves subjects naturally and front-loads tight deadlines (a weighted
//! least-slack-first policy). Topics inside a subject are studied in syllabus order.
//!
//! Ties are broken by earlier deadline, higher priority, then subject id, so the
//! output is a pure function of the input. Missed days need no special handling:
//! re-running with today's date and current progress re-plans the future.

use std::collections::{BTreeMap, HashMap, VecDeque};

use serde::{Deserialize, Serialize};

use crate::time::EpochDay;
use crate::timetable::DayCapacity;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct SyllabusSubject {
    pub id: String,
    pub name: String,
    /// Exam or completion deadline. Without one, the configured horizon applies.
    pub deadline_day: Option<EpochDay>,
    /// 1 (low) ..= 5 (high).
    pub priority: u8,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct SyllabusTopic {
    pub id: String,
    pub subject_id: String,
    /// Position in study order within the subject (unit order, then topic order).
    pub sequence: u32,
    pub estimated_minutes: u32,
    /// 1 (easy) ..= 5 (hard); scales the estimate.
    pub difficulty: u8,
    pub completed: bool,
    /// Minutes already studied on this topic.
    pub progress_minutes: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
#[serde(default)]
pub struct StudyConfig {
    /// Preferred length of one study block.
    pub chunk_minutes: u32,
    /// Blocks shorter than this are not scheduled (unless they finish a topic).
    pub min_block_minutes: u32,
    pub max_minutes_per_subject_per_day: u32,
    /// New material should be finished this many days before the deadline.
    pub revision_days_before_deadline: u32,
    /// Planning horizon for subjects without a deadline.
    pub default_horizon_days: u32,
}

impl Default for StudyConfig {
    fn default() -> Self {
        Self {
            chunk_minutes: 45,
            min_block_minutes: 15,
            max_minutes_per_subject_per_day: 180,
            revision_days_before_deadline: 2,
            default_horizon_days: 60,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct ScheduleInput {
    pub today: EpochDay,
    pub subjects: Vec<SyllabusSubject>,
    pub topics: Vec<SyllabusTopic>,
    pub capacity: Vec<DayCapacity>,
    pub config: StudyConfig,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct StudyAssignment {
    pub epoch_day: EpochDay,
    pub subject_id: String,
    pub topic_id: String,
    pub minutes: u32,
    /// 1-based index of this block among all blocks of the topic.
    pub part_index: u32,
    pub part_count: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SubjectStatus {
    Complete,
    OnTrack,
    /// Not all remaining work fits before the deadline.
    AtRisk,
    /// The deadline is in the past and work remains.
    DeadlinePassed,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct SubjectForecast {
    pub subject_id: String,
    pub status: SubjectStatus,
    pub remaining_minutes: u32,
    pub scheduled_minutes: u32,
    pub shortfall_minutes: u32,
    /// Last day new material must be finished (deadline minus revision days).
    pub target_day: Option<EpochDay>,
    pub projected_finish_day: Option<EpochDay>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct StudySchedule {
    pub generated_for_day: EpochDay,
    pub assignments: Vec<StudyAssignment>,
    pub forecasts: Vec<SubjectForecast>,
    pub required_minutes: u32,
    pub scheduled_minutes: u32,
    pub feasible: bool,
}

/// Effective remaining minutes of a topic after difficulty scaling and progress.
pub fn remaining_minutes(topic: &SyllabusTopic, config: &StudyConfig) -> u32 {
    if topic.completed {
        return 0;
    }
    let factor = 0.7 + 0.15 * f64::from(topic.difficulty.clamp(1, 5) - 1);
    let effective = (f64::from(topic.estimated_minutes) * factor).ceil() as u32;
    match effective.saturating_sub(topic.progress_minutes) {
        // Studied longer than estimated but not done yet: plan one more block.
        0 => config.chunk_minutes.max(1),
        m => m,
    }
}

struct SubjectPlan<'a> {
    subject: &'a SyllabusSubject,
    target_day: EpochDay,
    queue: VecDeque<(String, u32)>,
    remaining: u64,
    initial_remaining: u64,
    scheduled: u64,
    finish: Option<EpochDay>,
    weight: f64,
}

pub fn generate(input: &ScheduleInput) -> StudySchedule {
    let config = &input.config;
    let today = input.today;
    let chunk = config.chunk_minutes.max(1);
    let min_block = config.min_block_minutes.max(1);
    let daily_cap = config.max_minutes_per_subject_per_day.max(min_block);

    let mut subjects: Vec<&SyllabusSubject> = input.subjects.iter().collect();
    subjects.sort_by(|a, b| a.id.cmp(&b.id));

    let mut topics: Vec<&SyllabusTopic> = input.topics.iter().collect();
    topics.sort_by(|a, b| {
        (&a.subject_id, a.sequence, &a.id).cmp(&(&b.subject_id, b.sequence, &b.id))
    });

    let mut plans: Vec<SubjectPlan> = subjects
        .iter()
        .map(|s| {
            let queue: VecDeque<(String, u32)> = topics
                .iter()
                .filter(|t| t.subject_id == s.id)
                .map(|t| (t.id.clone(), remaining_minutes(t, config)))
                .filter(|(_, m)| *m > 0)
                .collect();
            let remaining: u64 = queue.iter().map(|(_, m)| u64::from(*m)).sum();
            let target_day = match s.deadline_day {
                Some(deadline) => (deadline - config.revision_days_before_deadline as EpochDay)
                    .max(today.min(deadline)),
                None => today + config.default_horizon_days as EpochDay,
            };
            SubjectPlan {
                subject: s,
                target_day,
                queue,
                remaining,
                initial_remaining: remaining,
                scheduled: 0,
                finish: None,
                weight: 0.8 + 0.1 * f64::from(s.priority.clamp(1, 5)),
            }
        })
        .collect();

    let active = |p: &SubjectPlan| p.subject.deadline_day.is_none_or(|d| d >= today);
    let horizon_end = plans
        .iter()
        .filter(|p| active(p))
        .map(|p| p.target_day)
        .max()
        .unwrap_or(today - 1);

    let capacity: BTreeMap<EpochDay, u32> = input
        .capacity
        .iter()
        .map(|c| (c.epoch_day, c.minutes))
        .collect();
    let day_count = (horizon_end - today + 1).max(0) as usize;
    let caps: Vec<u64> = (0..day_count)
        .map(|i| u64::from(capacity.get(&(today + i as EpochDay)).copied().unwrap_or(0)))
        .collect();
    let mut prefix = vec![0u64; day_count + 1];
    for i in 0..day_count {
        prefix[i + 1] = prefix[i] + caps[i];
    }

    let mut raw: Vec<(EpochDay, usize, String, u32)> = Vec::new();
    let mut index: HashMap<(EpochDay, String), usize> = HashMap::new();

    for di in 0..day_count {
        let day = today + di as EpochDay;
        let mut left = caps[di];
        let mut used = vec![0u32; plans.len()];
        let mut blocked = vec![false; plans.len()];

        while left > 0 {
            let best = plans
                .iter()
                .enumerate()
                .filter(|(i, p)| {
                    !blocked[*i]
                        && p.remaining > 0
                        && active(p)
                        && p.target_day >= day
                        && used[*i] < daily_cap
                })
                .map(|(i, p)| {
                    let last = (p.target_day - today) as usize;
                    let future = left + (prefix[last + 1] - prefix[di + 1]);
                    let pressure = if future == 0 {
                        f64::INFINITY
                    } else {
                        p.remaining as f64 / future as f64 * p.weight
                    };
                    (i, pressure)
                })
                .max_by(|(ia, pa), (ib, pb)| {
                    let (a, b) = (&plans[*ia], &plans[*ib]);
                    pa.total_cmp(pb)
                        .then(b.target_day.cmp(&a.target_day))
                        .then(a.subject.priority.cmp(&b.subject.priority))
                        .then(b.subject.id.cmp(&a.subject.id))
                });
            let Some((i, _)) = best else { break };

            let plan = &mut plans[i];
            let (topic_id, topic_left) = plan
                .queue
                .front()
                .cloned()
                .expect("remaining > 0 implies a queued topic");
            let amount = u64::from(chunk)
                .min(left)
                .min(u64::from(topic_left))
                .min(u64::from(daily_cap - used[i])) as u32;
            if amount == 0 || (amount < min_block && amount < topic_left) {
                blocked[i] = true;
                continue;
            }

            let key = (day, topic_id.clone());
            match index.get(&key) {
                Some(&at) => raw[at].3 += amount,
                None => {
                    index.insert(key, raw.len());
                    raw.push((day, i, topic_id, amount));
                }
            }
            if topic_left == amount {
                plan.queue.pop_front();
            } else if let Some(front) = plan.queue.front_mut() {
                front.1 -= amount;
            }
            plan.remaining -= u64::from(amount);
            plan.scheduled += u64::from(amount);
            plan.finish = Some(day);
            left -= u64::from(amount);
            used[i] += amount;
        }
    }

    let mut parts: HashMap<&str, u32> = HashMap::new();
    for (_, _, topic, _) in &raw {
        *parts.entry(topic.as_str()).or_default() += 1;
    }
    let mut seen: HashMap<&str, u32> = HashMap::new();
    let assignments: Vec<StudyAssignment> = raw
        .iter()
        .map(|(day, i, topic, minutes)| {
            let n = seen.entry(topic.as_str()).or_default();
            *n += 1;
            StudyAssignment {
                epoch_day: *day,
                subject_id: plans[*i].subject.id.clone(),
                topic_id: topic.clone(),
                minutes: *minutes,
                part_index: *n,
                part_count: parts[topic.as_str()],
            }
        })
        .collect();

    let forecasts: Vec<SubjectForecast> = plans
        .iter()
        .map(|p| {
            let status = if p.initial_remaining == 0 {
                SubjectStatus::Complete
            } else if !active(p) {
                SubjectStatus::DeadlinePassed
            } else if p.remaining > 0 {
                SubjectStatus::AtRisk
            } else {
                SubjectStatus::OnTrack
            };
            SubjectForecast {
                subject_id: p.subject.id.clone(),
                status,
                remaining_minutes: p.initial_remaining as u32,
                scheduled_minutes: p.scheduled as u32,
                shortfall_minutes: p.remaining as u32,
                target_day: p.subject.deadline_day.map(|_| p.target_day),
                projected_finish_day: p.finish,
            }
        })
        .collect();

    let required: u64 = plans
        .iter()
        .filter(|p| active(p))
        .map(|p| p.initial_remaining)
        .sum();
    let scheduled: u64 = plans.iter().map(|p| p.scheduled).sum();
    StudySchedule {
        generated_for_day: today,
        feasible: forecasts
            .iter()
            .all(|f| matches!(f.status, SubjectStatus::Complete | SubjectStatus::OnTrack)),
        assignments,
        forecasts,
        required_minutes: required as u32,
        scheduled_minutes: scheduled as u32,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn subject(id: &str, deadline: Option<EpochDay>, priority: u8) -> SyllabusSubject {
        SyllabusSubject {
            id: id.into(),
            name: id.into(),
            deadline_day: deadline,
            priority,
        }
    }

    fn topic(id: &str, subject: &str, seq: u32, minutes: u32) -> SyllabusTopic {
        SyllabusTopic {
            id: id.into(),
            subject_id: subject.into(),
            sequence: seq,
            estimated_minutes: minutes,
            difficulty: 3,
            completed: false,
            progress_minutes: 0,
        }
    }

    fn caps(from: EpochDay, minutes: &[u32]) -> Vec<DayCapacity> {
        minutes
            .iter()
            .enumerate()
            .map(|(i, &m)| DayCapacity {
                epoch_day: from + i as EpochDay,
                minutes: m,
            })
            .collect()
    }

    fn config() -> StudyConfig {
        StudyConfig {
            revision_days_before_deadline: 0,
            ..StudyConfig::default()
        }
    }

    fn minutes_for(schedule: &StudySchedule, subject: &str) -> u32 {
        schedule
            .assignments
            .iter()
            .filter(|a| a.subject_id == subject)
            .map(|a| a.minutes)
            .sum()
    }

    #[test]
    fn schedules_topics_in_order_within_capacity() {
        let input = ScheduleInput {
            today: 100,
            subjects: vec![subject("dsa", Some(105), 3)],
            topics: vec![
                topic("stacks", "dsa", 2, 60),
                topic("lists", "dsa", 1, 90),
                topic("queues", "dsa", 3, 30),
            ],
            capacity: caps(100, &[120, 120, 120, 120, 120, 120]),
            config: config(),
        };
        let s = generate(&input);
        assert!(s.feasible);
        assert_eq!(s.required_minutes, 180);
        assert_eq!(s.scheduled_minutes, 180);
        let order: Vec<&str> = s.assignments.iter().map(|a| a.topic_id.as_str()).collect();
        assert_eq!(order.first(), Some(&"lists"));
        assert_eq!(order.last(), Some(&"queues"));
        for day in 100..106 {
            let total: u32 = s
                .assignments
                .iter()
                .filter(|a| a.epoch_day == day)
                .map(|a| a.minutes)
                .sum();
            assert!(total <= 120);
        }
        let lists: Vec<_> = s
            .assignments
            .iter()
            .filter(|a| a.topic_id == "lists")
            .collect();
        assert_eq!(lists.iter().map(|a| a.minutes).sum::<u32>(), 90);
        assert!(lists.iter().all(|a| a.part_count == lists.len() as u32));
    }

    #[test]
    fn tight_deadline_is_front_loaded() {
        let input = ScheduleInput {
            today: 0,
            subjects: vec![
                subject("exam_soon", Some(1), 3),
                subject("exam_later", Some(20), 3),
            ],
            topics: vec![
                topic("a1", "exam_soon", 1, 180),
                topic("b1", "exam_later", 1, 180),
            ],
            capacity: caps(0, &[180; 21]),
            config: config(),
        };
        let s = generate(&input);
        assert!(s.feasible);
        let soon_by_day1: u32 = s
            .assignments
            .iter()
            .filter(|a| a.subject_id == "exam_soon" && a.epoch_day <= 1)
            .map(|a| a.minutes)
            .sum();
        assert_eq!(soon_by_day1, 180);
    }

    #[test]
    fn infeasible_subjects_are_flagged() {
        let input = ScheduleInput {
            today: 0,
            subjects: vec![subject("dbms", Some(2), 3)],
            topics: vec![topic("norm", "dbms", 1, 600)],
            capacity: caps(0, &[60, 60, 60]),
            config: config(),
        };
        let s = generate(&input);
        assert!(!s.feasible);
        let f = &s.forecasts[0];
        assert_eq!(f.status, SubjectStatus::AtRisk);
        assert_eq!(f.scheduled_minutes, 180);
        assert_eq!(f.shortfall_minutes, 420);
    }

    #[test]
    fn missed_day_replans_remaining_work() {
        let base = ScheduleInput {
            today: 10,
            subjects: vec![subject("os", Some(14), 3)],
            topics: vec![
                topic("t1", "os", 1, 60),
                topic("t2", "os", 2, 60),
                topic("t3", "os", 3, 60),
            ],
            capacity: caps(10, &[60, 60, 60, 60, 60]),
            config: config(),
        };
        let first = generate(&base);
        assert_eq!(first.assignments[0].epoch_day, 10);
        // Day 10 was missed: the same work is re-planned starting day 11.
        let replanned = generate(&ScheduleInput { today: 11, ..base });
        assert!(replanned.feasible);
        assert_eq!(replanned.assignments[0].epoch_day, 11);
        assert_eq!(replanned.scheduled_minutes, 180);
    }

    #[test]
    fn respects_completion_progress_and_difficulty() {
        let mut done = topic("done", "x", 1, 60);
        done.completed = true;
        let mut partial = topic("partial", "x", 2, 100);
        partial.progress_minutes = 40;
        let mut hard = topic("hard", "x", 3, 100);
        hard.difficulty = 5;
        let config = config();
        assert_eq!(remaining_minutes(&done, &config), 0);
        assert_eq!(remaining_minutes(&partial, &config), 60);
        assert_eq!(remaining_minutes(&hard, &config), 130);
        let mut overshoot = topic("o", "x", 4, 30);
        overshoot.progress_minutes = 90;
        assert_eq!(remaining_minutes(&overshoot, &config), config.chunk_minutes);
    }

    #[test]
    fn subjects_interleave_and_respect_daily_cap() {
        let input = ScheduleInput {
            today: 0,
            subjects: vec![subject("a", Some(9), 3), subject("b", Some(9), 3)],
            topics: vec![topic("a1", "a", 1, 600), topic("b1", "b", 1, 600)],
            capacity: caps(0, &[240; 10]),
            config: StudyConfig {
                max_minutes_per_subject_per_day: 150,
                ..config()
            },
        };
        let s = generate(&input);
        for day in 0..10 {
            for subj in ["a", "b"] {
                let m: u32 = s
                    .assignments
                    .iter()
                    .filter(|x| x.epoch_day == day && x.subject_id == subj)
                    .map(|x| x.minutes)
                    .sum();
                assert!(m <= 150);
            }
        }
        assert_eq!(minutes_for(&s, "a"), 600);
        assert_eq!(minutes_for(&s, "b"), 600);
        let day0: Vec<&str> = s
            .assignments
            .iter()
            .filter(|x| x.epoch_day == 0)
            .map(|x| x.subject_id.as_str())
            .collect();
        assert!(day0.contains(&"a") && day0.contains(&"b"));
    }

    #[test]
    fn passed_deadlines_and_complete_subjects() {
        let mut done = topic("d", "complete", 1, 60);
        done.completed = true;
        let input = ScheduleInput {
            today: 50,
            subjects: vec![
                subject("old", Some(40), 3),
                subject("complete", Some(60), 3),
                subject("open", None, 3),
            ],
            topics: vec![topic("o1", "old", 1, 60), done, topic("n1", "open", 1, 60)],
            capacity: caps(50, &[60; 70]),
            config: config(),
        };
        let s = generate(&input);
        let status = |id: &str| {
            s.forecasts
                .iter()
                .find(|f| f.subject_id == id)
                .unwrap()
                .status
        };
        assert_eq!(status("old"), SubjectStatus::DeadlinePassed);
        assert_eq!(status("complete"), SubjectStatus::Complete);
        assert_eq!(status("open"), SubjectStatus::OnTrack);
        assert_eq!(minutes_for(&s, "old"), 0);
        assert!(!s.feasible);
    }

    #[test]
    fn deterministic_output() {
        let input = ScheduleInput {
            today: 0,
            subjects: vec![subject("b", Some(5), 2), subject("a", Some(5), 2)],
            topics: vec![topic("b1", "b", 1, 100), topic("a1", "a", 1, 100)],
            capacity: caps(0, &[90; 6]),
            config: config(),
        };
        let first = generate(&input);
        for _ in 0..10 {
            assert_eq!(generate(&input), first);
        }
    }
}
