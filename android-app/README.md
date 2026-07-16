# Flint — Android

Native Android app (Kotlin + Jetpack Compose), multi-module. The enforcement engine is isolated
from the UI so it can run headless and survive process death. Strategy & rationale:
[`../docs/research/03-android-technical-strategy.md`](../docs/research/03-android-technical-strategy.md).

## Build

```bash
# from repo root
make android          # assemble debug APK  (JAVA_HOME defaults to openjdk@21)
make android-test     # run unit tests (pure-Kotlin: engine, drafts, codecs, policies)
# or directly:
cd android-app && ./gradlew assembleDebug
```

Requires JDK 21 and an Android SDK (API 35). `minSdk = 23`, `targetSdk = 35`,
`applicationId = com.flint.peakfocus`.

## Architecture in one line

Two detection paths — **AccessibilityService** (primary, real-time, the only URL source) and
**UsageStatsManager** (fallback + daily budgets) — feed one pure-Kotlin **`BlockDecisionEngine`**,
which drives one shared enforcement seam (`BlockScreenCoordinator` → a11y overlay /
`SYSTEM_ALERT_WINDOW` overlay / full-screen `BlockActivity`), kept alive by a foreground service
and a resilience layer.

## Status — honest, current

**Verified earlier on an Android 35 emulator (pre-fleet MVP path):** the end-to-end block —
pick an app → enable the Flint accessibility service (behind the prominent-disclosure consent) →
the blocked app is covered by a full-screen accessibility-overlay block screen within ~1s.
Schedule-window gating, per-app daily Time Limits, and survive-reboot (the OS rebinds an enabled
a11y service) were verified the same way. Those runs predate the feature merges below.

**Merged since (fleet waves, per-task verifies green at merge time):** rich blocklist UI
(rules/schedules/limits/break levels — `feature-blocklist`), screen-time stats
(`feature-stats`), blocking-health settings (`feature-settings`), branded shared block screen
(`feature-blockscreen`), breaks/Emergency Pass/Open Limits policies (engine + overlay), DataStore
persistence (`core-datastore`), and the resilience layer (`blocking-resilience`).

**Integration pass (A-VERIFY) — wired in a session with no Android toolchain, so `make android`
/ `make android-test` were the outstanding gates; both have since gone green and the integrated
app was emulator-driven (see the 2026-07-06 and 2026-07-08 runs below).**
What it wired, verified by inspection at the time:

- **Navigation:** `MainActivity` hosts four state-switched tabs behind a Material3 bottom bar —
  Home (quick blocklist + service status), Blocklist, Stats, Settings. No nav library (none in
  the version catalog); same state-driven pattern the shell already used. The ADR-007 consent
  screen renders full-screen *instead of* the tab shell; Accept is the only route to the
  accessibility-settings hand-off.
- **Path B handoff:** `UsageStatsForegroundService.tick()` now routes **every** poll observation
  through `PathBBlockHandoff.onForegroundPolled(...)` (the overlay module's documented one-line
  call site) — Block renders the shared screen, Allow drives open-limit counting and overlay
  stand-down.
- **Rules actually reach the engine:** `ActiveRulesBridge` (app) feeds `ActiveRulesHolder` from
  *both* the legacy quick blocklist and the DataStore rules the Blocklist tab writes — the bridge
  `BlockRulesStore`'s KDoc deferred to an integration task.
- **Path B lifecycle:** `PathBServiceGate` (app) starts the poll service only when usage access
  is granted *and* the a11y service is off (running both would double-enforce), stops it
  otherwise; re-checked on every app resume and from the boot re-arm hook `FlintApplication`
  registers with `BootReceiver`.

**Time-change guard (`feat/android-time-change-guard`) — implemented; CI-verified build/tests;
emulator re-run pending.** Changing the device clock, date, or timezone no longer resets what
was already consumed: `TimeChangeReceiver` (blocking-resilience) catches
TIME_SET / TIMEZONE_CHANGED / DATE_CHANGED, re-warms the rules cache, runs the same re-arm
hooks as `BootReceiver`, and applies the pure `ClockChangeGuard` (blocking-engine, JVM-tested)
to the persisted focus state. Fail-closed semantics: day/week keys only roll *forward*
(enforced unconditionally inside `OpenLimitPolicy.rolledOver` / `EmergencyPassPolicy.refreshed`
on every evaluation, so a clock set back never re-grants consumed opens or a spent Emergency
Pass — even if the broadcast never arrives); detected forward jumps and timezone hops carry
consumed state into the new day/week instead of resetting it; a pending HARDER friction wait
keeps the same real length under any wall-clock jump. Honest limits: schedule windows and
Path-A Time Limits read the OS clock / UsageStats daily buckets, so a changed clock still
moves them (the receiver forces immediate re-evaluation; it cannot veto the OS clock), and a
clock set back during an *already granted* break stretches that break's exemption window —
HARDCORE is unaffected (it never grants breaks).

**Uninstall guard + stats insights — implemented; build + unit tests green;
Path A emulator UI shield still pending.** While any enabled HARDCORE rule's schedule window is
active, Path A now shields the system uninstaller outright (mirroring iOS `denyAppRemoval`'s
device-wide denial)
and Settings pages *about Flint* (App-info / Accessibility toggle — mention-gated and
danger-gated, so plain Settings stays usable during a block; an *unreadable* window falls back
to the class name alone, fail-closed on the dangerous screen families). Decision half is pure
Kotlin in `blocking-engine` (`UninstallGuard`, JVM-tested); the service side measures the
foreground window (event class name + capped node-text sweep, gated behind a cheap `isArmed`
pre-check so unarmed users never pay for the sweep) and routes verdicts through a dedicated
`uninstallGuardBlockCause` — its shield deliberately offers **no** break/pass affordance,
because the guard re-shields regardless of exemptions and would otherwise burn the weekly
Emergency Pass for nothing (caught by the adversarial review pass; JVM-tested). Honest limits
in the class KDoc: Path A only, English danger-phrase fallback (class-name +
uninstaller-package gates are locale-independent), OEM lists best-effort, protects impulse not
determination. The Stats tab adds a "Week in review" card (typical completed day, busiest day)
and a today-vs-typical line (`UsageReport` derivations, JVM-tested).

**Premium UI overhaul — implemented; build + unit tests green; emulator-verified 2026-07-06
(see below).** One design direction ("warm charcoal, one ember") across every screen, grounded in
`design/tokens.json` extensions (tonal surfaceContainer ladder, brand danger/outline roles,
type ramp, radius/spacing scales, motion vocabulary) bound in `FlintTheme` (typography + shapes
now passed to MaterialTheme) plus a shared component kit (`core-common` `ui.FlintKit`:
FlintScreenHeader/FlintCard/FlintStatusDot/FlintBadge/FlintInfoPill/FlintSectionLabel) and the
tri-color brand mark as a dependency-free ImageVector (`FlintIcons`). All six surfaces rebuilt
on the kit — shell (tonal nav bar, tab crossfades), Home (status dashboard, M3 time pickers),
block screen (breathing spark mark, radial ember glow, tabular-numeral pills), stats (animated
bars, today gradient), blocklist + editors (sectioned cards, push/pop motion), settings
(brand-colored health states) — with the ADR-007 consent disclosure byte-identical (visual
container only, verified by a dedicated review lens).

**Block Now sessions — implemented; build + unit tests green; emulator-verified end-to-end
2026-07-06 (see below).** One-tap timed focus sessions from Home (duration + difficulty chips
over the quick blocklist): a session is a one-shot rule whose `expiresAtEpochMs` the **engine
itself** checks
— it can never outlive its timer or re-fire weekly the way a same-day schedule would, even if
cleanup never runs (expired rows are swept lazily by the rules bridge). Live countdown card,
early stop gated by tier (EASY only; HARDER/HARDCORE get honest copy), sessions hidden from
the rule-authoring UI so editing can't quietly strip an expiry, block screen counts down to
the session's end. Codec appends a backward-compatible 11th field (pre-expiry lines still
decode); engine/guard/codec/cause paths all JVM-tested. Session expiry is wall-clock, but the
time-change guard shifts it by the measured jump (`ClockChangeGuard.guardedSessionRules` —
same elapsedRealtime-anchored delta as the HARDER-wait guard), so setting the clock forward
cannot end a Hardcore session early nor a set-back stretch one. Honest limit: a jump the
anchor cannot measure (clock changed across a reboot/power-off) leaves expiry at face value —
same class of limitation the guard already documents for schedule windows.

Android 13+'s `POST_NOTIFICATIONS` permission also surfaces as its own blocking-health row
in Settings. It is visibility-only, so it never changes the enforcement health level; the
degraded banner names the hidden notification only when Path B is actively enforcing.

**Sleep Mode, preset routines & named groups — implemented; JVM-tested; emulator run pending.**
Sleep Mode: bedtime→wake windows on chosen nights (post-midnight bedtimes day-shift correctly),
Wind Down / free Full Assist materialized as allow-list rules on the schedules engine (the same
approach as iOS), an optional enforced Morning Assist window, and one saved app group allowed
overnight (`SleepRules` + `SleepEditorScreen`); the engine's telephony safety floor and a
runtime launcher/dialer exemption keep emergency calling and Home reachable. Preset routines:
the same four honest presets as iOS prefill rule drafts — targets stay the user's to pick
(`RoutinePresets`). Named groups: DataStore-persisted app groups (`GroupsStore`) applied into
rule drafts in one tap, with name-upsert and delete.

**Also fixed in this wave:** DataStore-authored Time Limits (Limit editor) now actually
enforce — they previously had **zero** enforcement readers (only the debug receiver's legacy
store did); both paths now take the stricter of the two stores via a coordinator snapshot.
Path A also arms a one-shot budget re-check while a time-limited app sits in front, so the
block lands when the budget crosses mid-session instead of on the next app switch (Path B's
1s poll already caught this). The Path B FGS notification re-posts on every service start so
a post-hoc POST_NOTIFICATIONS grant takes effect. A delete-mid-animation flash in the rule
editor was fixed by snapshotting editor inputs per destination. Accessibility/insets pass:
the HARDER hold-to-request control now exposes a TalkBack action (hold friction is a *touch*
affordance, not an accessibility barrier), schedule day pills got 48dp touch targets +
checkbox semantics, and the block screen self-insets (`safeDrawingPadding`) since none of its
three hosts do. The HARDCORE block screen now reflects a spent weekly Emergency Pass (inert
"used — a new one arrives Monday" notice instead of a button whose tap silently no-oped;
threaded from the coordinator's pass snapshot, JVM-tested). Home rows show real app icons
(small pre-decoded bitmaps) with letter-avatar fallback, plus skeleton loading rows and an
empty state.

## Emulator verification (2026-07-06, `flint-test` AVD — API 35 google_apis arm64, headless)

The integrated app was installed and driven end-to-end via adb (evidence stayed session-local;
the archived-evidence convention under `docs/verification/` began with the 2026-07-08 run).
**Verified live, with screenshots reviewed in-session:** the MVP block flow (enable the a11y
service → quick-blocklist Chrome → the
branded block screen covers Chrome within ~1s); **Block Now** end-to-end (Hardcore session
started from the Home UI → live countdown card → Deep Focus block screen with the "Unblocks
in …" pill and the Emergency-Pass-only affordance **while an EASY legacy rule shared the same
target** — the strictest-match engine fix observed working); the Hardcore pre-commitment
disclosure; a running session surviving an app reinstall; the premium UI on Home (idle + ON),
Stats (permission prompt), Settings (DEGRADED health banner, status dots), and the dark block
screen. Two findings came out of the run: selected chips rendered M3's baseline lavender
(unmapped `secondaryContainer` — fixed as tokens.json `selectedContainer` → Ember, re-verified
on-device) and the a11y overlay lingered over Flint's own UI when the app was opened externally
while a shield was up — fixed (a self-window event naming the package-derived MainActivity
class hides the shield; the overlay's own attach events never match) and re-verified live:
external open lands on Home, and the blocked app still re-shields immediately after.

**Closed by the two 2026-07-08 runs below:** Path B fallback flow, daily Time Limit fallback,
Easy break consumption, self-surface stand-down/re-shield, and Open Limits end-to-end on Path B.
Still pending: Path A open counting, sleep windows, uninstall-guard shielding, boot re-arm, and
the time-change-guard broadcast path. Device-only proof (OEM kill behavior, real-world
resilience) stays in the board's `H-*` tasks.

## Emulator verification (2026-07-08, `flint-test` AVD — API 35 google_apis arm64, headless)

`make android-test` and `make android` both passed locally, then
[`scripts/android-pathb-verify.sh`](../scripts/android-pathb-verify.sh) installed the debug APK
and drove Path B with AccessibilityService forced off, usage access allowed, and overlay access
allowed. **Verified live, with screenshots and window dumps:** `UsageStatsForegroundService`
starts as a foreground service; a debug-seeded Clock block renders the Path B overlay; `Open
Flint` stands the overlay down on Flint's own UI; relaunching Clock re-shields it; the Easy
`Take a break` action grants an exemption and drops the shield; and a debug-seeded `0` minute
Contacts daily Time Limit renders the `TIME LIMIT` block screen through Path B. Evidence:
[`docs/verification/android-pathb-2026-07-08/`](../docs/verification/android-pathb-2026-07-08/).

**Not exercised in this run:** Path A-only uninstall-guard shielding (Path B cannot read
Settings/uninstaller window text and the run deliberately kept a11y off), Open Limits, sleep
windows, boot re-arm, the time-change-guard broadcast path, OEM kill behavior, and real-device
resilience.

## Emulator verification (2026-07-08, Open Limits — `flint-test` AVD, API 35 google_apis arm64, headless)

A second same-day run closed the Open Limits entry above on Path B. `make android-test` and
`make android` passed, then a UI-driving script (archived with the evidence) installed the debug
APK and exercised Open Limits with AccessibilityService off, usage access allowed, overlay access
allowed, and `pm clear` first. The debug seed receiver has no Open-Limit hook, so the run authors
the limit through the real UI — deliberately verifying the whole chain **Blocklist tab → New app
limit → app picker → limit editor → DataStore `LimitsStore` → `BlockScreenCoordinator` →
`OpenLimitPolicy` → shield**. **Verified live, with screenshots, window dumps, and UI dumps
(Clock · 2 opens/day · EASY):** open #1 allowed unshielded across ~5 poll ticks; open #2 hits
the quota with a branded `OPEN LIMIT` shield and next-local-midnight countdown; HOME stands the
shield down; an over-quota reopen re-shields; the EASY `Take a break` action stands the shield
down; and open counts survive `am force-stop` — relaunching re-shields in 0.77 s with the
`OPEN LIMIT` cause still attached. Evidence:
[`docs/verification/android-openlimits-2026-07-08/`](../docs/verification/android-openlimits-2026-07-08/).

**Known nuance (product decision pending):** the *last* allowed open is truncated — with
"2 opens/day", open #2 is granted and then shielded **~1.8 s after launch** (measured 1.83 s),
because the coordinator re-decides on every 1 s poll tick and `OpenLimitPolicy` blocks at
`used >= dailyOpens` once the entry tick has recorded the second open. In practice "N opens/day"
is N−1 full opens plus one ~2-second open. The engine test `atQuotaBlocksAndCarriesTheLimit`
asserts at-quota blocking and the model KDoc says the block appears "after the quota", so this
may be intended — but a user may expect N *usable* opens. Path A shares the same
coordinator/policy and would behave the same on its event-driven cadence. If unwanted, the fix
belongs in the coordinator/policy seam; the drafted policy patch in `/tmp` explores entry
semantics.

**Not exercised in this run:** Path A open counting (a11y deliberately off), HARDER/HARDCORE
open-limit tiers, the observable effect of the break's extra spent open after the 5-minute
exemption lapses, real-midnight rollover, and the "system-surface bounces don't count as opens"
heuristic (JVM-tested only).

## Known gaps

**Fixed since the 2026-07 integration audit:**

- *Weekday convention bug (was gap 1):* the service feed now converts `Calendar.DAY_OF_WEEK`
  to ISO before calling the engine (`A-FIX-WEEKDAY`); engine KDoc + test fixture restated in
  ISO terms.
- *Legacy-writer clobber window (was gap 2):* `ActiveRulesHolder` is now **source-keyed** —
  each writer publishes only its own contribution (`legacy-blocklist` vs `datastore`) and can
  no longer overwrite the other's, so DataStore-authored rules stay in enforcement across
  service connects / boot warm-ups / legacy setters. JVM-tested (`ActiveRulesHolderTest`);
  `ActiveRulesBridge` shrank to the DataStore stream + a legacy re-sync helper.
- *Test-gate hole (found fixing the above):* `make android-test` ran `testDebugUnitTest`,
  which does not exist on the pure-Kotlin modules — the engine/core-model tests were silently
  skipped locally. The target now runs `./gradlew test`, matching CI.
- *`DailyLimitTracker` had no caller (was gap 1 of this list):* Path B now enforces daily Time
  Limits — `UsageStatsForegroundService.tick()` checks the stricter of the legacy `LimitStore`
  and DataStore-authored thresholds against `DailyLimitTracker`'s consumption measure, ahead
  of the rule verdict, and routes hits through
  `PathBBlockHandoff.onTimeLimitExceeded` (break/pass stand-down still wins; no Open-Limit
  open is recorded — Path A's exact order). UsageStats verdicts cache for 15 seconds so the
  one-second poll loop does not issue an expensive system query every tick.
- *`POST_NOTIFICATIONS` never requested (was gap 2):* the app shell now asks **once**, on
  resume, and only when Path B is actually in play (usage access granted, a11y off — the
  same condition the service gate starts it under); a decline is respected, never re-prompted.
  Enforcement never depended on the grant — only the FGS notification's visibility does.

Emulator-verifiable next: Path A open counting, sleep windows, boot re-arm, the
time-change-guard broadcast path, and Path A uninstall-guard shielding. Device-only proof (OEM
kill behavior, real-world resilience) stays in the board's `H-*` tasks.

## Module map (all implemented; `make android` + `make android-test` green as of 2026-07-08)

| Module | Kind | What it holds |
|---|---|---|
| `app` | app | `FlintApplication` (bridge + boot hook), `MainActivity` (tab shell + Home), `ActiveRulesBridge`, `PathBServiceGate`, manifest (permissions + queries), debug `SetBlocklistReceiver` |
| `core:core-model` | kotlin-jvm | Pure data: `BlockRule`, `Schedule` (ISO days), `BreakLevel`, `Verdict`, limits/breaks models, `ActiveRulesHolder` |
| `core:core-common` | android-lib | `FlintTheme` (brand tokens → Material3), `OemUtil` |
| `core:core-data` | android-lib | Legacy synchronous stores: `BlocklistStore`, `LimitStore`, `UsageQuery` |
| `core:core-datastore` | android-lib | Preferences DataStore: `BlockRulesStore`, `LimitsStore`, `FocusStateStore`, `GroupsStore` + codecs (unit-tested) |
| `blocking:blocking-engine` | kotlin-jvm | `BlockDecisionEngine` + break/pass/open-limit policies + `ClockChangeGuard` (fail-closed time-change policy) + `DayMath` (unit-tested) |
| `blocking:blocking-accessibility` | android-lib | `FlintAccessibilityService` — Path A detector + a11y-overlay enforcement (no `isAccessibilityTool`) |
| `blocking:blocking-usagestats` | android-lib | `UsageStatsForegroundService` (poll loop → Path B handoff), `UsagePoller`, `DailyLimitTracker` |
| `blocking:blocking-overlay` | android-lib | `BlockScreenCoordinator` (shared seam), `PathBBlockHandoff`, `OverlayController`, `BlockActivity` |
| `blocking:blocking-resilience` | android-lib | `BootReceiver` (+ re-arm hooks), `TimeChangeReceiver` (clock/timezone-change guard), `ExitReasonReporter`, `PermissionHealthChecker` |
| `permissions:permissions-special` | android-lib | `UsageAccess`, `OverlayPermission`, `AccessibilityPermission`, `BatteryOptimization` |
| `feature:feature-onboarding` | android-lib | `AccessibilityConsentScreen` — **prominent-disclosure consent (Play gate, ADR-007)** |
| `feature:feature-blocklist` | android-lib | Rule/limit/sleep editors, preset routines + named groups, app picker, domain input (draft logic unit-tested) |
| `feature:feature-stats` | android-lib | 7-day screen-time report from `UsageStatsManager` (aggregation unit-tested) |
| `feature:feature-blockscreen` | android-lib | The shared branded "blocked" UI (hosted by overlay window or `BlockActivity`) |
| `feature:feature-settings` | android-lib | Blocking-health rows, battery exemption, OEM auto-start guidance, exit diagnostics |

## Compliance gates (do not skip)

- **No** `android:isAccessibilityTool="true"` — Flint is a "monitoring app" and is ineligible.
  (Audited this pass: the flag appears nowhere in the merged manifests.)
- The `AccessibilityConsentScreen` must be shown **before** sending the user to enable the
  service. (Audited this pass: the shell's Enable button opens the consent full-screen; only
  Accept fires the Settings intent. `feature-settings` re-states the disclosure and never
  auto-redirects.)
- `foregroundServiceType=specialUse` needs a Play Console justification at submission.
- Don't depend on `USE_FULL_SCREEN_INTENT` (auto-revoked for non-call/alarm apps on Android 14+).

Full ship checklist: strategy doc §10.
