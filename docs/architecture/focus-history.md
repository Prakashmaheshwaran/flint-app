# Focus history contract (v1)

Status: accepted for the v1 build (lane F). Applies to iOS and Android.
Aligned with ADR-012 §11 (`protection-contract.md`), which owns the canonical lifecycle
vocabulary and the durable enforcement outbox; this file is the focus-history projection.
Each platform keeps the wire names in **one** enum, and readers accept old and new names.

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
| `reason` | string | `failed_to_arm`, `ended_early`, `break_ended`, `protection_degraded`, optional elsewhere | Short machine reason (ADR-012 §11), e.g. `monitor_registration_failed`, `stop`, `toggle_off`, `deadline`. |
| `originRoute` | string | optional, additive | ADR-012 origin: `app_ui`, `shortcut`, `siri`, `focus_filter`, `automation`, `notification`, `shield_action`, `monitor`, `service`, `startup_reconciliation`, `migration`. |
| `observedAt` | int64 | optional, additive | Wall time the transition was observed (ADR-012 `sourceObservedAt`) when it differs from the logical `at`. |
| `timeConfidence` | object | optional, additive | `{"kind":"certain"}` or `{"kind":"uncertain","reason":"clock_discontinuity"}`. Confidence in effective time, independent of delayed observation or `inferred`. Reasons are non-identifying machine codes. |
| `kind` | string | `break_started` | `ordinary` or `planned` (precommitted Pomodoro rest). |
| `grant` | string | optional | Break/access grant or pass operation id. |
| `sessions` | [string] | `pass_used` | Session ids the pass affected (zero or more). |
| `recoveryEnd` | int64 | `pass_used` | End of the pass's release/recovery window. |

### Event types

| Type | Emitted when |
| --- | --- |
| `started` | A focus session's occurrence started after its arm prerequisites were durably accepted (ADR-012 §11: persisted alone is not armed). |
| `completed` | The session reached its **logical** deadline without an earlier terminal event; never written before that deadline. `at` = the deadline. |
| `ended_early` | The session stopped before its planned end, by `route`. |
| `superseded` | A new session replaced a still-running one (`by` = new id). |
| `break_started` / `break_ended` | A break inside the session began / ended (both platforms per ADR-012 §4; `kind`, `grant`). |
| `pass_used` | One global Emergency Pass operation (ADR-012 §6). `session` is the pass operation id; `sessions` lists affected sessions; `[at, recoveryEnd)` is subtracted from each listed session's focus. A session the pass ends also gets `ended_early(route: emergency_pass)`. |
| `protection_degraded` / `protection_restored` | A known coverage gap for this session began / ended; the gap is subtracted from focus. Permission loss is never a completion. |
| `access_started` / `access_ended` | Open-Limit credit interval (ADR-012 §5). Not focus sessions; preserved and ignored by focus aggregation. |
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
3. No terminal event, `plannedEnd != null`, `plannedEnd <= now` → **unconfirmed**
   at `plannedEnd`, including when a stale active-session identifier remains. A start and
   a planned deadline do not prove completion (R-17, ADR-012 §11 / PC-84).
4. No terminal event, not the current active session, `plannedEnd > now` or open-ended →
   **unconfirmed** at the observation time. Neither rule 3 nor rule 4 writes a terminal.
   A later durable owner/outbox terminal resolves the projection, idempotently.
5. A session with terminal events but no `started` is orphaned and contributes nothing.

## Focused time

For one session:

- Interval = `[started.at, end)` where `end` is the terminal event's `at`, clamped to the
  effective planned end when it is not `null`. Running sessions use `end = now` and are labelled
  "in progress" wherever shown.
- **Unconfirmed sessions credit zero time from a start alone.** Later session observations
  (`open_intercepted`, break boundaries, coverage loss/restoration) evidence only the prefix
  through their effective timestamp, clipped to the deadline and now. Subtract breaks and
  coverage gaps as below; never credit the unobserved tail. A reschedule only changes the
  plan and is not coverage evidence. Legacy inferred unknown early ends still credit zero.
- **Clock evidence is bounded before it is credited.** An explicitly uncertain start,
  plan, phase or terminal cannot supply elapsed duration. No trusted elapsed-duration field
  is currently carried, so retain only independently valid earlier coverage and leave the
  affected outcome unconfirmed. An observation at +121 minutes against a ten-minute plan
  cannot become ten minutes of evidence by clipping, even if its producer omitted confidence.
  Raw early terminals after the plan and terminals at/before the start are also unconfirmed.
  `observedAt` is never an elapsed-duration bound.
- Missing/null confidence preserves plausible legacy history: effective timestamps must
  agree with the actual start, effective plan and logical phase order. Legacy completion
  time equals its effective deadline; explicit certain completion can be later and is still
  bounded by the plan. Early exact-deadline ties remain readable. Explicit certainty does
  not admit impossible early timing. Outbox append order is not logical phase order: a break
  end delivered before its start is paired using its effective time and exact grant.
  Plan revisions use `(observedAt ?? at, append ordinal)`, so equal observation milliseconds
  preserve the last recorded revision rather than the largest effective timestamp.
  An inverted break/pass window is contradictory evidence even when its start is in-plan.
  Each ordinary revision must occur at/before the preceding accepted deadline and at/before
  its own new endpoint; exact-boundary changes and delayed observations remain valid.
  An invalid/uncertain later plan freezes extension and cannot erase a prefix already
  confirmed under an earlier trusted plan.
  Rejected time barriers take effect in original append order: an uncertain or invalid
  backdated timestamp cannot establish that it preceded an already recorded trusted
  witness, select that witness's plan, or subtract an earlier break/pass/gap from it.
  Accepted grant pairs and coverage cuts still use their logical effective order.
  Uncertain or contradictory evidence freezes later prefix extension; later cleanup after
  an accepted terminal does not invalidate that terminal. Accepted logical terminal duration
  is not truncated merely because a reader's wall clock subsequently moves backward.
- The durable policy owner may assert a final plan when history missed an edit. That is
  distinct from an ordinary revision: the final plan carries `originRoute: editor_terminal`,
  `reason: owner_final_plan` and certain time confidence, with its endpoint/effective time
  equal to a matching certain completed terminal from the same owner origin. Only that
  explicit pair may repair a stale earlier deadline. The public EditorTerminalEvent adapter
  supplies the assertion from the owner's logical endpoint when `timeUncertain` is false;
  uncertain owner finals remain unconfirmed and cannot extend the prior evidenced prefix.
  A matching certain owner pair supersedes rejected ordinary plan-only observations in
  either delivery order, without inventing an edit time. It does not override explicitly
  uncertain starts, plan times or coverage phases.
  Observation time is never substituted as proof of an edit or elapsed focus duration.
- Confidence is additive in schema v1. Old readers ignore the optional object; new readers
  treat unknown/malformed confidence as `uncertain(unsupported_time_confidence)`, retaining
  the original raw line through maintenance. `inferred` alone remains provenance rather
  than time uncertainty, so a valid recovered logical completion with a later observation
  still counts. Existing `reason: time_uncertain` is also conservatively recognized.
- Unconfirmed sessions never count as completed, ended early, or toward a completion
  streak. Count them separately on a valid expired planned-end day (otherwise the reader's
  observation day); a rejected raw terminal cannot move the warning onto another day.
  and show a localized “1 session couldn't be confirmed” note in the selected UI range.
- Subtract break intervals `[break_started.at, break_ended.at ?? breakEnd ?? end)`, pass
  windows `[pass_used.at, recoveryEnd)` for sessions listed in `sessions`, and coverage gaps
  `[protection_degraded.at, protection_restored.at ?? end)`, each clipped to the session
  interval. An observed `break_ended` takes precedence over the planned
  `breakEnd`, whether it is earlier or later.
- If `end < started.at` (wall clock moved backwards) the session contributes zero.
- Ended-early and superseded sessions **do** contribute the time actually spent before the
  end. Completed vs ended early is reported separately as a count.

Across sessions, focused intervals are **unioned** before summing so malformed overlapping
data can never double count.

Android accepts aggregate timing only within the same supported civil range as iOS
(years 1–9999). Persisted out-of-range Long values cannot prove a duration or completion.
Day splitting first intersects intervals with the requested display window, and duration
addition/subtraction is overflow-safe; a long accepted interval after reader rollback does
not require traversing all historical or future dates to render one week.

### Day attribution

- Split each focused interval at local midnights of the **current** device calendar/zone
  (`Calendar.current` / `ZoneId.systemDefault()`), using calendar day starts — never
  `86_400` second arithmetic — so DST days are 23 or 25 hours.
- A session spanning midnight contributes to both days.
- A session counts toward "completed on day D" on the day containing its end.
- Travel re-buckets history in the new zone. `tz` is kept for future refinement only.

### Streak (optional UI)

Number of consecutive local days, ending today — or ending yesterday when today has no
completed session yet — each with at least one recorded `completed` terminal (including
durable owner recovery/outbox events). Days before history start break the streak.

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
  so its completion still records. Android preserves the timestamp high-water mark across
  wall rollback and trims removed session IDs from retained shared pass records. Durable
  owner retries for removed identities are acknowledged without restoring those records;
  retained running STARTED identities may still receive late authoritative evidence.
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
one, `completed(previous)`. `didEnd(.completed)` must only be called at or after the logical
deadline (ADR-012 §4/§11; lane B owns timing — no early-callback allowance). Owners pass
`logicalEnd:` after clock changes; legacy callers default to `session.endsAt`. The event
mapping and durable sink preserve the accepted event time rather than substituting the
original wall plan or discarding an outcome when a reader's wall clock has moved back.
Clear keeps the running session by logical expiry, including through a same-boot wall jump.
All lifecycle hook families have additive `timeConfidence:` overloads, and the annotated
variants default it to nil. The public Swift shape is
`FlintEventTimeConfidence.certain` / `.uncertain(reason: String)`; `.annotated` carries it
separately from `observedAt`. Android history uses optional `HistoryEvent.timeConfidence`
with `HistoryEventTimeConfidence.Certain` / `Uncertain(reason)`, using the same wire object.
Emitters own truthful confidence and effective logical time; these consumer bounds do not
replace the lane B/C clock checks or invent a confidence level for plausible legacy data.
Proposed hook sites, for
lane B to accept or adapt: `FlintSessionController.startBlockNow` (after saving the active
session), `stop(route:)`, `finishIfExpired`, `useEmergencyPass`, the monitor's session
`intervalDidEnd` branch (before `clearActiveSession`), Shortcuts stop, Focus filter off,
and the registration-failure path. History's host reconciliation drains committed extension
events; it never manufactures an end from a missed callback or a missing active row.

### Android — observed transitions

The app process observes the public `ActiveRulesHolder.dataStoreState` read-time anchored
projection and rechecks it under the history transaction lock before admitting changes.
It diffs successfully read session-rule snapshots against history's open sessions: a new
**active** session rule → `started` (start instant from the `session-timer-<epochMs>-…` id,
else observation time; expired rules with no recorded start create nothing); a present rule
whose `expiresAt` differs from the effective planned end → `rescheduled`; a present, enabled
rule is never completed from an older planned end. For anchored rules, history persists the
owner's committed logical endpoint, shared with its terminal, rather than the live display
estimate (which can vary by a millisecond between wall/elapsed clock samples);
a session rule gone with `now >= expiresAt` stays unconfirmed until the lifecycle owner
records a terminal; gone earlier during live observation →
`ended_early(route: unknown)` at observation time — observed in-process, so this one
credits time to the observation instant. An owner-inactive occurrence instead awaits its
durable terminal; a stale future-looking raw expiry cannot recreate its STARTED after clear.
Unavailable owner reads preserve the last good active set and expose a diagnostic.
A cold-start missing row emits no terminal. The app consumes the editor owner's natural
Timer outbox on an independent IO loop, appending the authoritative final deadline and
terminal atomically before acknowledgement. Stable IDs make failed-ack replay idempotent.
Explicit
route, break, pass and `open_intercepted` emission needs one call each in
`BlockScreenCoordinator` / the rules store, added only with lane C's agreement.

### Android — durable pass and break projection

The app shell registers `ProtectionHistoryConsumer` with
`BlockEventHooks.registerDurableConsumer`. C owns the serialized outbox drain and exact-id
acknowledgement; F returns `true` only after `FocusHistoryStore.update` commits. A failed
write returns `false`, and cancellation propagates. Multi-record projections commit in one
atomic history rewrite; deterministic UUIDs derived from the owner event id, history type
and session id make replay after a crash before acknowledgement idempotent.

The projection also reads `HistorySnapshot.clearedAt` under that same update lock. After
a clear, lifecycle records may reference only sessions with retained or newly recorded
`started` records, even when a late event's wall time exceeds the clear cutoff. Cleared pass
references and early terminals are omitted together; a pass with no admitted sessions is
intentionally acknowledged without a new record. Break replay uses the same retained scope,
so a running session keeps its break pairing while cleared sessions do not return after a
failed acknowledgement or wall-clock rollback. This uses existing clear metadata, without
a new history schema or tombstone ledger.

A missing `started` may also be a new live session whose observer write has not committed.
For missing identities the adapter reads the same public anchored owner projection as the
rules observer, under the history update lock. Runtime `evaluation.active` decides liveness;
the stable committed history endpoint must not be compared with current wall time for that
decision. Raw expiry is used only for legacy rules without runtime metadata, and unprojected
runtime is unknown. A still-live identity, loading/unavailable
owner state or failed owner read defers the entire batch (`false`, no acknowledgement) until
STARTED arrives or removal becomes known. It never reconstructs a start. A global pass with
any undelivered live reference defers atomically, including references whose starts are
already retained; stable record IDs still make retry after a failed owner ACK harmless.

The rules observer carries public owner activity separately from the committed logical
`plannedEnd`. An active runtime remains eligible for STARTED admission and clear retention
after a forward wall-clock jump, while the logical endpoint and history outcome/coverage
calculations stay unchanged. Unprojected runtime is unknown; legacy rules without runtime
metadata retain their expiry-based fallback. Production clear reads fresh public owner
activity under the history lock and refuses unknown or failed reads without changing files
or marking history corrupt. It retains only active identities with STARTED and no terminal;
an old active ID cannot preserve a terminalled session.

The conservative admission rule also omits a genuinely new, ordinary-rule-only pass after
clear. With no focus identity, existing clear metadata cannot distinguish it from an old
unacknowledged operation; event wall time is not a safe freshness witness. This loses a
non-focus operation row, not focused duration or session counts, and does not change the
owner's committed pass decision. Complete post-clear zero-focus operation recording would
require a coordinated persisted clear/delivery watermark; v1 has no such witness.

- `PASS_COMMITTED` writes one `pass_used`, with the opaque operation id as `session` and
  `grant`. Its `sessions` contains only `session-*` ids from `affectedRuleIds`, and
  `recoveryEnd` is the owner's deadline. Only focus sessions in `endedTransientIds` receive
  `ended_early(route: emergency_pass)` in the same batch. Ordinary rules never become focus
  sessions. Before the first clear, a pass with no affected focus sessions still records
  one operation; after a clear the STARTED admission rule above applies.
- `BREAK_STARTED` / `BREAK_ENDED` write one record per affected focus session, carrying
  the same grant id. End reasons preserve `expired`, `resumed`, `superseded`, or `recovery`.
  The owner supplies the exact frozen session scope on both events; replay may persist an
  end before its start without looking up current rules or fabricating a start.
- The projection preserves the schema-1 wire vocabulary. Owner clock uncertainty is
  marked `inferred`; boot identity, elapsed clock, targets and non-session rule ids are
  excluded. No origin route is invented when the owner did not provide one.
- `ACCESS_STARTED` / `ACCESS_ENDED` are acknowledged without touching history, including
  when the history store is unavailable. Open-credit access is not a focus session or an
  ordinary break; it adds no records, counts or focused time.

The compatibility `Unit` listener records only genuine `open_intercepted` measurements.
It does not also record breaks or passes after durable registration. Natural Timer terminal
delivery is a separate owner outbox and app adapter. This consumer does not prove that every
production break boundary emits an event: start, deadline, resume, supersession and recovery
producer coverage must be verified in C's implementation. C also owns bounded-outbox loss
diagnostics. The app observes a positive `ProtectionStatus.lifecycleEventsDropped` or
`detectors.degradedReasons` containing `lifecycle-events-dropped` and marks history summaries degraded with the note "Some focus
events may be missing". That observation is sticky within the process; a later loading or
stale status cannot erase known loss, and C persists its counter across process restarts.
Existing totals stay unchanged; no events, loss start date or missing duration are invented.
The app also observes the public `EditorCommitmentStore.terminalDeliveryHealth` stream and
maps a ready `EditorTerminalDeliveryHealth.incompleteHistory` into that same missing-events
note. The owner derives it from `overflowDropped` or `captureFailures`; `pendingCount` and
retained `overflowCount` alone never imply loss. Loading/unavailable health cannot establish
a loss count, and history does not inspect the editor's private ledger.
The consumer never reads the private protection ledger or treats a missing event as evidence
of uninterrupted focus. Exact production emission and status-publication coverage remain
C-owned verification requirements.

## Notifications (local only)

- Copy (ADR-012 §11): a pre-scheduled alert says only that the **planned focus time has
  ended** (e.g. "Planned focus time ended"); it never claims committed completion or that
  anything was blocked. Delivery is not enforcement evidence.
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
- **FH-05** no terminal, planned end passed → unconfirmed, zero credit from a start alone,
  no completion/streak credit; stale active IDs do not change this.
- **FH-06** no terminal, not active, planned end in future → unconfirmed, zero credit.
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
- **FH-21** a global `pass_used` listing two sessions subtracts `[at, recoveryEnd)` from
  each; a `protection_degraded`/`protection_restored` gap is subtracted; `access_*` events
  change nothing.
- **R-17 / PC-84** killed sessions survive repeated cold reconciliation without a guessed
  terminal; late monitor/service outbox completion resolves once at its logical deadline.
  Positive coverage credits only its prefix minus breaks/gaps; expired starts remain zero
  across DST and local midnight, and widgets/Live Activity use the same public aggregate.
- **R-time-confidence** forward-jump witnesses and raw early terminals cannot inflate a
  finite plan; explicit uncertainty retains only prior trusted coverage, malformed/future
  confidence fails closed, legacy plausible rows remain readable, delayed logical completion
  and reader rollback remain valid, and grant replay uses logical order rather than delivery.
