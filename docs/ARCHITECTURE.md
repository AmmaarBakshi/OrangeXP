# Architecture

## Principles

1. **Kotlin owns Android, Rust owns computation.** Anything touching the OS (UI, sensors, Room,
   WorkManager, permissions, notifications) is Kotlin. Anything algorithmic (scoring, state,
   sleep inference, scheduling, statistics) is Rust.
2. **Deterministic core.** The engine never reads the clock, the time zone database, files or the
   network. Callers pass timestamps and UTC offsets explicitly. Identical input gives identical output.
3. **Coarse FFI boundary.** One structured input goes in and one structured result comes out. A
   screen never makes chains of small calls across the boundary.
4. **Explainable results.** The engine returns *why* as well as *what*: every rule contribution,
   every state finding, the decisive rule, and whether a cap applied.
5. **No AI at runtime.** No LLM or cloud dependency. The app works fully offline.

## Repository layout

```
orangexp-core/            Rust engine (Cargo workspace)
  src/                    one module per concern, each with unit tests
  uniffi-bindgen/         binding generator pinned to the runtime's UniFFI version
  uniffi.toml             Kotlin package for generated bindings
build-logic/convention/   Gradle convention plugins
app/                      Application, MainActivity, navigation shell
core/
  common/                 TimeSource, local-day math, coroutine dispatchers
  engine/                 OrangeEngine facade over generated bindings, MetricKeys
  database/               Room entities, DAOs, exported schemas
  sensing/                UsageStats events, step counter, power state, permission checks
  data/                   repositories, measurement assembly, TrackingCoordinator
  work/                   periodic tracking worker, departure reminders, notification channel
  designsystem/           orange theme, typography, counters, heatmap, cards, formatters
  ui/                     shared domain→UI mappings (state tones, labels, breakdown list)
feature/
  today/ history/ academics/ competitions/ settings/
  widgets/                Glance home-screen widgets, refreshed through DataChangeListener
```

Dependency direction: `app → feature:* → core:ui → core:data → core:{database, sensing, engine, common}`.
Features never depend on each other. `core:designsystem` knows nothing about the domain.

## The engine (`orangexp-core`)

| Module | Responsibility |
|---|---|
| `time` | epoch-day calendar math (no chrono dependency), weekdays, fixed-offset helpers |
| `metrics` | metric catalog: stable string keys, units, aggregation (sum/max/last) |
| `normalization` | sanitises raw measurements, aggregates duplicates, derives distance and attendance % |
| `scoring` | `ScoreCurve` (linear, steps, piecewise) + floor/cap → `RuleContribution` |
| `state` | threshold rules → Green/Orange/Red/Black, most severe wins |
| `daily` | `evaluate_day` and `evaluate_history` (streak + heatmap + statistics in one call) |
| `sleep` | active intervals → episodes → sleep candidates, interruptions, onset modes, confidence |
| `streak` | current/longest runs; today in progress never breaks a streak |
| `heatmap` | 0–5 intensity levels (target-relative or quantile) and week-column layout |
| `statistics` | lifetime and per-pool totals, averages, median, std-dev, consistency, OLS trend |
| `timetable` | conflict detection, free study capacity per day (windows − classes − travel) |
| `travel` | leave/prepare times with safety buffer and percentile of real travel history |
| `syllabus` | slack-aware greedy scheduler with deterministic tie-breaks, forecasts, shortfalls |
| `adherence` | planned vs. actual work, credit capped per plan item |
| `movement` | walking vs. vehicle from GPS fixes, step cadence and activity hints; spike removal, median speed, segment smoothing |
| `competitions` | competition capacity (exact interval-demand feasibility + concurrency limit, exhaustive up to 16 candidates) and teammate ranking |
| `config` | complete user configuration, defaults, validation, JSON with forward compatibility |
| `ffi` | UniFFI exports (feature `ffi` only) |

### Sleep estimation

1. An **active interval** starts at an unlock or app interaction and ends at the next screen-off.
   A screen lit by a notification is not activity.
2. Active intervals separated by less than the inactivity threshold (45 min by default) form an
   **episode** of phone use.
3. The gap between episodes is a **sleep candidate**. Sleep starts once the threshold has elapsed
   (or at the last activity, if configured) and ends at the first unlock of the next episode.
4. An unlock-only episode (no app opened) no longer than the tolerance, followed by more
   inactivity, is an **interruption**, not a wake-up.
5. Only rest of at least `awake_reset_minutes` resets the continuous-awake clock.
6. **Confidence** (0–100) is a transparent sum: typical duration, overlap with the usual sleep
   window, interruptions, and charging.

### Syllabus scheduling

Days are processed in order. Capacity is handed out in chunks to the subject with the highest

```
pressure = remaining_minutes / capacity_left_until_target_day × priority_weight
```

Ties go to the earlier deadline, then higher priority, then subject id. This interleaves subjects,
front-loads tight deadlines and reports shortfalls when work cannot fit. Re-running with today's
date and current progress re-plans after missed days; no special recovery logic is needed.

## Kotlin ↔ Rust bridge

- The `orangexp.rust.android` Gradle plugin runs `cargo ndk` for the configured ABIs
  (`orangexp.rust.abis`) and UniFFI's library mode on the result. Both outputs are registered as
  generated sources of every variant.
- Generated data classes (`com.orangexp.core.engine.ffi.*`) *are* the domain model. A field added
  in Rust appears in Kotlin on the next build.
- `OrangeEngine` is the only entry point. It is an interface, so ViewModels and repositories can be
  tested with fakes.
- `orangexp.rust.jvmtests` builds the crate for the host and points JNA at it, so JVM unit tests
  (`core:engine`, `core:data`) exercise the **real** engine without a device.

## Data flow

```
WorkManager (15 min) / app resume
  → TrackingCoordinator.sync()
      ingest UsageStats events since cursor → device_events
      read step counter once → daily_steps
      regenerate study plan when the day changed
      DayRepository.evaluate(yesterday once, today)
          assemble measurements from Room
          engine.analyzeSleepDay + engine.adherence
          engine.evaluateDay → DaySnapshot (record, contributions, metrics, findings)
  → Room flows → ViewModels → Compose
```

## Time and dates

- The engine works on **epoch days** and epoch milliseconds.
- Kotlin computes local-day windows with `java.time` (DST-aware, 23/25-hour days) in `core:common`.
- Sleep is attributed to the day on which it **ends** (wake-day attribution).

## Adding things

**A new measurable activity**
1. Add a key and catalog entry in `orangexp-core/src/metrics.rs` (and a default rule in `config.rs` if it scores).
2. Mirror the key in `core/engine/.../MetricKeys.kt`. `UniffiOrangeEngineTest` fails if the two drift apart.
3. Produce the measurement in `DayRepository.evaluate`.

**A new screen**
1. Create `feature/<name>` with `plugins { alias(libs.plugins.orangexp.android.feature) }`.
2. Expose a `@Serializable` destination and a `NavGraphBuilder.<name>Screen()` extension.
3. Register it in `app/.../OrangeXpApp.kt`.

**A new algorithm**
Write it in Rust with tests, expose one coarse function in `ffi.rs`, then add it to `OrangeEngine`.

**Database changes**
Bump the Room version, add a migration, and commit the exported schema in `core/database/schemas`.
