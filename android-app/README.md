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

**Preset routine library (`feat/android-preset-routines`) — implemented; JVM-tested draft
logic; CI compile is the build gate; emulator pass pending.** The Blocklist overview now
offers the five Opal-mirroring templates the feature inventory names (Laser Focus,
Rise and Shine 6–9 AM, Reading Time, Gym Time, Weekend Limit) as one-tap cards. A preset
prefills only the routine's *shape* — name, days, window, curated break level — and opens the
normal rule editor; apps/sites stay empty on purpose (no catalog knows which apps derail this
user), so the editor's existing NO_TARGETS validation walks the user to the picker before
anything saves. No preset defaults to HARDCORE (a one-tap template must not arm a
cannot-stop-early block); raising it is one tap in the editor. Catalog + draft mapping are
pure Kotlin (`RoutinePresets`, JVM-tested); iOS has no preset library yet.

**This integration pass (A-VERIFY) — NOT compiled: this session's environment has no Android
toolchain; `make android` / `make android-test` on the Mac are the outstanding gates.**
What it wired, verified by inspection only:

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

## Known gaps (found in the integration audit)

1. **Weekday convention bug — Path A schedules are wrong on Sundays/Fridays.** `core-model`'s
   `Schedule.daysOfWeek` is documented ISO (1=Mon…7=Sun) and `feature-blocklist` writes ISO days,
   but `FlintAccessibilityService.kt:120` feeds `Calendar.DAY_OF_WEEK` (1=Sun…7=Sat) into
   `BlockDecisionEngine.decide` — so a "Weekdays" rule blocks Sunday and skips Friday on Path A.
   Path B (`UsageStatsForegroundService`) converts to ISO as of this pass. Needs a coordinator
   hotfix (out of A-VERIFY scope): the same one-line conversion in `blocking-accessibility`, plus
   re-stating `BlockDecisionEngine.scheduleActive`'s KDoc (lines 84–85) and the
   `BlockDecisionEngineTest.scheduleGatingDayAndWindow` fixture (line 81) in ISO terms — the
   engine itself is convention-agnostic (pure membership test), so only callers + docs move.
2. **Legacy-writer clobber window.** `BlocklistStore.load()`/setters (a11y-service connect, boot
   warm-up, debug receiver) overwrite `ActiveRulesHolder` with the legacy projection alone;
   DataStore-authored rules drop out of enforcement until the next rule change or app resume
   republishes the combined list. Clean fix belongs in core-data/blocking-accessibility.
3. **`DailyLimitTracker` has no caller.** Path B daily time budgets are not enforced yet; Time
   Limits enforce on Path A only (`LimitStore` + `UsageQuery` in the a11y service).
4. **`POST_NOTIFICATIONS` is declared but never requested at runtime**, so on Android 13+ the
   Path B foreground-service notification is invisible (the service still runs).
5. **Not re-verified end-to-end since the merges.** Every claim in "merged since" is
   compile/unit-test-gated per task, but the integrated app (this nav shell + bridge + Path B
   handoff) has not been built or run anywhere yet.

Emulator-verifiable next: build, then re-run the MVP script above; Path B is emulator-verifiable
too (grant usage access, keep a11y off, launch a blocked app → overlay/BlockActivity).
Device-only proof (OEM kill behavior, real-world resilience) stays in the board's `H-*` tasks.

## Module map (all implemented; integration compile pending)

| Module | Kind | What it holds |
|---|---|---|
| `app` | app | `FlintApplication` (bridge + boot hook), `MainActivity` (tab shell + Home), `ActiveRulesBridge`, `PathBServiceGate`, manifest (permissions + queries), debug `SetBlocklistReceiver` |
| `core:core-model` | kotlin-jvm | Pure data: `BlockRule`, `Schedule` (ISO days), `BreakLevel`, `Verdict`, limits/breaks models, `ActiveRulesHolder` |
| `core:core-common` | android-lib | `FlintTheme` (brand tokens → Material3), `OemUtil` |
| `core:core-data` | android-lib | Legacy synchronous stores: `BlocklistStore`, `LimitStore`, `UsageQuery` |
| `core:core-datastore` | android-lib | Preferences DataStore: `BlockRulesStore`, `LimitsStore`, `FocusStateStore` + codecs (unit-tested) |
| `blocking:blocking-engine` | kotlin-jvm | `BlockDecisionEngine` + break/pass/open-limit policies + `ClockChangeGuard` (fail-closed time-change policy) + `DayMath` (unit-tested) |
| `blocking:blocking-accessibility` | android-lib | `FlintAccessibilityService` — Path A detector + a11y-overlay enforcement (no `isAccessibilityTool`) |
| `blocking:blocking-usagestats` | android-lib | `UsageStatsForegroundService` (poll loop → Path B handoff), `UsagePoller`, `DailyLimitTracker` |
| `blocking:blocking-overlay` | android-lib | `BlockScreenCoordinator` (shared seam), `PathBBlockHandoff`, `OverlayController`, `BlockActivity` |
| `blocking:blocking-resilience` | android-lib | `BootReceiver` (+ re-arm hooks), `TimeChangeReceiver` (clock/timezone-change guard), `ExitReasonReporter`, `PermissionHealthChecker` |
| `permissions:permissions-special` | android-lib | `UsageAccess`, `OverlayPermission`, `AccessibilityPermission`, `BatteryOptimization` |
| `feature:feature-onboarding` | android-lib | `AccessibilityConsentScreen` — **prominent-disclosure consent (Play gate, ADR-007)** |
| `feature:feature-blocklist` | android-lib | Rule/limit editors, app picker, domain input, preset routine library (draft + preset logic unit-tested) |
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
