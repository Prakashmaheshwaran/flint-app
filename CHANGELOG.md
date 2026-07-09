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
- **The Home status card now tells the truth about blocking, and asks for one thing at a
  time:** a new `SetupGuidance` (feature-onboarding, pure Kotlin) turns blocking-resilience's
  `HealthStatus` into the card's headline, body, and single next step. It reports blocking as
  on whenever *either* path can enforce, names what is missing, and orders the remaining grants
  cheapest-route-to-blocking first — so when usage access is already granted, the overlay grant
  leads (one tap restores Path B) instead of the consent-gated accessibility flow. Home also
  re-checks every grant on resume, not just the accessibility one. The accessibility hand-off
  stays behind the prominent-disclosure consent screen (ADR-007): the app-side hand-off table
  has no direct route to Accessibility settings at all.

### Fixed
- **Android:** the Home status card no longer claims "Blocking is OFF" while Flint is actively
  blocking through the Path B fallback (usage access + overlay, accessibility service off). It
  read a single boolean — is the AccessibilityService on — so it both mis-reported protected
  users and pushed them at a permission they did not need. It now reads the full grant snapshot.
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
