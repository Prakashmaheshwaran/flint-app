# Changelog

All notable changes to Flint will be documented in this file.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning: [SemVer](https://semver.org/).

## [Unreleased]

### iOS
- **Open Limits are now user-reachable end-to-end (in code):** a config screen (Limits tab →
  *Open Limits*) to create/edit/toggle launch-count caps, host-app shield arming on launch and
  rule edits (`FlintOpenLimitsController`), a day-boundary re-arm via the monitor extension, and
  an open-aware block screen ("Use app (N left)" / opens-spent). Compile verification is pending
  on the macOS CI toolchain; shield enforcement itself remains device-gated, like all shields.
- **Hardcore uninstall guard:** while a Hardcore (Deep Focus) session is active, Flint sets
  `denyAppRemoval` on the session's `ManagedSettingsStore`, so deleting the app — the one-tap
  escape from a "non-bypassable" block — is refused by iOS (device-wide, per Apple's setting).
  Armed on every Hardcore start (Block Now, Shortcuts/Siri, Focus Filter) and re-asserted by the
  monitor + on launch; released on every end path (allowed stop, the monitor's interval end,
  Emergency Pass, launch reconciliation) because it shares the session shield's store. The
  session UI says so while it applies. Compile verification is pending on the macOS CI
  toolchain; enforcement is device-gated like all Screen Time settings.

### Android
- **Time-change guard:** changing the device clock, date, or timezone no longer resets what was
  already consumed. `TimeChangeReceiver` re-warms rules and runs the boot re-arm hooks; the pure
  `ClockChangeGuard` applies fail-closed semantics — day/week keys only roll forward, so a clock
  set back never re-grants consumed opens or a spent Emergency Pass. JVM-tested; the
  broadcast → re-arm path still awaits an emulator pass.
- **Block Now sessions:** one-tap timed focus sessions from Home (duration + difficulty chips);
  expiry is checked by the engine itself so a session can never outlive its timer, with a live
  countdown card and tier-gated early stop. Emulator-verified end-to-end (2026-07-06).
- **Hardcore uninstall guard (Path A):** while a Hardcore window is active, the system
  uninstaller and Settings pages about Flint are shielded (mention- and danger-gated,
  fail-closed on unreadable windows); the guard's shield offers no break/pass exit by design.
  JVM-tested; the emulator shield pass is pending.
- **Sleep Mode, preset routines & named groups:** bedtime→wake windows with Wind Down / free
  Full Assist and an optional Morning Assist window, the four-preset routine library, and
  DataStore-persisted named app groups. JVM-tested; emulator pass pending.
- **Premium UI overhaul:** one design direction ("warm charcoal, one ember") across all screens,
  driven by extended `design/tokens.json` roles through `FlintTheme` and a shared component kit;
  the ADR-007 consent disclosure text is byte-identical. Emulator-verified (2026-07-06).
- **Stats "Week in review":** typical completed day, busiest day, and a today-vs-typical line
  derived from `UsageReport` (JVM-tested).
- **Path B emulator verification (2026-07-08):** foreground-service lifecycle, blocklist
  overlay, self stand-down/re-shield, Easy break, and the daily Time Limit fallback verified
  live — evidence in `docs/verification/android-pathb-2026-07-08/`.
- **Open Limits Path B emulator verification (2026-07-08):** a 2-opens/day Easy limit authored
  through the real UI was verified end-to-end — first open allowed, at-quota `OPEN LIMIT`
  shield, HOME stand-down, over-quota re-shield, Easy break, and persisted counts after process
  death. The evidence records the current product nuance: the last allowed open is shielded
  ~1.8 s after launch.

### Fixed
- **iOS:** reloading Schedules or Time Limits with zero rules no longer cancels *all* of
  Flint's DeviceActivity monitoring at launch (it hit the stop-everything overload via an
  empty list, killing the active session's auto-clear). Empty-list `stopMonitoring` is now a
  no-op; deliberate stop-everything moved to `stopAllMonitoring()`.
- **iOS:** the monitor extension no longer treats unrecognized activity names (stale
  registrations) as the Block Now session — they could re-shield the saved selection or tear
  down a live session; they are now deregistered and ignored.
- **iOS:** the block screen's opens-spent copy no longer promises "It unlocks tomorrow" when
  open-limit state is unreadable (fail-closed) — it now says opens are unavailable.
- **Android:** day-restricted overnight windows now block the correct morning: the
  post-midnight tail is gated on the day the window started ("Mon 22:00–06:00, Mondays"
  blocks Tue 00:30, not Mon 00:30).
- **Android:** Limit-editor (DataStore) Time Limits now actually enforce — they previously had
  zero enforcement readers; both detection paths take the stricter of the legacy and
  Limit-editor stores, and Path A re-checks mid-session so the block lands when the budget
  crosses, not on the next app switch.
- **Android:** the Path B foreground-service notification re-posts on every service start, so a
  post-hoc POST_NOTIFICATIONS grant takes effect; the app shell asks for the permission once,
  and only when Path B is actually in play.
- **Android:** the HARDCORE block screen reflects a spent weekly Emergency Pass with an inert
  "used" notice instead of a button whose tap silently did nothing; the HARDER hold-to-request
  control exposes a TalkBack action; schedule day pills have 48dp touch targets; the block
  screen self-insets.
- **Docs:** the Open-Limits "never charges an open another layer would block" claim now
  carries its real exception — category-rule blocks are undetectable (opaque tokens), so
  those taps still charge an open.

## [0.1.0] — Initial public release

First public snapshot of both native apps.

### iOS (Swift / SwiftUI, Screen Time API)
- Block Now, unlimited schedules, Time Limits, break levels incl. free Hardcore,
  free weekly Emergency Pass, website blocking, embedded usage report,
  Sleep Mode + Morning Assist, Open Limits, Focus Filters, Siri/Shortcuts intents.
- Built and unit-tested against the Simulator. **Shield enforcement requires a physical
  device** (an Apple platform constraint — the Simulator cannot grant Family Controls).

### Android (Kotlin / Jetpack Compose, multi-module)
- Real blocking via AccessibilityService + overlay, schedule-gated blocking, Time Limits,
  screen-time stats, branded block screen, boot/resilience layer — verified on an emulator.
- Pure-Kotlin `BlockDecisionEngine` with JVM unit tests.
