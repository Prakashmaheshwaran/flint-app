# Focus history contract (v1)

Status: accepted for the v1 build (lane F). Applies to iOS and Android.
Event names are provisional until ADR-012 (`protection-contract.md`) fixes the lifecycle
vocabulary; each platform keeps the wire names in **one** enum so a rename is a mapping
change, and readers accept both old and new names.

## Purpose and boundaries

Flint records a small, local, append-only log of **focus-session lifecycle events** so
Today, Complete and Insights can show truthful focused totals. The log:

- stays on the device. No network, no export, no analytics, no account.
- contains **no app, package, bundle, token, category or domain identifiers**. Only the
  session id, times, tier, source, a user-facing session name and outcome data.
- never contains Apple `DeviceActivityReport` data. Screen-time totals stay inside the iOS
  report extension; focus history is a separate, app-owned record.
- covers **focus sessions the user starts**: iOS Block Now (Today sheet, presets,
  Shortcuts, Pomodoro intent, Focus filter) and Android Timer sessions (`session-timer-*`)
  plus legacy `session-*` rules. Recurring schedules, Sleep, Time Limits and Open Limits are
  rules, not sessions; they are **not** counted as focused time in v1, and UI copy must say
  "focus sessions" rather than implying all protection time.

## Event record

One JSON object per event. Both platforms use the same field names.

| Field | Type | Present on | Meaning |
| --- | --- | --- | --- |
| `v` | int | all | Record schema version. This contract is `1`. |
| `id` | string | all | Unique event id (UUID). |
| `type` | string | all | One of the event types below. |
| `session` | string | all | Session id (iOS `FlintActiveSession.id`, Android `BlockRule.id`). |
| `at` | int64 | all | Epoch milliseconds (UTC wall clock) the event takes effect. |
| `tz` | string | all | IANA zone id at emission. Diagnostic only; aggregation uses the current zone. |
| `inferred` | bool | optional | `true` when written by reconciliation rather than observed at the transition. |
| `plannedEnd` | int64? | `started`, `rescheduled` | Planned end; `null` for open-ended sessions. |
| `tier` | string | `started` | `easy`, `harder` or `hardcore`. |
| `source` | string | `started` | `manual`, `preset`, `shortcut`, `pomodoro`, `focus_filter`, `legacy`, `unknown`. |
| `name` | string | `started` | User-facing session name (e.g. "Focus", "Deep study"). Max 64 chars. |
| `route` | string | `ended_early` | `manual_stop`, `harder_wait`, `emergency_pass`, `shortcut_stop`, `focus_filter_off`, `hold_to_end`, `cancel`, `deleted`, `disabled`, `unknown`. |
| `by` | string | `superseded` | Id of the session that replaced this one. |
| `breakEnd` | int64 | `break_started` | Planned break end. |
| `reason` | string | `failed_to_arm` | Short machine reason, e.g. `monitor_registration_failed`. |

### Event types

| Type | Emitted when |
| --- | --- |
| `started` | A focus session became active (persisted as the active session / rule). |
| `completed` | The session reached its planned end without an earlier terminal event. `at` = planned end. |
| `ended_early` | The session stopped before its planned end, by `route`. |
| `superseded` | A new session replaced a still-running one (`by` = new id). |
| `break_started` / `break_ended` | A break inside the session began / ended (Android only in v1). |
| `pass_used` | An Emergency Pass was spent while this session was active. Does not by itself end the session; iOS also emits `ended_early(route: emergency_pass)`. |
| `failed_to_arm` | Starting or arming the session's out-of-app teardown failed. Diagnostic; does not end the session. |
| `rescheduled` | A still-running session's planned end changed in place (same session id). Carries the new `plannedEnd`. Name/tier-only edits emit nothing. |
| `open_intercepted` | Android only: a block surface was shown for a new foreground open attempt during this session. |

Unknown `type` values (from a newer build) are preserved on compaction and ignored by
aggregation. Records with `v` greater than the reader supports are preserved and ignored.

## Session outcome

For each session id, group its events and sort by `at` (ties: file order). The session's
**effective planned end** is the `plannedEnd` of its latest `started`/`rescheduled` event
that precedes the terminal event; every rule below uses it.

1. Outcome = the **first terminal event** among `completed`, `ended_early`, `superseded`.
   Later terminal events are duplicates and are ignored (e.g. the monitor wrote `completed`
   and a host reconcile later wrote another). Writers may emit duplicates; readers must be
   idempotent.
2. No terminal event and the session is the platform's current active session with
   `now < plannedEnd` (or open-ended) → **running**.
3. No terminal event, `plannedEnd != null`, `plannedEnd <= now` → **completed (inferred)**
   at `plannedEnd`. The reconciler may persist this as `completed` with `inferred: true`.
4. No terminal event, not the current active session, `plannedEnd > now` or open-ended →
   **ended early, unknown route (inferred)** at the observation time. The reconciler may
   persist it as `ended_early(route: unknown, inferred: true)`.
5. A session with terminal events but no `started` is orphaned and contributes nothing.

## Focused time

For one session:

- Interval = `[started.at, end)` where `end` is the terminal event's `at`, clamped to the
  effective planned end when it is not `null`. Running sessions use `end = now` and are labelled
  "in progress" wherever shown.
- **Inferred unknown ends (rule 4) credit zero focused time.** Flint cannot prove when such a
  session actually stopped, so it under-reports rather than invents time.
- Subtract break intervals `[break_started.at, break_ended.at ?? breakEnd ?? end)` clipped
  to the session interval. An observed `break_ended` takes precedence over the planned
  `breakEnd`, whether it is earlier or later.
- If `end < started.at` (wall clock moved backwards) the session contributes zero.
- Ended-early and superseded sessions **do** contribute the time actually spent before the
  end. Completed vs ended early is reported separately as a count.

Across sessions, focused intervals are **unioned** before summing so malformed overlapping
data can never double count.

### Day attribution

- Split each focused interval at local midnights of the **current** device calendar/zone
  (`Calendar.current` / `ZoneId.systemDefault()`), using calendar day starts — never
  `86_400` second arithmetic — so DST days are 23 or 25 hours.
- A session spanning midnight contributes to both days.
- A session counts toward "completed on day D" on the day containing its end.
- Travel re-buckets history in the new zone. `tz` is kept for future refinement only.

### Streak (optional UI)

Number of consecutive local days, ending today — or ending yesterday when today has no
completed session yet — each with at least one `completed` session (inferred completions
count). Days before history start break the streak.

## Absent versus zero

The store reports a `historyStart` timestamp = max(store creation, last clear).

| Situation | UI shows |
| --- | --- |
| Store unreadable and nothing salvageable | "Focus history unavailable" — never `0` |
| Some lines quarantined | Totals from readable events plus a quiet "Some history couldn't be read" note |
| No sessions ever since `historyStart` | Empty state ("No focus sessions yet") |
| Day on/after `historyStart` with no focus | `0m` (a true zero) |
| Day before `historyStart` | No bar / "no data", never `0` |

Never convert screen-time or usage data into focus time, "time saved" or "opens stopped".
"Opens stopped" is shown only where measured: Android `open_intercepted` events. iOS
omits it in v1 (a shield-button tap is not an open count).

## Storage rules

- **Append-only** JSON Lines file, one record per line, UTF-8, newline-terminated. Metadata
  (`{"v":1,"createdAt":…,"clearedAt":…}`) lives in a sibling file.
- **Atomic, cross-process-safe writes.** Appends write one complete line per write call
  under the platform lock; compaction and clear write a temp file and atomically replace.
  - iOS: file in the App Group container (`FlintHistory/`), usable from the host app and
    the DeviceActivity monitor extension. Coordinate with `NSFileCoordinator` (or an
    equivalent advisory lock); hold no lock across suspension; explicit file protection
    `completeUntilFirstUserAuthentication` so the monitor can append after first unlock.
    The extension only appends; it never reads the whole log.
  - Android: app-private files dir (`focus-history/`), single process, a process-wide
    mutex, `AtomicFile` (or temp + rename) for rewrites, flushed and synced appends.
- **Bounded retention**: compaction drops sessions whose last event is older than 400
  days, keeping a session's events together. Run at most once per day from the app, and
  when the file exceeds 1 MiB. Hard cap 5 MiB: drop oldest sessions first.
- **Corruption**: an unparseable line is skipped, the original file is copied once to
  `quarantine/<epochMs>.jsonl` (keep at most 3), and the next compaction rewrites without
  bad lines. A truncated last line (crash mid-append) is treated the same way. Reading,
  writing and compaction never crash the app or an extension; failures are swallowed into
  a diagnostic and surface as the absent/degraded states above.
- **Clear history** (user action in You/Settings, with confirmation): replaces the log with
  an empty one, sets `clearedAt`, and keeps only the events of a currently running session
  so its completion still records.
- **Persisted-data compatibility**: existing session/rule stores are not changed. Upgrading
  users get `historyStart` = first launch of a build with this store; earlier days show "no
  data". The legacy iOS `flint.sessions` key is left untouched.

## Emission API

Lifecycle transitions live in lane B (iOS) and lane C (Android) files. Lane F owns a tiny
emission surface; lifecycle owners add one call per transition.

### iOS — `FlintSessionLifecycle` (FlintCore)

Static, non-throwing, cheap; fans out to the history store and the completion notifier.

```swift
FlintSessionLifecycle.didStart(_ session: FlintActiveSession,
                               replacing previous: FlintActiveSession?,
                               source: FlintSessionSource)
FlintSessionLifecycle.didEnd(_ session: FlintActiveSession,
                             route: FlintSessionEndRoute,   // .completed or an early route
                             at: Date = Date())
FlintSessionLifecycle.didUsePass(_ session: FlintActiveSession)
FlintSessionLifecycle.didFailToArm(_ session: FlintActiveSession, reason: String)
```

`didStart` with a non-expired `previous` emits `superseded(previous, by:)`; with an expired
one, `completed(previous)`. `didEnd(.completed)` from the monitor's `intervalDidEnd` uses
`at: session.endsAt` and is emitted only when the end is within 120 s of `endsAt`
(DeviceActivity truncates the schedule end to whole seconds). Proposed hook sites, for
lane B to accept or adapt: `FlintSessionController.startBlockNow` (after saving the active
session), `stop(route:)`, `finishIfExpired`, `useEmergencyPass`, the monitor's session
`intervalDidEnd` branch (before `clearActiveSession`), Shortcuts stop, Focus filter off,
and the registration-failure path. The host reconciles on launch/foreground (rules 3–4)
so a missed extension write still produces a truthful outcome.

### Android — observed transitions

The app process observes `BlockRulesStore.rules` (as `ActiveRulesBridge` already does) and
diffs successfully read session-rule snapshots against the history's open sessions: a new
**active** session rule → `started` (start instant from the `session-timer-<epochMs>-…` id,
else observation time; expired rules with no recorded start create nothing); a present rule
whose `expiresAt` differs from the effective planned end → `rescheduled`; a present, enabled
rule is never completed from an older planned end;
a session rule gone with `now >= expiresAt` → `completed` at `expiresAt`; gone earlier →
`ended_early(route: unknown)` at observation time — observed in-process, so this one
credits time to the observation instant, unlike rule 4's cold-start inference. Explicit
route, break, pass and `open_intercepted` emission needs one call each in
`BlockScreenCoordinator` / the rules store, added only with lane C's agreement.

## Notifications (local only)

- iOS: optional permission requested from onboarding (E02) and You; never required. On
  `didStart` with a planned end and authorization granted, schedule one local notification
  (`flint.session.complete.<sessionId>`) at `endsAt`; cancel it on early end or supersede.
- Android: a "Focus sessions" channel; post at the planned end via an inexact
  `AlarmManager` alarm (no exact-alarm permission); cancel on early end; respect the
  existing `POST_NOTIFICATIONS` handling and never block a session on permission.

## Acceptance cases (both platforms implement these as unit tests)

- **FH-01** started + completed → completed, full planned duration on the start day.
- **FH-02** started + ended_early at half time → ended early, half duration credited.
- **FH-03** duplicate `completed` (monitor + reconcile) → counted once.
- **FH-04** `completed` then a later `ended_early` → completed wins.
- **FH-05** no terminal, planned end passed, not active → completed (inferred).
- **FH-06** no terminal, not active, planned end in future → ended early (unknown), zero credit.
- **FH-07** session 23:30–00:30 local → 30 min on each day.
- **FH-08** DST spring-forward day (America/New_York 2026-03-08) — 01:30–03:30 local is 1 h of
  focus, and the day is 23 h long; fall-back (2026-11-01) 00:30–02:30 local is 3 h.
- **FH-09** time-zone change after recording re-buckets by the current zone.
- **FH-10** overlapping sessions union to the covered time.
- **FH-11** break inside a session subtracts the break interval.
- **FH-12** corrupt line and truncated final line → skipped, file quarantined once, others read.
- **FH-13** unknown event type / higher `v` → ignored by aggregation, preserved by compaction.
- **FH-14** retention drops sessions whose last event is older than 400 days, never half a session.
- **FH-15** clear history keeps a running session's events; `historyStart` moves to the clear time.
- **FH-16** day before `historyStart` → no data; day after with no sessions → zero.
- **FH-17** superseded session credits time until the supersede; the new session starts fresh.
- **FH-18** `end < start` (clock moved back) → zero credit, no crash.
- **FH-19** streak: completions today and the previous two days → 3; none today → counts from yesterday.
- **FH-20** `rescheduled` extends/shortens the effective planned end; completion and clamping
  use the latest value; name/tier-only edits change nothing.
