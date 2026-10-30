# Changelog

All notable changes to Flint will be documented in this file.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning: [SemVer](https://semver.org/).

## [Unreleased]

### v1 — integration/v1 (refreshed 2026-10-30)

This is an integration summary, not a published v1 release or store/device acceptance.
The [capability ledger](docs/acceptance/v1-capability-ledger.md) records the exact merged
checkpoint, evidence and remaining gaps; [ADR-012](docs/architecture/protection-contract.md)
defines requirements without turning them into passing results.

- **Protection:** iOS session timing/registration, session replacement/stop policy, shared
  ledger and startup health; Android terminal safety floor, continuous evaluation/failover,
  acknowledged saves and durable commit-before-release grants. Pure ADR-012 policies and
  live callers have distinct acceptance boundaries. Android Settings/uninstall/disable
  interception is removed; OS recovery remains reachable.
- **Final protection integration:** iOS generation/reboot/replay authority, trusted rule clocks,
  frozen Safari policies, global recovery and scoped ordinary breaks; enabled timed Open
  credits remain a hard signed-device pre-release gate. New empty Allow Lists are refused,
  saved ones keep running with attention and calling/SOS hardware acceptance remains open.
  Android adopts saved Time-Limit tiers, explicit credits/global recovery, strict initialized
  accounting and exact-Timer live-detector arming; force-stop can unbind Accessibility.
  Final wind-down merges add #225 trusted Android Time-Limit days/retained usage, #226
  default-off captured removal consent/derived guard, #228 stale-session break fallback,
  #229 refusal feedback and #230 all-six SystemBootTime privacy declarations. The ledger's
  Open at v1 wind-down section records remaining red-team variants and hardware gates.
- **Local outcomes:** durable session history and truthful Today/Complete/Insights totals,
  separate from screen usage; optional planned-focus-end reminders. iOS captures use
  labelled fixtures; Android hosted E2E includes an actual five-minute Timer. Missing terminal
  evidence stays Unconfirmed; confidence-aware totals conservatively subtract uncertain gaps.
  Durable Android pass/break projection and stable iOS replay IDs are merged, with explicit
  incomplete typed-iOS-break/operation-history boundaries in the ledger. #227 arbitrates
  new Android terminal writes; old duplicates remain readable.
- **Glance and UX:** read-only widgets, opt-in iOS Live Activity, optional Android Path A
  ongoing status, Help/limitations, accessible onboarding/PIN/picker/Sleep and Android
  scrollable block-screen work. Live status producers and hardware rendering retain their
  explicit boundaries in the ledger. Live commit/Hub/service publishers are wired; Android
  installed minified widget smoke covers unavailable/Open Flint. iOS #223 passes ten UI
  fixtures plus eleven Insights cases at its exact source, with no hardware promotion.
- **Release preparation:** iOS AppIcon, six privacy manifests after widget integration,
  unsigned Release/fixture gates and private signing scaffold; Android target API 36,
  APK/AAB checks, owner-signing configuration with labelled preview fallback, shrinking
  and actual installed-release smoke. Android includes offline policy/license screens.
- **Acceptance:** store/privacy drafts, one evidence-linked capability ledger and one
  risk-ordered physical-device plan. Physical hardware, signing/account facts, uploads,
  review and publication remain open human gates. No refreshed competitor pricing or
  hardware protection guarantee is made.

### Historical pre-v1 development notes (original entries retained)

> The entries below record earlier implementations and their original verification state.
> They are superseded by ADR-012 and the v1 ledger for current behavior. In particular,
> historical Android uninstall guards are removed, tier labels do not prove universal
> editor locks, pass renewal is not promised at a calendar-week boundary, and legacy
> Open/Timer semantics must not be read as the new contract. Old compile/test debt and
> dated verification claims remain here for provenance rather than current status.

- **Android (removed): the Hardcore uninstall/Settings guard.** Per the protection contract
  (ADR-012 §10) and the Play Accessibility API policy, Flint no longer intercepts the uninstaller,
  Settings, Accessibility disable, app info, force-stop or permission revocation at any tier, and
  no longer reads Settings window text. The uninstaller and Settings/permission surfaces are on
  the safety floor instead; turning access off reduces protection and stays the user's choice.
  This supersedes the "Hardcore uninstall guard (Path A)" entry below.
- **Android:** the safety floor (dialer, emergency, default home app, Settings/permission
  recovery, uninstaller, Flint) applies before every block cause on both detectors.
- **Android:** native finite-session Timer with persisted restoration, explicit hold-to-leave,
  and preservation of overlapping restrictions; selected-day and weekly usage rankings.
- **Android:** strongest-cause arbitration prevents ordinary breaks bypassing overlapping
  Hardcore restrictions. Versioned access and managed-authority foundations remain dormant.
- **Development:** preserve the local account/group/managed-policy sandbox as a debug-only
  module with Auth/Firestore integration tests. Release APKs exclude its client, Firebase
  account SDK and network permissions; managed policies do not activate device blocking.
- **CI:** run script selftests, validate release versions before upload, and verify the actual
  release APK's local-first boundary. Public previews use release code with development signing.

### iOS
- **Blocking Health now reports the whole enabled → attempted → armed funnel.** Schedule rules
  rejected by Flint's window validation now enter the same health ledger as registrations iOS
  refuses, so Settings cannot show green when an enabled rule was skipped before
  `startMonitoring`. Time Limits and Open Limits record the same three counts. The near-cap
  warning uses successfully armed registrations; its around-20 threshold remains an empirical
  observation, not an Apple-published number, because `DeviceActivity` exposes only a finite,
  undocumented pool shared by these monitors. Existing persisted health reports decode into the
  richer model without being discarded.
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
- Block Now, schedules with no Flint-imposed count cap, Time Limits, break levels incl. free Hardcore,
  free weekly Emergency Pass, website blocking, embedded usage report,
  Sleep Mode + Morning Assist, Open Limits, Focus Filters, Siri/Shortcuts intents.
- Built and unit-tested against the Simulator. **Shield enforcement requires a physical
  device** (an Apple platform constraint — the Simulator cannot grant Family Controls).

### Android (Kotlin / Jetpack Compose, multi-module)
- Real blocking via AccessibilityService + overlay, schedule-gated blocking, Time Limits,
  screen-time stats, branded block screen, boot/resilience layer — verified on an emulator.
- Pure-Kotlin `BlockDecisionEngine` with JVM unit tests.
