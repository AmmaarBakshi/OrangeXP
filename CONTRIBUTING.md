# Contributing to OrangeXP

Thanks for helping. OrangeXP is a measurement instrument, so correctness and determinism come first.

## Ground rules

- **Algorithms live in Rust** (`orangexp-core`) and ship with unit tests. Android code never
  re-implements scoring, state or scheduling logic.
- **Android integration lives in Kotlin.** Rust never knows Android exists.
- **No AI in measurement.** Scores, states, schedules and reminders never depend on a model. If a
  rule, statistic or algorithm can do it, use that. Holstrom's optional on-device language model
  only holds conversations: it reads the briefing and never writes data.
- **No score inflation.** No multipliers, streak bonuses or hidden adjustments. Every point must be
  explainable through a rule contribution.
- **Battery matters.** No polling loops or long-running services. Prefer system history,
  WorkManager and one-shot sensor reads.
- **Privacy by default.** Nothing leaves the device.

## Setup

See the *Building* section of the [README](README.md). Useful commands:

```sh
cd orangexp-core
cargo fmt --all
cargo clippy --all-targets --all-features -- -D warnings
cargo test

cd ..
./gradlew testDebugUnitTest -Porangexp.rust.abis=arm64-v8a
./gradlew assembleDebug -Porangexp.rust.abis=x86_64   # for an x86_64 emulator
```

## Pull requests

- Keep changes focused; one concern per PR.
- Add or update tests: Rust tests for engine behaviour, Kotlin tests for repositories, ViewModels
  and the bridge.
- If you change the engine's public types, the Kotlin bindings regenerate automatically. Update
  `OrangeEngine` and its callers in the same PR.
- Room schema changes need a version bump, a migration and the exported schema file.
- User-facing text goes in `strings.xml`.
- CI must pass: `rustfmt`, `clippy -D warnings`, `cargo test`, Gradle unit tests and a debug build.

## Code style

- Rust: `rustfmt` defaults, `clippy` clean, doc comments on public items that explain *why*.
- Kotlin: official Kotlin style (see `.editorconfig`), trailing commas, 120-column lines.
- Comments explain decisions and constraints, not what the code already says.

## Reporting bugs

Include the device, Android version, what you expected and what happened. For scoring or sleep
questions, export your configuration (Settings → Configuration) and describe the day.
Never attach personal usage data you are not comfortable sharing.
