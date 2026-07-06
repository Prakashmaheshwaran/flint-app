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
- **Notifications join the blocking-health rows (Android 13+):** `POST_NOTIFICATIONS` was
  declared but never requested, so the Path B backup-blocking service ran with its status
  notification invisible. Settings now shows a "Notifications" row on 13+ — disclosure first,
  Settings hand-off on tap (ADR-007), honest about being visibility-only: it never moves the
  health level, and the degraded banner names the hidden notification only when the fallback
  path is the one actually enforcing. JVM-tested (`HealthStatusTest`, `BlockingHealthUiTest`).
- **Block screen is now screen-reader operable, and the HARDER wait shows progress:** the
  hold-to-request break control was a bare gesture surface — invisible to TalkBack, so a
  screen-reader user on a HARDER block had no way to request a break at all. It now carries
  button semantics with a custom "Request a break" action (the hold friction is deterrence,
  not security; for a TalkBack user the explicit action is the equivalent deliberate act).
  Countdown pills speak words ("4 minutes 32 seconds", not "4m 32s"), and the HARDER
  break-cooldown notice gained a determinate progress ring driven by the pending request's
  real friction length (text-only fallback when the total is unknown). JVM-tested
  (`BlockScreenContentTest`, `BlockCauseTest`).

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
- **Android:** rules created in the Blocklist tab no longer silently drop out of enforcement
  when the legacy quick-blocklist reloads (a11y-service connect, boot warm-up, Home-tab
  edits). `ActiveRulesHolder` is now lane-based: each writer replaces only its own
  contribution and the engine reads the merged snapshot, so the old clobber window — and the
  resume-time "republish" workaround papering over it — are gone. JVM-tested in `core-model`.
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
