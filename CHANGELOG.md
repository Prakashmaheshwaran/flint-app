# Changelog

All notable changes to Flint will be documented in this file.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning: [SemVer](https://semver.org/).

## [Unreleased]

### iOS
- **Schedules can no longer silently fail to block.** `DeviceActivityCenter.startMonitoring`
  throws on a window it won't take (zero-length, or under its 15-minute floor) and the schedules
  controller armed with `try?` — so such a rule saved, showed an ON toggle, and shielded nothing.
  The window contract now lives in FlintCore (`FlintSchedule.issues`, one definition shared by the
  editor, `armPlan`, Sleep Mode, and the preset tests): the editor refuses to save an
  unregistrable window and names the reason, `reload()` skips rules iOS would reject rather than
  swallowing the throw, and any enabled-but-unarmed rule is listed at the top of the Schedules
  screen instead of lying to the user. Overnight windows (22:00–07:00) are measured across
  midnight, not as negative time. Also fixes an out-of-range weekday crashing the schedule row and
  a rule with such a day being uneditable. Logic verified off-device; Xcode build/test gated on
  the CI toolchain, and shield enforcement stays device-gated like all shields.
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
  Full Assist and an optional Morning Assist window, the five-preset Android routine library, and
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
  death. That run also measured the last allowed open being shielded ~1.8 s after launch; it was
  recorded as a pending product decision and is now fixed (see *Fixed* below).
- **Notifications join the blocking-health rows (Android 13+):** `POST_NOTIFICATIONS` was
  declared but never requested, so the Path B backup-blocking service ran with its status
  notification invisible. Settings now shows a "Notifications" row on 13+ — disclosure first,
  Settings hand-off on tap (ADR-007), honest about being visibility-only: it never moves the
  health level, and the degraded banner names the hidden notification only when the fallback
  path is the one actually enforcing. JVM-tested (`HealthStatusTest`, `BlockingHealthUiTest`).
- **Block-screen accessibility:** TalkBack can activate the HARDER break control, abbreviated
  countdowns have word-based spoken copy, and pending HARDER waits show determinate progress.
- **Path B Time Limits:** daily-budget checks now use a JVM-tested 15-second UsageStats cache,
  avoiding a system query on every one-second foreground poll.
- **Home app search:** the quick blocklist filters by app label or package name, preserves list
  order, and shows an explicit no-match state.
- **Honest Home setup guidance:** the status card now reports blocking as on whenever either
  enforcement path is healthy, re-checks the complete permission state on every return, and
  offers the cheapest next setup step. Accessibility remains behind its disclosure screen;
  usage access, overlay, and battery exemption use explicit Settings hand-offs. A denied
  Android 13+ notification grant is described as visibility-only when Path B is active.

### Fixed
- **Android:** the last allowed open of an Open Limit is usable again. The quota was re-decided
  on *every* evaluation of the foreground app, not just on entry — recording the Nth open pushes
  `used` to the quota, so the next Path B poll tick (~1 s) or Path A window event shielded the
  open the user had just been granted, and the shield then stood until local midnight. "N opens
  per day" was worth N-1 usable opens; a limit of 1 was worth none. Measured live at 1.83 s in
  `docs/verification/android-openlimits-2026-07-08/`. Both detection paths now fold transitions
  through `OpenLimitPolicy.onForegroundTransition`, which decides once per open, carries the
  grant through shade dips and Flint's own windows, and releases it when another app takes the
  foreground — so re-entering an exhausted app still blocks, and a "use anyway" break still
  re-blocks when its 5-minute exemption ends. JVM-tested; emulator re-run pending.
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
- **Tooling:** `scripts/check-release-version.sh` now catches a release tag whose compiled
  app versions were not bumped. Tagging `v0.2.0` while Android still declares `versionName =
  "0.1.0"` would publish a file named for `v0.2.0` that still installs as `0.1.0`; leaving
  `versionCode` unchanged also makes Android refuse the upgrade (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
  Run `make release-check TAG=v0.2.0` before cutting the tag.
- **Tooling:** `make selftest` now covers both the Android emulator verification harness and
  the release-version checker without an emulator, Gradle, Xcode, or an Android SDK.

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
