# OrangeXP

**Measure reality → process it with deterministic algorithms → calculate a daily result → visualise progress.**

OrangeXP is a native Android app that works as a personal efficiency, discipline, scheduling and
progress-tracking system. It turns what your phone can measure (sleep estimated from phone
inactivity, awake time, steps, study sessions, attendance, departures) into **one daily score**
and a **four-level day state**. Every day becomes one square in an orange contribution graph.

There is no cloud and no AI in the measurements. Every number comes from a configurable, inspectable
rule, and the same inputs always produce the same result.

**Holstrom** (internal name UIH32) is the built-in assistant. Type or say *"remind me to text Ria
tomorrow"*, *"I have to submit my assignment on 3 Oct"* or *"silent my phone after 1 hour"* and it
schedules it; ask *"how am I doing?"* and it analyses your day. Commands are understood by
deterministic rules in the engine. For open conversation you can add a ~2B-parameter language model
that runs entirely on the phone. It reads your data but never changes a score.

```
TODAY                                   HISTORY
12,482  POINTS                          LIFETIME XP  3,642,817
● ORANGE   🔥 23 day streak              ▢▢▣▣▢▣▣▣▣▢▣▣▣▣▣▣▣▣▣▣  (one square per day)
SLEEP 6h 21m    AWAKE 14h 32m
WALKED 4.7 km   SYLLABUS 3 / 4
```

## Download

Get the latest APK from **[Releases](https://github.com/AmmaarBakshi/OrangeXP/releases/latest)**:

1. Download `OrangeXP-x.y.z.apk` on your phone (Android 9 or newer).
2. Open it and allow installing from your browser or file manager when Android asks.
3. New versions install over the old one and keep your data.

Each release also has a `.sha256` file to verify the download.

## Features

| Area | What it does |
|---|---|
| **Daily score** | One number per day, the plain sum of rule contributions. No multipliers, no streak bonuses, no inflation. |
| **Explainability** | Every point is traceable: rule, measured value, whether a cap applied. |
| **Day state** | Green / Orange / Red / Black from configurable thresholds (e.g. continuous awake time, short sleep). The day takes the most severe state. |
| **Sleep estimation** | From screen, unlock and app-interaction history. Notifications never count as waking up. Short unlock-only glances at night are interruptions. Every session carries a transparent confidence score and is labelled as an estimate. |
| **Awake tracking** | Continuous awake time, reset only by rest long enough to matter. A quiet hour or a nap does not reset it. |
| **Timetable** | Classes, locations, travel requirements, conflict detection. |
| **Leave-home planner** | "Start preparing" and "leave" times that aim to arrive *early*. Travel estimates come from your real trips once enough exist. |
| **Syllabus scheduler** | Subjects → units → topics with estimates, difficulty and exam dates. A deadline-aware, slack-based scheduler decides what to study on which day and re-plans automatically after missed days. |
| **Contribution heatmap** | GitHub-style yearly graph in orange, with day drill-down. |
| **Lifetime counters** | Lifetime, study, physical, sleep and discipline XP, all plain historical sums. |
| **Streaks** | Historical information only. Streaks never change points. |
| **Walking vs. vehicle** | Optional GPS mode: fixes are recorded only while Android reports movement and paired with step-counter readings. Step cadence proves walking; moving faster than *your* walking speed without steps is a vehicle; median speeds and spike removal ignore bad fixes; short segments merge into their neighbours, so a traffic stop doesn't split a bus ride. Steps registered in vehicles are removed. |
| **Competitions** | Track upcoming competitions with prep hours, dates, importance and team. An exact capacity check (interval demand vs. available hours) plus a concurrency limit tells you how many you can take on at once and which ones fit. |
| **Teammates** | Every result earns your teammates points (win 100, podium 60, finalist 30, participated 10), halving each year, so the ranking favours people you've recently done well with. |
| **Widgets** | Score, streak calendar, timetable and statistics widgets for the home screen. |
| **Holstrom: plans** | Write or say what you need to do. *"remind me texting ria tommoro"* → a reminder tomorrow at 09:00; *"i have to submit my assignment at 3 oct"* → a deadline that nudges you every day until 3 Oct. Understands times, dates, weekdays, "in 2 hours", "every Monday", "till/by", and common misspellings. Done/Snooze right from the notification; ticked-off reminders count toward the day's *Tasks* points. |
| **Holstrom: phone actions** | *"silent my phone after 1hr"*, *"vibrate until 6pm"*, *"do not disturb for 2 hours"*, *"flashlight on"*, *"wake me up at 6:30"*, *"timer for 10 minutes"*. Scheduled actions run while the phone sleeps and undo themselves when you give a duration. |
| **Holstrom: analysis and conversation** | Answers about score, sleep, steps, streak, classes, study plan, exams, competitions and reminders straight from your data. With an on-device model (e.g. Gemma 3n E2B) it also holds a conversation and gives a spoken analysis of your day, fully offline. |
| **Holstrom: voice everywhere** | Tap-or-hold mic in the app, a home-screen mic widget, a Quick Settings tile and a launcher shortcut. The voice sheet opens over the lock screen, speech recognition prefers the phone's on-device recognizer, and replies are spoken aloud. |

## Architecture in one picture

```
┌──────────────────────────── Kotlin (Android) ─────────────────────────────┐
│  feature:today  feature:history  feature:academics  feature:settings      │
│  feature:holstrom (assistant, voice overlay, tile, mic widget)             │
│        │                 Compose UI + ViewModels                          │
│  core:ui ── core:designsystem (orange theme, heatmap, counters)           │
│        │                                                                  │
│  core:data   repositories · measurement assembly · tracking coordinator   │
│   │      │                                                                │
│  core:database (Room)   core:sensing (UsageStats, step counter)           │
│  core:work (WorkManager pass every 15 min, departure + Holstrom alarms)   │
│  core:voice (speech in/out)   core:llm (optional on-device model)         │
│        │                                                                  │
│  core:engine  OrangeEngine facade ── UniFFI-generated bindings            │
└────────┼──────────────────────────────────────────────────────────────────┘
         │ FFI: one structured input → one structured result
┌────────▼──────────────── Rust: orangexp-core ─────────────────────────────┐
│ scoring · state · sleep · streak · heatmap · statistics · timetable ·     │
│ travel · syllabus · adherence · commands · normalization · config         │
│ Pure, deterministic, platform-independent. Never reads clocks or files.   │
└───────────────────────────────────────────────────────────────────────────┘
```

**Kotlin owns Android. Rust owns the computational core.** See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Building

Requirements:

- JDK 17 or newer (Android Studio's bundled JBR works)
- Android SDK with platform 36 and an NDK (any recent version; the newest installed one is used)
- Rust (stable) with Android targets and [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk):

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
```

Then:

```sh
./gradlew assembleDebug                 # builds Rust for all ABIs, generates bindings, builds the APK
./gradlew assembleDebug -Porangexp.rust.abis=arm64-v8a   # faster: a single ABI
./gradlew testDebugUnitTest             # Kotlin tests (some call the real Rust engine on the JVM)
cd orangexp-core && cargo test          # engine tests
```

The Gradle build compiles the Rust crate with `cargo ndk` and generates Kotlin bindings with UniFFI
automatically. There are no manual steps and no generated code in the repository.

Release builds are minified and signed from `keystore.properties` (not committed) or the
`ORANGEXP_KEYSTORE*` environment variables.

### Publishing a release

1. Once, add the repository secrets `ORANGEXP_KEYSTORE_BASE64`, `ORANGEXP_KEYSTORE_PASSWORD`,
   `ORANGEXP_KEY_ALIAS` and `ORANGEXP_KEY_PASSWORD` (Settings → Secrets and variables → Actions).
2. Tag and push: `git tag v0.2.0 && git push origin v0.2.0`.
3. The *Release* workflow builds the signed APK and publishes it with release notes.

Always sign with the same key; Android refuses updates signed with a different one.

## Permissions and privacy

| Permission | Why |
|---|---|
| Usage access | Screen, unlock and foreground-app *history*, used for sleep and awake estimation. Read from the system's own record; no background service. |
| Activity recognition | Reads the hardware step counter. |
| Notifications | Preparation and departure reminders, Holstrom reminders. |
| Microphone (optional) | Talking to Holstrom. Audio goes to the phone's speech recognizer, the on-device one when available. |
| Alarms & reminders | Exact alarms so Holstrom reminders arrive on time while the phone sleeps; restored after a reboot. |
| Do Not Disturb access (optional, system setting) | Only if you ask Holstrom to silence the phone or turn Do Not Disturb on or off. |
| Set alarm | Hands *"wake me up at 6:30"* to your clock app. |
| Location, including "Allow all the time" (optional) | Only if you turn on walking detection: GPS while you're moving, so walking can be told apart from riding. Stays on the device. |

All data stays on the device. The app does not request the `INTERNET` permission, has no account and no analytics.
Holstrom's language model is a file you import yourself; it runs on the phone and cannot send anything anywhere.

Keep your own data out of git: anything in `personal/` (for example exported syllabus or timetable files, database backups, signing keys) and any `*.db` file is ignored.
Cloud backup is disabled for personal data; direct device-to-device transfer is allowed.

## Battery

OrangeXP runs no long-lived service. A WorkManager job every 15 minutes reads what Android already
recorded, reads the step counter once, lets the engine evaluate the day, and exits. Departure
reminders use inexact alarms; Holstrom reminders use one exact alarm each, only at the moments you
asked for. The engine is a small native library doing pure computation. Holstrom's language model is
loaded only when a question needs it and released after three idle minutes.

## Holstrom

Open the **Holstrom** tab (the waveform icon).

- **Talk**: type, tap the mic (stops after a pause) or hold it (release to finish).
- **Plans**: write what you have to do; a live preview shows how Holstrom read it before you add it.
- **Setup**: model, voice, reminder times, permissions, widget.

**Everywhere else.** Add the *Holstrom mic* widget (Setup → Add), the *Holstrom* Quick Settings tile
(works from the lock screen), or long-press the app icon → *Talk to Holstrom*. Home-screen widgets on
Android only receive taps, so the widget starts listening on tap and stops by itself when you pause.
Always-on wake words are not possible for a regular app without a constantly running microphone, so
Holstrom listens only when you ask it to.

**Adding the language model (optional).** Download a MediaPipe `.task` model on any device, for
example `gemma-3n-E2B-it-int4.task` (Gemma 3n E2B, about 2B effective parameters, ~3 GB) or a
Gemma 2 2B IT `.task` from Hugging Face ([litert-community](https://huggingface.co/litert-community))
or Kaggle (you'll have to accept Google's Gemma licence). Copy it to the phone, then
Holstrom → Setup → *Import model file*. Alternatively push it with
`adb push model.task /sdcard/Android/data/com.orangexp.app/files/models/`. Phones with 6 GB of RAM
or more work best, and the runtime is included for 64-bit ARM phones.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). The short version: algorithms go in Rust with tests,
Android integration goes in Kotlin, and no score, state or schedule ever depends on an LLM.

## License

[Apache License 2.0](LICENSE)
