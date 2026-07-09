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
- **Android:** a website rule can no longer be walked past by re-spelling the host. The
  address bar is canonicalized before matching, so a blocked site still blocks when its URL
  carries a second URL in the query. Chrome elides the scheme, so the omnibox text
  `reddit.com/submit?url=https://example.com` was split on the *query's* `://` and read as a
  visit to `example.com`. Also fixed: hosts qualified with a root dot (`reddit.com.`),
  preceded by credentials (`evil.com@reddit.com`), or spelled with backslashes, tabs, or
  mixed case. The same change retires the mirror-image false positive — searching for a
  blocked URL (`google.com/search?q=https://reddit.com`) no longer shields the search engine.
- **Android:** Microsoft Edge and Opera were listed as browsers but had no address-bar view
  id, so website rules silently never fired in them. Browser → address-bar ids now live in one
  unit-tested table, which additionally covers Chrome Beta/Dev/Canary, Brave and Vivaldi
  channels, Kiwi, Firefox Beta/Nightly/Focus, Opera Mini/GX, and DuckDuckGo.
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
