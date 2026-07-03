# feature-stats — screen-time reports

Compose UI for Flint's screen-time report (the free counterpart of Opal's usage insights):
**today's total**, a **7-day day-by-day bar comparison** (plain Compose boxes — no chart
library), and **today's top apps** by foreground time, rendered as `core-model.UsageStat` rows.

## Entry point

```kotlin
com.flint.peakfocus.feature.stats.StatsScreen(modifier: Modifier = Modifier)
```

Self-contained (owns its ViewModel via `StatsViewModel.factory`); the A-VERIFY integrator wires
it into MainActivity navigation — this module does not touch the nav graph.

## How it works

```
UsageStatsManager.queryEvents(last 7 local days)          android edge
  └─ UsageStatsSource  — maps ACTIVITY_RESUMED/PAUSED → ForegroundEvent,
     builds local-midnight DayWindows (Calendar), resolves app labels,
     excludes Flint's own package (the block overlay shouldn't count)
       └─ UsageAggregator.bucketIntoDays(...)             pure Kotlin (JVM-tested)
          single-foreground-app interval pairing; implicit close when
          another app resumes; open interval closed at `now`; sessions
          split at midnight so they count toward both days
            └─ UsageReport → StatsViewModel (StateFlow<StatsUiState>) → StatsScreen
```

- **Why events, not `queryUsageStats(INTERVAL_DAILY)`:** daily `UsageStats` buckets are
  system-chosen and can straddle local midnight; event bucketing gives true per-day numbers.
  The single-package "today" helpers (`core-data.UsageQuery`,
  `blocking-usagestats.DailyLimitTracker`) stay the canonical source for limit *enforcement*;
  this module only feeds the report UI, so there is no duplicated enforcement logic.
- **Usage Access handling:** `StatsViewModel.refresh()` re-checks
  `permissions-special.UsageAccess.isGranted` on every ON_RESUME (same pattern MainActivity
  uses for the accessibility grant). Not granted → `StatsUiState.PermissionRequired`, a
  friendly explainer whose button fires `UsageAccess.settingsIntent()`; returning from
  Settings updates the screen without a manual refresh.
- **Tokens only:** all colors/typography/shapes come from `FlintTheme` / `MaterialTheme` —
  no hard-coded values. Local-only, no telemetry, no network (ADR-002).

## Known limits (honest)

- Some OEMs retain usage events for only a few days — the oldest bars can read low/zero even
  though the phone was used. Stock Android retains events well past 7 days.
- `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED` (API 29 names) are numeric aliases of the legacy
  `MOVE_TO_FOREGROUND`/`MOVE_TO_BACKGROUND` (values 1/2, API 21+) — compile-time inlined,
  safe at `minSdk 23`; same pattern as `blocking-usagestats.UsagePoller`.
- An app already foreground when the 7-day window opens is only counted from its first event
  inside the window (negligible at a 7-day horizon).

## Verification status (honest)

- Pure-JVM unit tests cover the aggregation (midnight splits, implicit closes, clamping,
  ordering) and formatting: `UsageAggregatorTest`, `DurationFormatTest`, `UsageReportTest` —
  they run under `make android-test` (`gradlew testDebugUnitTest`).
- **Written in a fleet worktree without an Android toolchain — not yet compiled or run here.**
  `make android` (the A-STATS verify gate) and `make android-test` must pass at merge time.
- Real usage numbers need the Usage Access grant on a device/emulator; the permission-prompt
  and report states are emulator-verifiable once A-VERIFY wires the screen into navigation.
