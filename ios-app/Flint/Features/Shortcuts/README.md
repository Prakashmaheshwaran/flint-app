# Shortcuts / Siri — App Intents (`I-SHORTCUTS`)

Flint's Siri & Shortcuts surface. Three [App Intents](https://developer.apple.com/documentation/appintents),
auto-discovered by iOS from the app binary — **no separate extension target** (ADR-005/006 fix the
target count at 5; App Intents ship inside the host app).

| File | What it is |
|---|---|
| `StartSessionIntent.swift` | "Start a Flint session" — timed Block Now over a saved selection or app-group. |
| `StartPomodoroIntent.swift` | "Start Pomodoro Focus Round" — one timed focus round. |
| `StopSessionIntent.swift` | "Stop my Flint session" — asks to end the block under the same tier rules as the app (see below). |
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

- **Same rights as the app (ADR-012 §2/§3).** Stopping an Easy session ends it. Stopping a Harder
  session starts its persisted wait (1, then 5, then 15 minutes for repeated early stops) and the
  shortcut reports how long is left; open Flint after the wait for the final confirmation. A Hardcore
  session can't be stopped early from a shortcut — it ends on its own, or with the Emergency Pass in
  Flint. Starting a session while one runs replaces it only if that is allowed for the running
  session's tier: a replacement that keeps everything protected at least as long and as strictly is
  allowed; a weaker one is allowed for Easy, waits for Harder, and is refused for Hardcore. Harder
  and Hardcore sessions need a set number of minutes (0 = until stopped is Easy-only).
- **Simulator evidence is limited.** Builds and injected adapter tests prove compilation and
  transition logic. Physical-device verification is required for Screen Time enforcement.
- **Pomodoro starts one round (R-24 interim).** The intent exposes focus minutes only. It does not
  save a cadence or schedule breaks and later rounds. Legacy runner calls carrying break/round
  values still execute one focus round. The durable multi-round state machine remains separate work.
- **Start retries (R-25, PC-03/10).** A supplied Retry key binds the exact parameters to one accepted
  session and its terminal outcome. Keep that key for retries and use a new key for a new start.
  Without a key, identical Shortcut parameters share a durable attempt for 120 seconds while it
  runs; an intentional identical start inside that window replays it, and a retry outside that
  window can start another session. Terminal outcomes end this heuristic window immediately.
  The last 64 accepted starts are retained; a pruned key can be used again. Focus activation keeps
  its identity until Focus off is actually observed, including after its session ends.
- **No allow-list toggle yet.** `FlintSessionRequest.allowListMode` exists in the model, but
  `FlintSessionController.startBlockNow` doesn't enforce allow-list ("brick phone") mode yet, so the
  intents don't expose a switch that wouldn't do anything.
