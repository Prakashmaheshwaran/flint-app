# Shortcuts / Siri — App Intents (`I-SHORTCUTS`)

Flint's Siri & Shortcuts surface. Three [App Intents](https://developer.apple.com/documentation/appintents),
auto-discovered by iOS from the app binary — **no separate extension target** (ADR-005/006 fix the
target count at 5; App Intents ship inside the host app).

| File | What it is |
|---|---|
| `StartSessionIntent.swift` | "Start a Flint session" — timed Block Now over a saved selection or app-group. |
| `StartPomodoroIntent.swift` | "Start a Flint pomodoro" — first focus round of a focus/break cycle. |
| `StopSessionIntent.swift` | "Stop my Flint session" — ends the block (Hardcore can't be ended early). |
| `FlintShortcuts.swift` | `AppShortcutsProvider` — Siri phrases + Shortcuts-app seeding. |
| `FlintShortcutsRunner.swift` | The seam to `FlintCore`: resolve a selection → drive `FlintSessionController`. |
| `FlintAppGroupEntity.swift` | Surfaces saved app-groups (presets) as a Shortcuts entity. |
| `SessionBreakLevel.swift` | `AppEnum` bridge for `BreakLevel` (keeps `AppIntents` out of `FlintCore`). |

## How it wires up (and what it deliberately doesn't)

- The intents build on the model shapes the scaffold left in `FlintCore` — `FlintSessionRequest`
  and `FlintPomodoroConfig`. They hold **no** opaque FamilyControls tokens themselves: a session
  refers to a saved selection by `appGroupID` (or nil = the current/last selection), and the runner
  resolves it from the shared App Group at run time.
- These intents are **auto-discovered** — they don't wire themselves into app navigation. That's the
  fleet contract (Wave-2 integrator owns the app shell), and it's also just how App Intents work.
- `openAppWhenRun = false` so Siri can start/stop a block hands-free without bringing the app
  forward.

## Honest limits (match the repo's standard — say what's verified)

- **Simulator can't enforce.** Screen Time shields apply only on a physical, Screen-Time-authorized
  device. On the Simulator `make ios-build` proves these **compile and the logic path runs**, but the
  block won't visibly take effect; `startBlockNow` still records the `FlintActiveSession`.
- **Pomodoro starts one round.** `StartPomodoroIntent` fires the **first focus round** as a timed
  block and persists the cadence. Automatic round chaining (break → next focus) is on-device
  app/monitor work and is intentionally out of scope for this background intent in v1 — a background
  App Intent isn't a reliable place to orchestrate a multi-stage timer.
- **No allow-list toggle yet.** `FlintSessionRequest.allowListMode` exists in the model, but
  `FlintSessionController.startBlockNow` doesn't enforce allow-list ("brick phone") mode yet, so the
  intents don't expose a switch that wouldn't do anything.
