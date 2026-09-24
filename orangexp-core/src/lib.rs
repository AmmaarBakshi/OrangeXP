//! # OrangeXP core
//!
//! The deterministic computational engine behind OrangeXP. It receives
//! structured measurements and returns structured, explainable results:
//!
//! | Module          | Responsibility                                          |
//! |-----------------|---------------------------------------------------------|
//! | [`scoring`]     | configurable rule curves → points                       |
//! | [`state`]       | Green / Orange / Red / Black classification             |
//! | [`daily`]       | one day in, one explained score out                     |
//! | [`sleep`]       | sleep estimation from device events                     |
//! | [`streak`]      | consecutive qualifying days (never affects points)      |
//! | [`heatmap`]     | contribution-graph intensity levels and layout          |
//! | [`statistics`]  | lifetime counters, averages, consistency, trends        |
//! | [`timetable`]   | class conflicts and free study capacity                 |
//! | [`travel`]      | leave-home / start-preparing times                      |
//! | [`syllabus`]    | deadline-aware study scheduling                         |
//! | [`movement`]    | walking vs. vehicle from GPS, step cadence and hints    |
//! | [`competitions`]| competition capacity planning and teammate ranking      |
//! | [`adherence`]   | planned vs. actual work                                 |
//!
//! The crate knows nothing about Android. It never reads clocks, time zones,
//! files or the network; identical inputs always produce identical outputs.
//! The optional `ffi` feature exposes the engine through UniFFI.

pub mod adherence;
pub mod category;
pub mod competitions;
pub mod config;
pub mod daily;
pub mod error;
pub mod heatmap;
pub mod history;
mod interval;
pub mod metrics;
pub mod movement;
pub mod normalization;
pub mod scoring;
pub mod sleep;
pub mod state;
pub mod statistics;
pub mod streak;
pub mod syllabus;
pub mod time;
pub mod timetable;
pub mod travel;

#[cfg(feature = "ffi")]
mod ffi;

#[cfg(feature = "ffi")]
uniffi::setup_scaffolding!();

pub const ENGINE_VERSION: &str = env!("CARGO_PKG_VERSION");

pub use config::EngineConfig;
pub use daily::{evaluate_day, evaluate_history, DayEvaluation, DayInput};
pub use error::EngineError;
