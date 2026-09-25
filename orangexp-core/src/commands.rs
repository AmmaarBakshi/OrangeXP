//! Natural-language commands for Holstrom, the in-app assistant.
//!
//! Typed or spoken sentences become structured commands with a deterministic,
//! rule-based parser. No model is involved: the same sentence at the same
//! moment always gives the same result, and every rule is tested below.
//!
//! ```text
//! "remind me texting ria tommoro"            → Reminder  "Texting ria"   tomorrow 09:00
//! "i have to submit my assignment at 3 oct"  → Deadline  "Submit my assignment"
//!                                               nudges every day at 09:00 until 3 Oct
//! "silent my phone after 1hr"                → DeviceAction Silent        now + 60 min
//! "put phone on silent for 2 hours"          → DeviceAction Silent, revert to Ring after 2 h
//! "how did i sleep?"                         → Question  topic Sleep
//! ```
//!
//! Like the rest of the engine the parser never reads the clock: the caller
//! passes `now_ms` and the local UTC offset.
//!
//! # Reminder schedules
//!
//! [`next_fire`] turns a stored reminder into its next alarm time. A deadline
//! nudges once a day at `nudge_minute` on every day before it is due (including
//! the due day itself when the nudge comes first) and, when the deadline has a
//! clock time, once more at that time.

use serde::{Deserialize, Serialize};

use crate::time::{
    civil_from_days, days_from_civil, epoch_day_at, local_midnight_ms, minute_of_day_at,
    weekday_of, EpochDay, Weekday, MS_PER_DAY, MS_PER_MINUTE,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CommandKind {
    /// Notify once (or on a repeat rule) at `at_ms`.
    Reminder,
    /// Something due at `at_ms`; nudged every day until then.
    Deadline,
    /// Change something on the phone at `at_ms` (now or later).
    DeviceAction,
    /// Anything else: a question or conversation for the assistant.
    Question,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RepeatRule {
    None,
    Daily,
    Weekly,
    /// Daily nudges until `at_ms`, the deadline.
    DailyUntilDue,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DeviceAction {
    Silent,
    Vibrate,
    /// Normal ringer.
    Ring,
    DndOn,
    DndOff,
    FlashlightOn,
    FlashlightOff,
    /// Hand an alarm at `at_ms` to the clock app.
    SetAlarm,
    /// Hand a timer of `duration_minutes` to the clock app.
    SetTimer,
}

impl DeviceAction {
    /// The action that undoes this one, for "… for 2 hours" / "… until 5 pm".
    pub fn revert(self) -> Option<DeviceAction> {
        match self {
            DeviceAction::Silent | DeviceAction::Vibrate => Some(DeviceAction::Ring),
            DeviceAction::DndOn => Some(DeviceAction::DndOff),
            DeviceAction::FlashlightOn => Some(DeviceAction::FlashlightOff),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum QueryTopic {
    Summary,
    Score,
    Sleep,
    Steps,
    Streak,
    Classes,
    Study,
    Reminders,
    Competitions,
    Time,
    Date,
    /// Free conversation; only a language model can answer it well.
    Other,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct CommandInput {
    pub text: String,
    pub now_ms: i64,
    pub utc_offset_minutes: i32,
    /// Time used when a day is given without a clock time (minute of day).
    pub default_minute: u32,
    /// Delay for "remind me to …" with no time at all.
    pub default_delay_minutes: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct ParsedCommand {
    pub kind: CommandKind,
    /// What to be reminded of, cleaned of filler and time phrases. Empty for device actions.
    pub title: String,
    /// When to remind / the deadline / when to act. `None` for questions.
    pub at_ms: Option<i64>,
    /// The sentence contained a clock time or a relative offset.
    pub time_explicit: bool,
    /// The sentence contained a day or date.
    pub date_explicit: bool,
    pub repeat: RepeatRule,
    pub action: Option<DeviceAction>,
    /// When to undo `action` ("for 2 hours", "until 5 pm").
    pub revert_at_ms: Option<i64>,
    /// Timer length for [`DeviceAction::SetTimer`].
    pub duration_minutes: Option<u32>,
    pub topic: Option<QueryTopic>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct ReminderSchedule {
    pub at_ms: i64,
    pub repeat: RepeatRule,
    /// Minute of day for deadline nudges.
    pub nudge_minute: u32,
    /// Also alert at `at_ms` itself (deadlines with a clock time).
    pub final_alert: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct ReminderFire {
    pub at_ms: i64,
    /// Whole local days from the fire to the deadline (0 on the due day).
    pub days_left: i32,
    /// No further fires follow this one.
    pub is_final: bool,
}

/// Next alarm strictly after `after_ms`, or `None` when the reminder is over.
pub fn next_fire(
    schedule: &ReminderSchedule,
    after_ms: i64,
    utc_offset_minutes: i32,
) -> Option<ReminderFire> {
    let at = schedule.at_ms;
    match schedule.repeat {
        RepeatRule::None => (at > after_ms).then_some(ReminderFire {
            at_ms: at,
            days_left: 0,
            is_final: true,
        }),
        RepeatRule::Daily | RepeatRule::Weekly => {
            let step = if schedule.repeat == RepeatRule::Daily {
                1
            } else {
                7
            };
            if at > after_ms {
                return Some(ReminderFire {
                    at_ms: at,
                    days_left: 0,
                    is_final: false,
                });
            }
            // Same local clock time, `step` days apart, first one after `after_ms`.
            let minute = minute_of_day_at(at, utc_offset_minutes);
            let first_day = epoch_day_at(at, utc_offset_minutes);
            let mut day = epoch_day_at(after_ms, utc_offset_minutes);
            day += (step - (day - first_day).rem_euclid(step)) % step;
            loop {
                let candidate = at_minute(day, minute, utc_offset_minutes);
                if candidate > after_ms {
                    return Some(ReminderFire {
                        at_ms: candidate,
                        days_left: 0,
                        is_final: false,
                    });
                }
                day += step;
            }
        }
        RepeatRule::DailyUntilDue => {
            let due_day = epoch_day_at(at, utc_offset_minutes);
            let mut day = epoch_day_at(after_ms, utc_offset_minutes);
            let nudge = schedule.nudge_minute.min(1_439);
            while day <= due_day {
                let candidate = at_minute(day, nudge, utc_offset_minutes);
                if candidate > after_ms && candidate < at {
                    let is_final = !schedule.final_alert
                        && at_minute(day + 1, nudge, utc_offset_minutes) >= at;
                    return Some(ReminderFire {
                        at_ms: candidate,
                        days_left: due_day - day,
                        is_final,
                    });
                }
                day += 1;
            }
            (schedule.final_alert && at > after_ms).then_some(ReminderFire {
                at_ms: at,
                days_left: 0,
                is_final: true,
            })
        }
    }
}

fn at_minute(day: EpochDay, minute: u32, utc_offset_minutes: i32) -> i64 {
    local_midnight_ms(day, utc_offset_minutes) + i64::from(minute) * MS_PER_MINUTE
}

// ---------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
struct Token {
    lower: String,
    original: String,
    used: bool,
}

/// Where a date or time sits in the sentence, e.g. "until 5 pm" is a bound.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Marker {
    Plain,
    /// "by", "before": a deadline.
    By,
    /// "till", "until": a deadline for reminders, a revert time for actions.
    Until,
}

#[derive(Debug, Clone, Copy)]
struct Clock {
    minute: u32,
    /// A bare hour like "at 5" whose half of the day is not known yet.
    ambiguous: bool,
}

#[derive(Debug, Default)]
struct Found {
    day: Option<(EpochDay, Marker)>,
    clock: Option<Clock>,
    /// A clock time after "till"/"until".
    until_clock: Option<Clock>,
    /// An amount without "in"/"after"/"for", e.g. "timer 10 min".
    bare_minutes: Option<i64>,
    /// Morning/afternoon/evening/night default minute.
    part_of_day: Option<u32>,
    offset_minutes: Option<i64>,
    duration_minutes: Option<i64>,
    repeat: Option<RepeatRule>,
    weekly_day: Option<Weekday>,
}

/// Parses one sentence. Never fails: anything unrecognised is a [`CommandKind::Question`].
pub fn parse_command(input: &CommandInput) -> ParsedCommand {
    let offset = input.utc_offset_minutes;
    let now = input.now_ms;
    let today = epoch_day_at(now, offset);
    let now_minute = minute_of_day_at(now, offset);

    let raw = input.text.trim();
    let ends_with_question = raw.ends_with('?');
    let mut tokens = tokenize(raw);
    strip_wake_words(&mut tokens);

    let lower_all: String = tokens
        .iter()
        .map(|t| t.lower.as_str())
        .collect::<Vec<_>>()
        .join(" ");
    let has = |phrase: &str| contains_phrase(&lower_all, phrase);

    let mut found = Found::default();
    find_repeat(&mut tokens, &mut found);
    find_durations(&mut tokens, &mut found);
    find_dates(&mut tokens, &mut found, today);
    find_times(&mut tokens, &mut found);

    let remind_cue = REMIND_CUES.iter().any(|c| has(c));
    let first = tokens.first().map(|t| t.lower.as_str()).unwrap_or("");
    let action = if remind_cue || QUESTION_ONLY.contains(&first) {
        None
    } else {
        detect_action(&lower_all)
    };
    let has_when = found.day.is_some()
        || found.clock.is_some()
        || found.offset_minutes.is_some()
        || found.part_of_day.is_some()
        || found.repeat.is_some();
    let question_like = ends_with_question || QUESTION_STARTS.contains(&first);
    let deadline_cue = DEADLINE_CUES.iter().any(|c| has(c));

    // ---- resolve the moment -------------------------------------------------
    let resolve_clock = |day: EpochDay, clock: Clock| -> i64 {
        let mut minute = clock.minute;
        if clock.ambiguous && minute < 12 * 60 {
            let pm = minute + 12 * 60;
            minute = if day == today {
                // The next of the two readings still ahead today.
                if minute > now_minute {
                    minute
                } else {
                    pm
                }
            } else if minute < 8 * 60 {
                pm
            } else {
                minute
            };
        }
        at_minute(day, minute, offset)
    };

    let date_explicit = found.day.is_some() || found.weekly_day.is_some();
    let time_explicit = found.clock.is_some() || found.offset_minutes.is_some();
    let default_minute = found.part_of_day.unwrap_or(input.default_minute.min(1_439));

    let point_in_time = |clock: Option<Clock>, day: Option<EpochDay>| -> Option<i64> {
        if let Some(offset_minutes) = found.offset_minutes {
            return Some(now + offset_minutes * MS_PER_MINUTE);
        }
        match (day, clock) {
            (Some(d), Some(c)) => Some(resolve_clock(d, c)),
            (Some(d), None) => {
                let t = at_minute(d, default_minute, offset);
                // "today" with a default time already past: soon instead.
                Some(if d == today && t <= now {
                    now + i64::from(input.default_delay_minutes) * MS_PER_MINUTE
                } else {
                    t
                })
            }
            (None, Some(c)) => {
                let t = resolve_clock(today, c);
                Some(if t > now {
                    t
                } else {
                    resolve_clock(today + 1, c)
                })
            }
            (None, None) => found.part_of_day.map(|m| {
                let t = at_minute(today, m, offset);
                if t > now {
                    t
                } else {
                    at_minute(today + 1, m, offset)
                }
            }),
        }
    };

    let base = ParsedCommand {
        kind: CommandKind::Question,
        title: String::new(),
        at_ms: None,
        time_explicit,
        date_explicit,
        repeat: RepeatRule::None,
        action: None,
        revert_at_ms: None,
        duration_minutes: None,
        topic: None,
    };

    // ---- device actions -----------------------------------------------------
    if let Some(action) = action {
        let until_clock = found.until_clock;
        let until_day = found
            .day
            .filter(|(_, m)| *m == Marker::Until)
            .map(|(d, _)| d);
        // "wake me up at 7" is 7 am.
        let start_clock = found.clock.map(|mut c| {
            if action == DeviceAction::SetAlarm && c.ambiguous {
                c.ambiguous = false;
            }
            c
        });
        let start_day = found
            .day
            .filter(|(_, m)| *m != Marker::Until)
            .map(|(d, _)| d);

        let mut at =
            if start_clock.is_some() || start_day.is_some() || found.offset_minutes.is_some() {
                point_in_time(start_clock, start_day).unwrap_or(now)
            } else {
                now
            };
        let mut duration = found.duration_minutes;
        if action == DeviceAction::SetTimer && duration.is_none() {
            // "timer 10 minutes" or "timer in 10 minutes": the amount is the timer length.
            duration = found.bare_minutes.or(found.offset_minutes);
            at = now;
        }
        let revert_at = match action.revert() {
            Some(_) if duration.is_some() && action != DeviceAction::SetTimer => {
                Some(at + duration.unwrap_or(0) * MS_PER_MINUTE)
            }
            Some(_) if until_clock.is_some() || until_day.is_some() => {
                let day = until_day.unwrap_or(epoch_day_at(at, offset));
                let t = match until_clock {
                    Some(c) => resolve_clock(day, c),
                    None => at_minute(day, default_minute, offset),
                };
                Some(if t > at { t } else { t + MS_PER_DAY })
            }
            _ => None,
        };
        return ParsedCommand {
            kind: CommandKind::DeviceAction,
            at_ms: Some(at),
            action: Some(action),
            revert_at_ms: revert_at,
            duration_minutes: duration.map(|d| d.clamp(1, 24 * 60) as u32),
            ..base
        };
    }

    // ---- questions and conversation ----------------------------------------
    if !remind_cue && (question_like || !has_when) {
        return ParsedCommand {
            topic: Some(detect_topic(&lower_all)),
            ..base
        };
    }

    // ---- reminders and deadlines -------------------------------------------
    let title = extract_title(&tokens);
    let day = found.day.map(|(d, _)| d);
    // "finish the essay until 5pm": with nothing else, the bound is the time.
    let clock_or_until = found.clock.or(found.until_clock);
    let marker = found.day.map(|(_, m)| m).unwrap_or(Marker::Plain);

    if let Some(repeat) = found.repeat.filter(|r| *r != RepeatRule::DailyUntilDue) {
        // "every monday at 7": the first occurrence on that weekday.
        let first_day = found
            .weekly_day
            .map(|w| today + w.days_since(weekday_of(today)) as i32);
        let start_day = first_day.or(day).unwrap_or(today);
        let mut at = match clock_or_until {
            Some(c) => resolve_clock(start_day, c),
            None => at_minute(start_day, default_minute, offset),
        };
        let step = if repeat == RepeatRule::Weekly { 7 } else { 1 };
        while at <= now {
            at += i64::from(step) * MS_PER_DAY;
        }
        return ParsedCommand {
            kind: CommandKind::Reminder,
            title,
            at_ms: Some(at),
            repeat,
            ..base
        };
    }

    let explicit_until = found.repeat == Some(RepeatRule::DailyUntilDue) || marker == Marker::Until;
    let is_deadline = day.is_some_and(|d| d > today)
        && (explicit_until || marker == Marker::By || (deadline_cue && !remind_cue));

    if is_deadline {
        let due_day = day.unwrap_or(today);
        let at = match clock_or_until {
            Some(c) => resolve_clock(due_day, c),
            None => at_minute(due_day, 23 * 60 + 59, offset),
        };
        return ParsedCommand {
            kind: CommandKind::Deadline,
            title,
            at_ms: Some(at),
            repeat: RepeatRule::DailyUntilDue,
            ..base
        };
    }

    let at = point_in_time(clock_or_until, day)
        .unwrap_or(now + i64::from(input.default_delay_minutes) * MS_PER_MINUTE);
    ParsedCommand {
        kind: CommandKind::Reminder,
        title,
        at_ms: Some(at.max(now + MS_PER_MINUTE)),
        ..base
    }
}

// ---- vocabulary -------------------------------------------------------------

const REMIND_CUES: &[&str] = &[
    "remind me",
    "remind us",
    "set a reminder",
    "set reminder",
    "add a reminder",
    "add reminder",
    "create a reminder",
    "reminder to",
    "reminder for",
    "don't forget",
    "dont forget",
    "do not forget",
    "notify me",
    "ping me",
    "alert me",
    "tell me to",
    "msg me",
    "message me",
    "text me to",
];

const DEADLINE_CUES: &[&str] = &[
    "have to",
    "has to",
    "need to",
    "needs to",
    "must",
    "got to",
    "gotta",
    "submit",
    "submission",
    "due",
    "deadline",
    "assignment",
    "exam",
    "project",
    "homework",
    "complete",
    "finish",
    "pay",
];

/// Openers that make a sentence a question even when it mentions the phone ("why is my phone silent").
const QUESTION_ONLY: &[&str] = &[
    "what", "what's", "whats", "why", "how", "how's", "hows", "when", "when's", "where", "who",
    "which", "is", "are", "does", "did",
];

const QUESTION_STARTS: &[&str] = &[
    "what",
    "what's",
    "whats",
    "how",
    "how's",
    "hows",
    "when",
    "when's",
    "where",
    "why",
    "who",
    "which",
    "is",
    "are",
    "am",
    "do",
    "does",
    "did",
    "can",
    "could",
    "should",
    "would",
    "will",
    "tell",
    "show",
    "give",
    "explain",
    "analyse",
    "analyze",
    "summarize",
    "summarise",
    "hi",
    "hello",
    "hey",
    "thanks",
    "thank",
];

const FILLER_PREFIXES: &[&str] = &[
    "can you please",
    "could you please",
    "can you",
    "could you",
    "please",
    "pls",
    "plz",
    "set a reminder to",
    "set a reminder for",
    "set a reminder",
    "set reminder to",
    "set reminder",
    "add a reminder to",
    "add reminder to",
    "create a reminder to",
    "remind me to",
    "remind me that",
    "remind me about",
    "remind me of",
    "remind me for",
    "remind me",
    "remind",
    "reminder to",
    "reminder for",
    "reminder",
    "don't forget to",
    "dont forget to",
    "do not forget to",
    "don't let me forget to",
    "dont let me forget to",
    "notify me to",
    "notify me about",
    "notify me",
    "ping me to",
    "ping me",
    "alert me to",
    "alert me",
    "tell me to",
    "msg me to",
    "message me to",
    "text me to",
    "msg me",
    "message me",
    "i have to",
    "i've got to",
    "i have got to",
    "i got to",
    "i gotta",
    "i need to",
    "i must",
    "i should",
    "i will",
    "i'll",
    "i want to",
    "have to",
    "need to",
    "to",
    "that",
    "about",
    "me",
];

const DANGLING: &[&str] = &[
    "at", "on", "by", "in", "till", "until", "before", "after", "for", "the", "every", "this",
    "next", "from", "of", "around", "@", "and", "to", "coming",
];

const MONTHS: &[(&str, u32)] = &[
    ("jan", 1),
    ("january", 1),
    ("feb", 2),
    ("february", 2),
    ("mar", 3),
    ("march", 3),
    ("apr", 4),
    ("april", 4),
    ("may", 5),
    ("jun", 6),
    ("june", 6),
    ("jul", 7),
    ("july", 7),
    ("aug", 8),
    ("august", 8),
    ("sep", 9),
    ("sept", 9),
    ("september", 9),
    ("oct", 10),
    ("october", 10),
    ("nov", 11),
    ("november", 11),
    ("dec", 12),
    ("december", 12),
];

/// Full weekday names are always dates; short forms only after "on", "next", ….
const WEEKDAYS: &[(&str, Weekday, bool)] = &[
    ("monday", Weekday::Monday, true),
    ("tuesday", Weekday::Tuesday, true),
    ("wednesday", Weekday::Wednesday, true),
    ("thursday", Weekday::Thursday, true),
    ("friday", Weekday::Friday, true),
    ("saturday", Weekday::Saturday, true),
    ("sunday", Weekday::Sunday, true),
    ("mon", Weekday::Monday, false),
    ("tue", Weekday::Tuesday, false),
    ("tues", Weekday::Tuesday, false),
    ("wed", Weekday::Wednesday, false),
    ("thu", Weekday::Thursday, false),
    ("thur", Weekday::Thursday, false),
    ("thurs", Weekday::Thursday, false),
    ("fri", Weekday::Friday, false),
    ("sat", Weekday::Saturday, false),
    ("sun", Weekday::Sunday, false),
];

const DAY_PREPOSITIONS: &[&str] = &[
    "on", "at", "by", "before", "till", "until", "til", "this", "next", "coming", "upto", "for",
];

fn marker_of(word: &str) -> Option<Marker> {
    match word {
        "by" | "before" => Some(Marker::By),
        "till" | "until" | "til" | "upto" => Some(Marker::Until),
        "on" | "at" | "this" | "next" | "coming" | "around" | "@" | "for" | "from" => {
            Some(Marker::Plain)
        }
        _ => None,
    }
}

// ---- tokenizer ----------------------------------------------------------------

const UNIT_SUFFIXES: &[&str] = &[
    "am", "pm", "a.m.", "p.m.", "a.m", "p.m", "h", "hr", "hrs", "hour", "hours", "m", "min",
    "mins", "minute", "minutes", "d", "day", "days", "w", "wk", "wks", "week", "weeks", "sec",
    "secs",
];

fn tokenize(text: &str) -> Vec<Token> {
    let cleaned: String = text
        .chars()
        .map(|c| match c {
            ',' | '!' | '?' | ';' | '"' | '(' | ')' => ' ',
            '’' => '\'',
            _ => c,
        })
        .collect();
    let mut out = Vec::new();
    for raw in cleaned.split_whitespace() {
        let raw = raw.trim_end_matches('.');
        if raw.is_empty() {
            continue;
        }
        let lower = raw.to_lowercase();
        // "5pm" → "5" "pm", "1hr" → "1" "hr", "10:30pm" → "10:30" "pm".
        let split_at = lower
            .char_indices()
            .find(|(_, c)| c.is_alphabetic())
            .map(|(i, _)| i)
            .filter(|&i| {
                i > 0
                    && lower[..i]
                        .chars()
                        .all(|c| c.is_ascii_digit() || c == ':' || c == '.')
            });
        if let Some(i) = split_at {
            let suffix = &lower[i..];
            if UNIT_SUFFIXES.contains(&suffix) {
                out.push(Token {
                    lower: lower[..i].to_owned(),
                    original: raw[..i].to_owned(),
                    used: false,
                });
                out.push(Token {
                    lower: suffix.to_owned(),
                    original: raw[i..].to_owned(),
                    used: false,
                });
                continue;
            }
        }
        out.push(Token {
            lower,
            original: raw.to_owned(),
            used: false,
        });
    }
    out
}

fn strip_wake_words(tokens: &mut Vec<Token>) {
    let wake = ["hey", "hi", "ok", "okay", "yo"];
    let names = [
        "holstrom",
        "holstorm",
        "holstrum",
        "holstrom's",
        "uih32",
        "jarvis",
    ];
    let mut i = 0;
    while i < tokens.len() && i < 2 && wake.contains(&tokens[i].lower.as_str()) {
        i += 1;
    }
    if i < tokens.len() && names.contains(&tokens[i].lower.as_str()) {
        tokens.drain(..=i);
    }
}

fn contains_phrase(haystack: &str, phrase: &str) -> bool {
    let padded = format!(" {haystack} ");
    padded.contains(&format!(" {phrase} ")) || {
        // Single words also match as prefixes of longer words ("remind" → "reminded").
        !phrase.contains(' ')
            && padded
                .split(' ')
                .any(|w| w.starts_with(phrase) && phrase.len() >= 5)
    }
}

fn word(tokens: &[Token], i: usize) -> &str {
    tokens.get(i).map(|t| t.lower.as_str()).unwrap_or("")
}

fn mark(tokens: &mut [Token], from: usize, to_exclusive: usize) {
    for t in tokens.iter_mut().take(to_exclusive).skip(from) {
        t.used = true;
    }
}

/// Marks a preposition just before `i` as used and returns its meaning.
fn take_marker(tokens: &mut [Token], i: usize) -> Marker {
    if i == 0 {
        return Marker::Plain;
    }
    let prev = tokens[i - 1].lower.as_str();
    match marker_of(prev) {
        Some(m) if !tokens[i - 1].used => {
            tokens[i - 1].used = true;
            // "on or before", "by the": look one further back for "by"/"till".
            if i >= 2 && !tokens[i - 2].used {
                if let Some(m2 @ (Marker::By | Marker::Until)) = marker_of(&tokens[i - 2].lower) {
                    tokens[i - 2].used = true;
                    return m2;
                }
            }
            m
        }
        _ => {
            if prev == "the" && i >= 2 {
                tokens[i - 1].used = true;
                return take_marker(tokens, i - 1);
            }
            Marker::Plain
        }
    }
}

// ---- numbers ----------------------------------------------------------------------

fn number_word(w: &str) -> Option<f64> {
    let n = match w {
        "a" | "an" | "one" => 1.0,
        "two" | "couple" => 2.0,
        "three" => 3.0,
        "four" => 4.0,
        "five" => 5.0,
        "six" => 6.0,
        "seven" => 7.0,
        "eight" => 8.0,
        "nine" => 9.0,
        "ten" => 10.0,
        "eleven" => 11.0,
        "twelve" => 12.0,
        "fifteen" => 15.0,
        "twenty" => 20.0,
        "thirty" => 30.0,
        "forty" => 40.0,
        "fortyfive" | "forty-five" => 45.0,
        "fifty" => 50.0,
        "sixty" => 60.0,
        "ninety" => 90.0,
        "few" => 3.0,
        _ => return w.parse::<f64>().ok().filter(|n| n.is_finite() && *n >= 0.0),
    };
    Some(n)
}

fn unit_minutes(w: &str) -> Option<f64> {
    match w {
        "m" | "min" | "mins" | "minute" | "minutes" => Some(1.0),
        "h" | "hr" | "hrs" | "hour" | "hours" => Some(60.0),
        "d" | "day" | "days" => Some(1_440.0),
        "w" | "wk" | "wks" | "week" | "weeks" => Some(10_080.0),
        _ => None,
    }
}

fn ordinal_day(w: &str) -> Option<u32> {
    let digits = w
        .strip_suffix("st")
        .or_else(|| w.strip_suffix("nd"))
        .or_else(|| w.strip_suffix("rd"))
        .or_else(|| w.strip_suffix("th"))?;
    digits.parse::<u32>().ok().filter(|d| (1..=31).contains(d))
}

fn plain_day(w: &str) -> Option<u32> {
    ordinal_day(w).or_else(|| {
        (w.len() <= 2)
            .then(|| w.parse::<u32>().ok())
            .flatten()
            .filter(|d| (1..=31).contains(d))
    })
}

fn month_of(w: &str) -> Option<u32> {
    MONTHS.iter().find(|(name, _)| *name == w).map(|(_, m)| *m)
}

fn is_tomorrow(w: &str) -> bool {
    matches!(
        w,
        "tomorrow"
            | "tomorow"
            | "tommorow"
            | "tommorrow"
            | "tomorro"
            | "tommoro"
            | "tomoro"
            | "tomm"
            | "tmrw"
            | "tmr"
            | "tmrow"
            | "tmorrow"
            | "2morrow"
            | "2moro"
            | "2mrw"
            | "tomorrows"
    ) || (w.starts_with("tom")
        && w.len() >= 6
        && (w.ends_with("ow") || w.ends_with("ro") || w.ends_with("rw")))
}

// ---- finders --------------------------------------------------------------------

fn find_repeat(tokens: &mut [Token], found: &mut Found) {
    let n = tokens.len();
    for i in 0..n {
        let w = word(tokens, i).to_owned();
        match w.as_str() {
            "daily" | "everyday" => {
                found.repeat = Some(RepeatRule::Daily);
                mark(tokens, i, i + 1);
            }
            "weekly" => {
                found.repeat = Some(RepeatRule::Weekly);
                mark(tokens, i, i + 1);
            }
            "every" | "each" => {
                let next = word(tokens, i + 1).to_owned();
                if matches!(next.as_str(), "day" | "morning" | "evening" | "night") {
                    found.repeat = Some(RepeatRule::Daily);
                    if next != "day" {
                        found.part_of_day = part_of_day(&next);
                    }
                    mark(tokens, i, i + 2);
                } else if next == "week" {
                    found.repeat = Some(RepeatRule::Weekly);
                    mark(tokens, i, i + 2);
                } else if let Some((_, wd, _)) = WEEKDAYS.iter().find(|(name, _, _)| *name == next)
                {
                    found.repeat = Some(RepeatRule::Weekly);
                    found.weekly_day = Some(*wd);
                    mark(tokens, i, i + 2);
                }
            }
            _ => {}
        }
    }
    // "every day till/until X" is a deadline with daily nudges.
    if found.repeat == Some(RepeatRule::Daily)
        && tokens
            .iter()
            .any(|t| matches!(t.lower.as_str(), "till" | "until" | "til" | "upto"))
    {
        found.repeat = Some(RepeatRule::DailyUntilDue);
    }
}

fn find_durations(tokens: &mut [Token], found: &mut Found) {
    let n = tokens.len();
    let mut i = 0;
    while i < n {
        if tokens[i].used {
            i += 1;
            continue;
        }
        let w = word(tokens, i).to_owned();
        let amount = if w == "half"
            && word(tokens, i + 1) == "an"
            && unit_minutes(word(tokens, i + 2)) == Some(60.0)
        {
            Some((30.0, 3))
        } else if w == "half" && unit_minutes(word(tokens, i + 1)) == Some(60.0) {
            Some((30.0, 2))
        } else if w == "a" && word(tokens, i + 1) == "couple" && word(tokens, i + 2) == "of" {
            unit_minutes(word(tokens, i + 3)).map(|u| (2.0 * u, 4))
        } else {
            match (number_word(&w), unit_minutes(word(tokens, i + 1))) {
                (Some(num), Some(u)) => Some((num * u, 2)),
                _ => None,
            }
        };
        let Some((minutes, consumed)) = amount else {
            i += 1;
            continue;
        };
        let minutes = minutes.round() as i64;
        let end = i + consumed;
        let prev = if i > 0 {
            word(tokens, i - 1).to_owned()
        } else {
            String::new()
        };
        let next = word(tokens, end).to_owned();
        let from_now = next == "from" && word(tokens, end + 1) == "now";
        let later = next == "later" || from_now;
        if matches!(prev.as_str(), "in" | "after" | "within") {
            found.offset_minutes = Some(found.offset_minutes.unwrap_or(0) + minutes);
            mark(tokens, i - 1, end);
        } else if later {
            found.offset_minutes = Some(found.offset_minutes.unwrap_or(0) + minutes);
            mark(tokens, i, (end + if from_now { 2 } else { 1 }).min(n));
        } else if prev == "for" {
            found.duration_minutes = Some(minutes);
            mark(tokens, i - 1, end);
        } else if w.chars().all(|c| c.is_ascii_digit() || c == '.') {
            // "timer 10 min": a bare amount, only ever used as a timer length.
            found.bare_minutes.get_or_insert(minutes);
            mark(tokens, i, end);
        } else {
            // "twice a day" is not a time.
            i += 1;
            continue;
        }
        i = end;
    }
}

fn find_dates(tokens: &mut [Token], found: &mut Found, today: EpochDay) {
    let (year, month_today, dom_today) = civil_from_days(today);
    let resolve = |y: Option<i32>, m: u32, d: u32| -> Option<EpochDay> {
        let valid = |y: i32| civil_from_days(days_from_civil(y, m, d)) == (y, m, d);
        match y {
            Some(y) => valid(y).then(|| days_from_civil(y, m, d)),
            None => {
                let this_year = valid(year)
                    .then(|| days_from_civil(year, m, d))
                    .filter(|&day| day >= today);
                this_year.or_else(|| valid(year + 1).then(|| days_from_civil(year + 1, m, d)))
            }
        }
    };
    let year_at = |tokens: &[Token], i: usize| -> Option<i32> {
        word(tokens, i)
            .parse::<i32>()
            .ok()
            .filter(|y| (2000..=2100).contains(y))
    };

    let n = tokens.len();
    let mut i = 0;
    while i < n {
        if tokens[i].used {
            i += 1;
            continue;
        }
        let w = word(tokens, i).to_owned();
        let mut hit: Option<(EpochDay, usize)> = None; // (day, tokens consumed from i)

        if w == "today" || w == "tonight" || w == "tonite" {
            hit = Some((today, 1));
            if w != "today" {
                found.part_of_day = Some(21 * 60);
            }
        } else if w == "day" && word(tokens, i + 1) == "after" && is_tomorrow(word(tokens, i + 2)) {
            hit = Some((today + 2, 3));
        } else if w == "overmorrow" {
            hit = Some((today + 2, 1));
        } else if is_tomorrow(&w) {
            hit = Some((today + 1, 1));
        } else if let Some((_, wd, always)) = WEEKDAYS.iter().find(|(name, _, _)| *name == w) {
            let prev = if i > 0 { word(tokens, i - 1) } else { "" };
            if *always || DAY_PREPOSITIONS.contains(&prev) {
                let mut ahead = wd.days_since(weekday_of(today)) as i32;
                if ahead == 0 && prev != "this" {
                    ahead = 7;
                }
                hit = Some((today + ahead, 1));
            }
        } else if let Some(d) = plain_day(&w) {
            // "3 oct", "3rd of october", "3 oct 2026"
            let (m_at, skip) = if word(tokens, i + 1) == "of" {
                (i + 2, 3)
            } else {
                (i + 1, 2)
            };
            if let Some(m) = month_of(word(tokens, m_at)) {
                let y = year_at(tokens, m_at + 1);
                if let Some(day) = resolve(y, m, d) {
                    hit = Some((day, skip + usize::from(y.is_some())));
                }
            } else if ordinal_day(&w).is_some() {
                // "on the 5th": this month, or next month once it has passed.
                let prev = if i > 0 { word(tokens, i - 1) } else { "" };
                if DAY_PREPOSITIONS.contains(&prev) || prev == "the" {
                    let (y, m) = if d >= dom_today {
                        (year, month_today)
                    } else if month_today == 12 {
                        (year + 1, 1)
                    } else {
                        (year, month_today + 1)
                    };
                    if let Some(day) = resolve(Some(y), m, d) {
                        hit = Some((day, 1));
                    }
                }
            }
        } else if let Some(m) = month_of(&w) {
            // "oct 3", "october 3rd", "oct 3 2026"
            if let Some(d) = plain_day(word(tokens, i + 1)) {
                let y = year_at(tokens, i + 2);
                if let Some(day) = resolve(y, m, d) {
                    hit = Some((day, 2 + usize::from(y.is_some())));
                }
            }
        } else if let Some(day) = numeric_date(&w, today, &resolve) {
            hit = Some((day, 1));
        }

        if let Some((day, consumed)) = hit {
            let marker = take_marker(tokens, i);
            mark(tokens, i, i + consumed);
            // Keep the first date, except that a bound ("till 3 oct") wins over a plain one.
            if found.day.is_none() || marker != Marker::Plain {
                found.day = Some((day, marker));
            }
            i += consumed;
        } else {
            i += 1;
        }
    }
}

/// "3/10", "3-10", "3/10/2026": day first.
fn numeric_date(
    w: &str,
    _today: EpochDay,
    resolve: &dyn Fn(Option<i32>, u32, u32) -> Option<EpochDay>,
) -> Option<EpochDay> {
    let sep = if w.contains('/') {
        '/'
    } else if w.contains('-') {
        '-'
    } else {
        return None;
    };
    let parts: Vec<&str> = w.split(sep).collect();
    if !(2..=3).contains(&parts.len()) {
        return None;
    }
    let d = parts[0]
        .parse::<u32>()
        .ok()
        .filter(|d| (1..=31).contains(d))?;
    let m = parts[1]
        .parse::<u32>()
        .ok()
        .filter(|m| (1..=12).contains(m))?;
    let y = match parts.get(2) {
        Some(y) => {
            let y = y.parse::<i32>().ok()?;
            Some(if y < 100 { 2000 + y } else { y })
        }
        None => None,
    };
    resolve(y, m, d)
}

fn part_of_day(w: &str) -> Option<u32> {
    match w {
        "morning" => Some(9 * 60),
        "noon" | "midday" | "lunch" => Some(12 * 60),
        "afternoon" => Some(14 * 60),
        "evening" => Some(18 * 60),
        "night" | "tonight" => Some(21 * 60),
        _ => None,
    }
}

fn find_times(tokens: &mut [Token], found: &mut Found) {
    let n = tokens.len();
    let mut i = 0;
    while i < n {
        if tokens[i].used {
            i += 1;
            continue;
        }
        let w = word(tokens, i).to_owned();
        let next = word(tokens, i + 1).to_owned();
        let prev = if i > 0 {
            word(tokens, i - 1).to_owned()
        } else {
            String::new()
        };
        let meridiem = match next.as_str() {
            "am" | "a.m" | "a.m." => Some(false),
            "pm" | "p.m" | "p.m." => Some(true),
            _ => None,
        };

        let mut hit: Option<(u32, u32, bool, usize)> = None; // hour, minute, ambiguous, consumed
        if let Some((h, m)) = clock_digits(&w) {
            match meridiem {
                Some(pm) if (1..=12).contains(&h) => hit = Some((to_24(h, pm), m, false, 2)),
                None if w.contains(':') && h < 24 => hit = Some((h, m, h < 12 && h != 0, 1)),
                None if w.contains('.')
                    && h < 24
                    && matches!(prev.as_str(), "at" | "@" | "by" | "around") =>
                {
                    hit = Some((h, m, h < 12 && h != 0, 1))
                }
                _ => {}
            }
        } else if let Some(h) = w.parse::<u32>().ok().filter(|_| !w.contains('.')) {
            if let Some(pm) = meridiem.filter(|_| (1..=12).contains(&h)) {
                hit = Some((to_24(h, pm), 0, false, 2));
            } else if h < 24
                && matches!(
                    prev.as_str(),
                    "at" | "@" | "by" | "around" | "before" | "till" | "until" | "til"
                )
                && unit_minutes(&next).is_none()
                && month_of(&next).is_none()
            {
                let oclock = matches!(next.as_str(), "o'clock" | "oclock");
                hit = Some((h, 0, (1..12).contains(&h), 1 + usize::from(oclock)));
            }
        } else if let Some(pod) = match w.as_str() {
            "noon" | "midday" => Some(12 * 60),
            "midnight" => Some(24 * 60 - 1),
            _ => None,
        } {
            let marker = take_marker(tokens, i);
            tokens[i].used = true;
            let clock = Clock {
                minute: pod,
                ambiguous: false,
            };
            if marker == Marker::Until {
                found.until_clock.get_or_insert(clock);
            } else {
                found.clock.get_or_insert(clock);
            }
            i += 1;
            continue;
        } else if let Some(pod) = part_of_day(&w) {
            // "in the morning", "at night", "this evening"
            if matches!(prev.as_str(), "the" | "at" | "this" | "in" | "every")
                || i == 0
                || found.day.is_some()
            {
                tokens[i].used = true;
                if prev == "the" && i >= 2 && word(tokens, i - 2) == "in" {
                    tokens[i - 1].used = true;
                    tokens[i - 2].used = true;
                } else if matches!(prev.as_str(), "at" | "this" | "in") {
                    tokens[i - 1].used = true;
                }
                found.part_of_day.get_or_insert(pod);
            }
            i += 1;
            continue;
        }

        if let Some((h, m, ambiguous, consumed)) = hit {
            let marker = take_marker(tokens, i);
            mark(tokens, i, i + consumed);
            let clock = Clock {
                minute: h * 60 + m,
                ambiguous,
            };
            if marker == Marker::Until {
                found.until_clock.get_or_insert(clock);
            } else {
                found.clock.get_or_insert(clock);
            }
            i += consumed;
        } else {
            i += 1;
        }
    }
    // "7 in the evening": the part of day settles an ambiguous clock.
    if let (Some(clock), Some(pod)) = (found.clock.as_mut(), found.part_of_day) {
        if clock.ambiguous {
            clock.ambiguous = false;
            if pod >= 12 * 60 && clock.minute < 12 * 60 {
                clock.minute += 12 * 60;
            }
        }
    }
}

fn clock_digits(w: &str) -> Option<(u32, u32)> {
    let (h, m) = w.split_once(':').or_else(|| w.split_once('.'))?;
    let h = h.parse::<u32>().ok()?;
    let m = m.parse::<u32>().ok().filter(|m| *m < 60)?;
    (h <= 24).then_some((h, m))
}

fn to_24(hour: u32, pm: bool) -> u32 {
    match (hour, pm) {
        (12, false) => 0,
        (12, true) => 12,
        (h, true) => h + 12,
        (h, false) => h,
    }
}

// ---- intent ---------------------------------------------------------------------

fn detect_action(text: &str) -> Option<DeviceAction> {
    let has = |p: &str| contains_phrase(text, p);
    let off = has("off") || has("disable") || has("stop") || has("deactivate") || has("turn off");
    if has("timer") {
        return Some(DeviceAction::SetTimer);
    }
    if has("alarm") || has("wake me") {
        return Some(DeviceAction::SetAlarm);
    }
    if has("flashlight") || has("torch") || has("flash light") {
        return Some(if off {
            DeviceAction::FlashlightOff
        } else {
            DeviceAction::FlashlightOn
        });
    }
    if has("dnd") || has("do not disturb") || has("don't disturb") || has("dont disturb") {
        return Some(if off {
            DeviceAction::DndOff
        } else {
            DeviceAction::DndOn
        });
    }
    if has("unmute")
        || has("ring mode")
        || has("ringer on")
        || has("normal mode")
        || has("general mode")
        || has("sound on")
        || has("turn on sound")
        || has("loud mode")
    {
        return Some(DeviceAction::Ring);
    }
    let phone = has("phone") || has("mobile") || has("ringer") || has("mode") || has("sound");
    if has("vibrate") || has("vibration") {
        return Some(DeviceAction::Vibrate);
    }
    if has("sound off") || has("turn off sound") || has("turn off the sound") {
        return Some(DeviceAction::Silent);
    }
    if has("silent") || has("silence") || has("mute") || (phone && has("quiet")) {
        let leave_silent = has("silent off")
            || has("turn off silent")
            || has("disable silent")
            || has("stop silent")
            || has("remove silent");
        return Some(if leave_silent {
            DeviceAction::Ring
        } else {
            DeviceAction::Silent
        });
    }
    None
}

fn detect_topic(text: &str) -> QueryTopic {
    let has = |words: &[&str]| words.iter().any(|w| contains_phrase(text, w));
    if has(&[
        "how am i doing",
        "summary",
        "summarize",
        "summarise",
        "analysis",
        "analyse",
        "analyze",
        "overview",
        "report",
        "how was my day",
        "my day",
        "brief",
    ]) {
        QueryTopic::Summary
    } else if has(&["sleep", "slept", "sleeping", "rest", "awake"]) {
        QueryTopic::Sleep
    } else if has(&["streak"]) {
        QueryTopic::Streak
    } else if has(&["score", "points", "xp", "state"]) {
        QueryTopic::Score
    } else if has(&["steps", "walk", "walked", "walking", "distance", "km"]) {
        QueryTopic::Steps
    } else if has(&[
        "class",
        "classes",
        "lecture",
        "lectures",
        "timetable",
        "college",
        "leave",
        "school",
    ]) {
        QueryTopic::Classes
    } else if has(&[
        "study", "studying", "syllabus", "topic", "topics", "exam", "exams", "subject", "revise",
        "revision",
    ]) {
        QueryTopic::Study
    } else if has(&[
        "remind",
        "reminders",
        "task",
        "tasks",
        "todo",
        "to-do",
        "plans",
        "deadline",
        "deadlines",
        "schedule",
        "pending",
    ]) {
        QueryTopic::Reminders
    } else if has(&[
        "competition",
        "competitions",
        "compete",
        "contest",
        "hackathon",
        "team",
    ]) {
        QueryTopic::Competitions
    } else if has(&["what time", "time is it", "time now"]) {
        QueryTopic::Time
    } else if has(&[
        "what day",
        "date today",
        "today's date",
        "which day",
        "what date",
    ]) {
        QueryTopic::Date
    } else {
        QueryTopic::Other
    }
}

fn extract_title(tokens: &[Token]) -> String {
    let mut words: Vec<&Token> = tokens.iter().filter(|t| !t.used).collect();
    // Leading filler, repeatedly: "please remind me to …".
    'outer: loop {
        for prefix in FILLER_PREFIXES {
            let parts: Vec<&str> = prefix.split(' ').collect();
            if words.len() > parts.len()
                && parts.iter().enumerate().all(|(k, p)| words[k].lower == *p)
            {
                words.drain(..parts.len());
                continue 'outer;
            }
        }
        break;
    }
    while words
        .last()
        .is_some_and(|t| DANGLING.contains(&t.lower.as_str()))
    {
        words.pop();
    }
    while words
        .first()
        .is_some_and(|t| matches!(t.lower.as_str(), "and" | "to" | "at" | "on" | "by"))
    {
        words.remove(0);
    }
    let text = words
        .iter()
        .map(|t| t.original.as_str())
        .collect::<Vec<_>>()
        .join(" ");
    let mut chars = text.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().chain(chars).collect(),
        None => String::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const IST: i32 = 330;

    /// 2026-09-26 (a Saturday) at `hh:mm` IST.
    fn at(hh: u32, mm: u32) -> i64 {
        at_minute(days_from_civil(2026, 9, 26), hh * 60 + mm, IST)
    }

    fn local(y: i32, m: u32, d: u32, hh: u32, mm: u32) -> i64 {
        at_minute(days_from_civil(y, m, d), hh * 60 + mm, IST)
    }

    fn parse_at(text: &str, now: i64) -> ParsedCommand {
        parse_command(&CommandInput {
            text: text.to_owned(),
            now_ms: now,
            utc_offset_minutes: IST,
            default_minute: 9 * 60,
            default_delay_minutes: 60,
        })
    }

    fn parse(text: &str) -> ParsedCommand {
        parse_at(text, at(14, 0))
    }

    #[test]
    fn misspelled_tomorrow_reminder() {
        let c = parse("remind me texting ria tommoro");
        assert_eq!(c.kind, CommandKind::Reminder);
        assert_eq!(c.title, "Texting ria");
        assert_eq!(c.at_ms, Some(local(2026, 9, 27, 9, 0)));
        assert!(c.date_explicit);
        assert!(!c.time_explicit);
        assert_eq!(c.repeat, RepeatRule::None);
    }

    #[test]
    fn assignment_deadline_nudges_daily() {
        let c = parse("i have to submit my assignment at 3 oct");
        assert_eq!(c.kind, CommandKind::Deadline);
        assert_eq!(c.title, "Submit my assignment");
        assert_eq!(c.repeat, RepeatRule::DailyUntilDue);
        assert_eq!(c.at_ms, Some(local(2026, 10, 3, 23, 59)));
    }

    #[test]
    fn remind_without_time_uses_default_delay() {
        let c = parse("remind me to msg ray");
        assert_eq!(c.kind, CommandKind::Reminder);
        assert_eq!(c.title, "Msg ray");
        assert_eq!(c.at_ms, Some(at(15, 0)));
    }

    #[test]
    fn wake_word_is_ignored() {
        let c = parse("Hey Holstrom, remind me to call mom at 6pm");
        assert_eq!(c.title, "Call mom");
        assert_eq!(c.at_ms, Some(at(18, 0)));
        assert!(c.time_explicit);
    }

    #[test]
    fn relative_offsets() {
        assert_eq!(
            parse("remind me in 10 minutes to stretch").at_ms,
            Some(at(14, 10))
        );
        assert_eq!(parse("remind me to stretch in 10 minutes").title, "Stretch");
        assert_eq!(parse("drink water after 1hr").at_ms, Some(at(15, 0)));
        assert_eq!(
            parse("remind me in half an hour to check oven").at_ms,
            Some(at(14, 30))
        );
        assert_eq!(
            parse("remind me 2 hours later to eat").at_ms,
            Some(at(16, 0))
        );
        assert_eq!(parse("remind me in an hour to eat").at_ms, Some(at(15, 0)));
    }

    #[test]
    fn clock_times() {
        assert_eq!(parse("call dad at 5:30 pm").at_ms, Some(at(17, 30)));
        assert_eq!(parse("call dad at 17:30").at_ms, Some(at(17, 30)));
        // 9 am already passed today → tomorrow.
        assert_eq!(
            parse("remind me to run at 9am").at_ms,
            Some(local(2026, 9, 27, 9, 0))
        );
        // A bare "at 5" at 14:00 means 17:00 today.
        assert_eq!(parse("remind me to leave at 5").at_ms, Some(at(17, 0)));
        assert_eq!(
            parse("remind me to read at 7 in the evening").at_ms,
            Some(at(19, 0))
        );
        assert_eq!(
            parse("remind me to read tomorrow at 7").at_ms,
            Some(local(2026, 9, 27, 19, 0))
        );
        assert_eq!(
            parse("remind me to read tomorrow at 10").at_ms,
            Some(local(2026, 9, 27, 10, 0))
        );
        assert_eq!(
            parse("remind me at noon tomorrow to eat").at_ms,
            Some(local(2026, 9, 27, 12, 0))
        );
        assert_eq!(
            parse("remind me to call ria tonight").at_ms,
            Some(at(21, 0))
        );
        assert_eq!(
            parse("remind me tomorrow morning to pay rent").at_ms,
            Some(local(2026, 9, 27, 9, 0))
        );
        assert_eq!(
            parse("remind me tomorrow evening to pay rent").title,
            "Pay rent"
        );
    }

    #[test]
    fn dates_in_many_forms() {
        let oct3 = Some(local(2026, 10, 3, 9, 0));
        assert_eq!(parse("remind me on 3 oct to renew pass").at_ms, oct3);
        assert_eq!(parse("remind me on oct 3rd to renew pass").at_ms, oct3);
        assert_eq!(
            parse("remind me on 3rd of october to renew pass").at_ms,
            oct3
        );
        assert_eq!(parse("remind me on 3/10 to renew pass").at_ms, oct3);
        assert_eq!(parse("remind me on the 3rd to renew pass").at_ms, oct3);
        assert_eq!(
            parse("remind me on 3 oct to renew pass").title,
            "Renew pass"
        );
        // A date already past this year is next year.
        assert_eq!(
            parse("remind me on 1 jan to renew pass").at_ms,
            Some(local(2027, 1, 1, 9, 0))
        );
        assert_eq!(
            parse("remind me on 5 feb 2028 to renew").at_ms,
            Some(local(2028, 2, 5, 9, 0))
        );
        // The 30th of February does not exist.
        assert!(!parse("remind me on 30 feb to x").date_explicit);
    }

    #[test]
    fn weekdays() {
        // Today is Saturday 26 Sep.
        assert_eq!(
            parse("remind me on monday to call bank").at_ms,
            Some(local(2026, 9, 28, 9, 0))
        );
        assert_eq!(
            parse("remind me next friday at 4pm to call bank").at_ms,
            Some(local(2026, 10, 2, 16, 0))
        );
        assert_eq!(
            parse("remind me on saturday to wash").at_ms,
            Some(local(2026, 10, 3, 9, 0))
        );
        assert_eq!(
            parse("remind me on sat to wash").at_ms,
            Some(local(2026, 10, 3, 9, 0))
        );
        // "sun" without a preposition is just a word.
        assert_eq!(
            parse("remind me to sit in the sun tomorrow").title,
            "Sit in the sun"
        );
        assert_eq!(
            parse("remind me day after tomorrow to x").at_ms,
            Some(local(2026, 9, 28, 9, 0))
        );
    }

    #[test]
    fn deadlines() {
        let c = parse("project due on friday at 5pm");
        assert_eq!(c.kind, CommandKind::Deadline);
        assert_eq!(c.title, "Project due");
        assert_eq!(c.at_ms, Some(local(2026, 10, 2, 17, 0)));

        let c = parse("finish the report by 30 sep");
        assert_eq!(c.kind, CommandKind::Deadline);
        assert_eq!(c.title, "Finish the report");

        let c = parse("remind me every day till 3 oct to study for the exam");
        assert_eq!(c.kind, CommandKind::Deadline);
        assert_eq!(c.title, "Study for the exam");
        assert_eq!(c.at_ms, Some(local(2026, 10, 3, 23, 59)));

        // A single "remind me" on a later day stays a one-off reminder.
        let c = parse("remind me to submit the form on monday");
        assert_eq!(c.kind, CommandKind::Reminder);

        // Due today is just a reminder.
        let c = parse("i have to submit my assignment today at 5pm");
        assert_eq!(c.kind, CommandKind::Reminder);
        assert_eq!(c.at_ms, Some(at(17, 0)));
    }

    #[test]
    fn repeating_reminders() {
        let c = parse("remind me every day at 8am to drink water");
        assert_eq!(c.kind, CommandKind::Reminder);
        assert_eq!(c.repeat, RepeatRule::Daily);
        assert_eq!(c.title, "Drink water");
        assert_eq!(c.at_ms, Some(local(2026, 9, 27, 8, 0)));

        let c = parse("remind me every monday at 7pm to call grandma");
        assert_eq!(c.repeat, RepeatRule::Weekly);
        assert_eq!(c.at_ms, Some(local(2026, 9, 28, 19, 0)));

        let c = parse("take vitamins daily at 10pm");
        assert_eq!(c.repeat, RepeatRule::Daily);
        assert_eq!(c.at_ms, Some(at(22, 0)));
    }

    #[test]
    fn device_actions() {
        let c = parse("silent my phone after 1hr");
        assert_eq!(c.kind, CommandKind::DeviceAction);
        assert_eq!(c.action, Some(DeviceAction::Silent));
        assert_eq!(c.at_ms, Some(at(15, 0)));
        assert_eq!(c.revert_at_ms, None);

        let c = parse("put my phone on silent for 2 hours");
        assert_eq!(c.at_ms, Some(at(14, 0)));
        assert_eq!(c.revert_at_ms, Some(at(16, 0)));

        let c = parse("vibrate mode until 6pm");
        assert_eq!(c.action, Some(DeviceAction::Vibrate));
        assert_eq!(c.at_ms, Some(at(14, 0)));
        assert_eq!(c.revert_at_ms, Some(at(18, 0)));

        let c = parse("silent my phone at 10pm until 7am");
        assert_eq!(c.at_ms, Some(at(22, 0)));
        assert_eq!(c.revert_at_ms, Some(local(2026, 9, 27, 7, 0)));

        assert_eq!(
            parse("turn on do not disturb").action,
            Some(DeviceAction::DndOn)
        );
        assert_eq!(parse("turn off dnd").action, Some(DeviceAction::DndOff));
        assert_eq!(parse("unmute my phone").action, Some(DeviceAction::Ring));
        assert_eq!(
            parse("turn on the flashlight").action,
            Some(DeviceAction::FlashlightOn)
        );
        assert_eq!(parse("torch off").action, Some(DeviceAction::FlashlightOff));

        let c = parse("set an alarm for 6:30 am");
        assert_eq!(c.action, Some(DeviceAction::SetAlarm));
        assert_eq!(c.at_ms, Some(local(2026, 9, 27, 6, 30)));
        assert_eq!(
            parse("wake me up at 7").at_ms,
            Some(local(2026, 9, 27, 7, 0))
        );

        let c = parse("set a timer for 10 minutes");
        assert_eq!(c.action, Some(DeviceAction::SetTimer));
        assert_eq!(c.duration_minutes, Some(10));
        assert_eq!(parse("timer 5 min").duration_minutes, Some(5));

        // A reminder about silencing the phone is a reminder, not an action.
        assert_eq!(
            parse("remind me to put my phone on silent at 9pm").kind,
            CommandKind::Reminder
        );
    }

    #[test]
    fn questions() {
        let q = |s: &str| {
            let c = parse(s);
            assert_eq!(c.kind, CommandKind::Question, "{s}");
            assert_eq!(c.at_ms, None);
            c.topic.unwrap()
        };
        assert_eq!(q("how did i sleep?"), QueryTopic::Sleep);
        assert_eq!(q("what's my score today"), QueryTopic::Score);
        assert_eq!(q("how many steps have i walked"), QueryTopic::Steps);
        assert_eq!(q("what is my streak"), QueryTopic::Streak);
        assert_eq!(q("when is my next class"), QueryTopic::Classes);
        assert_eq!(q("what should i study today"), QueryTopic::Study);
        assert_eq!(q("what are my reminders"), QueryTopic::Reminders);
        assert_eq!(q("how am i doing"), QueryTopic::Summary);
        assert_eq!(q("give me an analysis of my week"), QueryTopic::Summary);
        assert_eq!(q("what time is it"), QueryTopic::Time);
        assert_eq!(q("tell me a joke"), QueryTopic::Other);
        assert_eq!(q("hello"), QueryTopic::Other);
        assert_eq!(q("I feel tired"), QueryTopic::Other);
    }

    #[test]
    fn never_schedules_in_the_past() {
        let c = parse_at("remind me today to x", at(23, 30));
        assert!(c.at_ms.unwrap() > at(23, 30));
        let c = parse_at("remind me in 0 minutes to x", at(10, 0));
        assert!(c.at_ms.unwrap() > at(10, 0));
    }

    fn schedule(at_ms: i64, repeat: RepeatRule, final_alert: bool) -> ReminderSchedule {
        ReminderSchedule {
            at_ms,
            repeat,
            nudge_minute: 9 * 60,
            final_alert,
        }
    }

    #[test]
    fn one_shot_fires_once() {
        let s = schedule(at(18, 0), RepeatRule::None, false);
        assert_eq!(
            next_fire(&s, at(14, 0), IST),
            Some(ReminderFire {
                at_ms: at(18, 0),
                days_left: 0,
                is_final: true
            })
        );
        assert_eq!(next_fire(&s, at(18, 0), IST), None);
    }

    #[test]
    fn daily_and_weekly_repeat_forever() {
        let s = schedule(at(8, 0), RepeatRule::Daily, false);
        assert_eq!(
            next_fire(&s, at(14, 0), IST).unwrap().at_ms,
            local(2026, 9, 27, 8, 0)
        );
        assert_eq!(next_fire(&s, at(7, 0), IST).unwrap().at_ms, at(8, 0));
        let w = schedule(at(8, 0), RepeatRule::Weekly, false);
        assert_eq!(
            next_fire(&w, at(8, 0), IST).unwrap().at_ms,
            local(2026, 10, 3, 8, 0)
        );
        assert_eq!(
            next_fire(&w, local(2026, 10, 5, 0, 0), IST).unwrap().at_ms,
            local(2026, 10, 10, 8, 0)
        );
    }

    #[test]
    fn deadline_without_time_nudges_through_due_day() {
        let due = local(2026, 10, 3, 23, 59);
        let s = schedule(due, RepeatRule::DailyUntilDue, false);
        let mut fires = Vec::new();
        let mut after = at(14, 0);
        while let Some(f) = next_fire(&s, after, IST) {
            after = f.at_ms;
            fires.push(f);
        }
        assert_eq!(fires.len(), 7, "27 Sep .. 3 Oct at 09:00");
        assert_eq!(
            fires[0],
            ReminderFire {
                at_ms: local(2026, 9, 27, 9, 0),
                days_left: 6,
                is_final: false
            }
        );
        assert_eq!(
            fires[6],
            ReminderFire {
                at_ms: local(2026, 10, 3, 9, 0),
                days_left: 0,
                is_final: true
            }
        );
    }

    #[test]
    fn deadline_with_time_ends_with_final_alert() {
        let due = local(2026, 9, 28, 8, 0);
        let s = schedule(due, RepeatRule::DailyUntilDue, true);
        let first = next_fire(&s, at(14, 0), IST).unwrap();
        assert_eq!(
            first,
            ReminderFire {
                at_ms: local(2026, 9, 27, 9, 0),
                days_left: 1,
                is_final: false
            }
        );
        // The 09:00 nudge on the due day would be after the 08:00 deadline.
        let last = next_fire(&s, first.at_ms, IST).unwrap();
        assert_eq!(
            last,
            ReminderFire {
                at_ms: due,
                days_left: 0,
                is_final: true
            }
        );
        assert_eq!(next_fire(&s, due, IST), None);
    }
}
