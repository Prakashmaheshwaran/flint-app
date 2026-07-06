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

1. **Not re-verified end-to-end since the merges.** Every claim in "merged since" is
   compile/unit-test-gated per task, but the integrated app (this nav shell + bridge + Path B
   handoff) has not been built or run anywhere yet.

Closed since the audit: the **weekday convention bug** (Path A now converts
`Calendar.DAY_OF_WEEK` to ISO before calling the engine; KDoc + fixtures restated in ISO
terms); the **legacy-writer clobber window** (`ActiveRulesHolder` is now lane-based —
each writer replaces only its own contribution, so `BlocklistStore.load()`/setters can no
longer drop DataStore-authored rules out of enforcement; JVM-tested in `core-model`); and
the **hidden Path B notification** (Android 13+'s `POST_NOTIFICATIONS` now surfaces as its
own blocking-health row in Settings — visibility-only, so it never moves the health level;
the degraded banner names it only when Path B is the path actually enforcing); and
**`DailyLimitTracker` having no caller** (Path B now enforces daily Time Limits:
`PathBTimeLimitGate` — JVM-tested decision + caching over `LimitStore`/`DailyLimitTracker`,
re-querying UsageStats at most every 15s, so a spent budget can block up to 15s late on this
fallback path — feeds `PathBBlockHandoff`, which applies Path A's exact precedence:
exemption → Time Limit → rules → Open Limits).

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
| `feature:feature-blocklist` | android-lib | Rule/limit editors, app picker, domain input (draft logic unit-tested) |
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
