# feature-stats — screen-time reports

Compose UI for Flint's screen-time report (the free counterpart of Opal's usage insights):
**today's total**, a **7-day day-by-day bar comparison** (plain Compose boxes — no chart
library), and **top apps for Today, any selected day, or the Last 7 days**, rendered as
`core-model.UsageStat` rows.

Tap a chart day to inspect that date's apps; the Today and Last 7 days chips select relative
periods. Rankings show the eight heaviest apps. Percentages use all recorded usage in the
selected period, including apps outside those eight. Whole-week rankings aggregate every app
before truncation, so an app can rank for the week even if it never appears in a daily top eight.
Selected dates use full localized labels and accessibility descriptions. A selected date survives
report refreshes and day rollover while it remains in the window; otherwise the view returns to
Today. Today and Last 7 days always follow the current report window.

## Entry point

```kotlin
com.flint.peakfocus.feature.stats.StatsScreen(modifier: Modifier = Modifier)
```

Self-contained (owns its ViewModel via `StatsViewModel.factory`); MainActivity renders it in
the Stats tab. This module does not own the nav graph.

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
            └─ buildUsageReport — date labels, daily and whole-week rankings
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
  though the phone was used. An empty day means no records were returned; it does not prove
  the phone was unused. Empty states surface this limitation. Flint does not create a new
  persistent usage archive or request additional permissions for historical insights.
- `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED` (API 29 names) are numeric aliases of the legacy
  `MOVE_TO_FOREGROUND`/`MOVE_TO_BACKGROUND` (values 1/2, API 21+) — compile-time inlined,
  safe at `minSdk 23`; same pattern as `blocking-usagestats.UsagePoller`.
- An app already foreground when the 7-day window opens is only counted from its first event
  inside the window (negligible at a 7-day horizon).

## Verification status (honest)

- Pure-JVM unit tests cover aggregation (midnight splits, implicit closes, clamping,
  ordering), formatting, period selection, ranking, percentage denominators, and refresh/rollover
  behavior: `UsageAggregatorTest`, `DurationFormatTest`, `UsageReportTest`, and
  `HistoricalInsightsTest`. They run under `make android-test` (`gradlew test`).
- On 2026-10-11, the historical insights module passed 45 debug unit tests (10 new), and
  `make verify-android` passed. An API 35 emulator pass verified selecting a past day, retaining
  that date after background/resume, and showing weekly app rankings with period percentages.
  [Screenshots, logs, and limits](../../../docs/verification/android-historical-insights-2026-10-11/README.md).
- Real usage numbers require Usage Access. The selected historical day had no emulator records;
  populated historical-day math and rollover are unit-tested, not physical-device verified.
