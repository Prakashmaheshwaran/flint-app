# Pomodoro chain (F01): design addendum to ADR-012

Status: proposed with the F01 iOS and Android pull requests (2026-11-11).

This addendum refines `protection-contract.md` §1 (Pomodoro cause), §2/§3 (tier actions and replacement), §4 (planned rests),
§9 (trusted time) and §11 (lifecycle events) for the repeated focus/rest chain. It grants no
new right, adds no weakening path and changes no tier rule. Where this file and the
contract disagree, the contract wins.

## 1. Decision: one parent commitment with a frozen phase plan

A Pomodoro is **one parent commitment**: one iOS `FlintActiveSession` or one Android
Timer `BlockRule` with one editor cause. Its committed duration covers the whole chain,
from the first focus round to the end of the last focus round. At acceptance it carries a
frozen **plan** (focus length, rest length, rounds, and an optional long rest). The
current phase is a pure function of the parent's **trusted logical elapsed time**.

We rejected the alternative of chained sessions, where each round is its own session and
the next one starts at each boundary:

- §1 defines the Pomodoro cause as a single parent whose tier covers the whole chain,
  planned rests included.
- Each round would create its own `started` and `completed` events. History would count
  the chain several times, and nothing would record the chain as one outcome (§11: "The
  Pomodoro parent completes once").
- When the app is not running, the monitor or service would have to *start* a new
  commitment at every boundary. A start needs arming prerequisites, Hardcore confirmation
  and replacement arbitration, and a background callback can supply none of these.
- Between rounds nothing would hold the tier. A Hardcore chain could be shortened just by
  the next round failing to start.
- On iOS, every round would register and retire DeviceActivity activities, which wears
  down the small shared registration pool.

## 2. Plan

| Field | Bounds (both platforms) | Default |
| --- | --- | --- |
| `focusMinutes` | 5–120, in steps of 5 | 25 |
| `restMinutes` | 1–60 | 5 |
| `rounds` | 2–8. A single round is an ordinary Timer or session. | 4 |
| `longRestMinutes` | optional, 5–60, and at least `restMinutes` | none |
| `longRestEvery` | optional, 2…`rounds − 1`. Both long-rest fields are set, or neither. | none |

The phases run in this order: `F1, R1, F2, R2, …, F(rounds)`. There are `rounds` focus
phases and `rounds − 1` rests, and the chain ends after the last focus round (§1). The rest
after focus round `k` (1-based) is a long rest when `longRestEvery` is set and
`k % longRestEvery == 0`. The total is at most 8×120 + 7×60 = 1,380 minutes. That is below
the §1 24-hour parent cap and below iOS's 23 h 59 m registration limit, but each platform
still validates its own cap.

`phaseAt(elapsed)` uses half-open intervals `[start, end)`. It returns the phase index,
whether the phase is focus or rest, the 1-based focus round number (for a rest, the round
it follows), and the phase's start and end as elapsed offsets. At `elapsed >= total` it
returns `finished`. Negative input is clamped to 0. Both platforms implement the same
function and test the same vectors:

| Plan | Elapsed (min) | Result |
| --- | --- | --- |
| 25/5 × 4, no long rest (total 115) | 0 | F1 `[0, 25)` |
| | 24.99 | F1 |
| | 25 | R1 `[25, 30)` (rest after round 1) |
| | 30 | F2 `[30, 55)` |
| | 114.99 | F4 `[90, 115)` |
| | 115 | finished |
| 25/5 × 4, long 15 every 2 (total 125) | 55 | R2 `[55, 70)`, long |
| | 70 | F3 `[70, 95)` |
| | 95 | R3 `[95, 100)`, short |
| | 125 | finished |

## 3. Trusted time (§9, PC-63)

- Elapsed time is `committedDuration − logicalRemaining`. On iOS this is
  `FlintActiveSession.logicalRemaining(clock:)`. On Android it is the editor cause's
  `AnchoredInterval.remainingMs(now)`. Phases are never derived from wall time.
- **Same-boot wall clock change:** elapsed time does not change, so round boundaries stay
  put. Only displayed wall instants and OS registrations are recomputed.
- **Process kill:** the phase is re-derived from durable anchors. No new `started` event
  is written, and no round restarts.
- **Reboot:** the §9 bounded rule `min(savedRemaining, max(0, wallEnd − nowWall))` means
  elapsed time never decreases. A completed round never restarts and a rest never gets
  longer. The bounded reboot trade-off can only move the chain forward, as for any finite
  session, and it records `time_uncertain`.

## 4. Enforcement during planned rests (§4, PC-27)

During a planned rest, the parent's **own** restrictions stop enforcing. Every other rule,
limit and session keeps enforcing. The parent stays an *active commitment* (§1) for the
whole chain, so its tier still governs stopping, replacing and editing during a rest.

### iOS

- The session's ManagedSettings store, including its Safari restrictions, is cleared while
  a rest is in progress and rewritten when focus resumes. Shield writes derive the phase
  under the session transition lock.
- **Carriers:** each planned rest gets its own DeviceActivity carrier, named
  `flint.pomo.<parentToken>.<restIndex>`. `parentToken` is the parent's `monitorName`
  token. The `flint.pomo.` prefix is deliberately outside `isSessionActivity`.
  - Interval: from the rest's start to `max(rest end, rest start + 15 min)`.
  - A rest shorter than 15 minutes gets `warningTime = 15 − restMinutes`, using the §4
    deadline adapter, so the warning lands on the logical rest end.
- **Callbacks:** `intervalDidStart`, `intervalWillEndWarning` and `intervalDidEnd` only
  trigger a phase reconcile. The monitor checks that the carrier's token matches the
  current parent; a carrier that does not match is stale and is stopped. The monitor then
  derives the logical phase and writes the shield to match. A callback never acts on its
  own name:
  - An early `intervalDidStart` during focus leaves the shield on.
  - A late or missed callback can only delay a rest (fail-closed). The exception is the
    clock-back case below.
- **Pre-registration (§4 "pre-register/validate every required phase"):** every rest
  carrier and the parent's end carrier must be registered and observed before acceptance
  commits.
  - If iOS refuses any of them, every carrier this attempt registered is stopped and the
    start fails with `armingFailed` and a `failed_to_arm` event.
  - The start is also refused when `registered + needed > FlintArmingHealth.observedActivityThreshold − 2`.
    This keeps room for break, credit and recovery carriers.
- **Teardown:** every terminal transition (`completed`, `ended_early`, pass, `superseded`,
  failed apply) stops all of the parent's rest carriers.
- **Clock changes:** after a discontinuity or a time zone change, the host reconcile
  re-registers the remaining future rest carriers from logical times, within the existing
  clock-rearm budget.
- **Known limit (device gate):** if the wall clock moves backward during a rest while the
  host is not running, the rest-end callback can arrive late. The shield returns at the next
  callback or host reconcile. Ordinary-break carriers have the same limitation.

### Android

- In `RuleClockProjection`, a rule with a Pomodoro plan and an anchored interval evaluates
  to:
  - `active` = the current phase is focus;
  - `endsAtEpochMs` = the chain end;
  - `nextBoundaryEpochMs` = `now + (phaseEnd − elapsed)`.
- The existing boundary timer re-evaluates the foreground app at each phase boundary
  without needing an app switch. Both detection paths read the same projection.
- The plan is persisted with the rule, so a process restart replays the same desired
  enforcement.

## 5. Rights (ADR-012 tiers, PC-27)

- **Tier:** the tier applies to the whole parent. Ending the chain early uses the tier's
  ordinary §2 stop path: Easy asks for confirmation; Harder runs the persisted wait and then
  asks for confirmation; Hardcore refuses.
- **No other weakening operations:** there is no skip-round, skip-rest, extend-rest,
  shorten-chain or edit-plan operation, because the plan is frozen at acceptance. §4 already
  treats skipping or lengthening a focus round as weakening, and extending a rest weakens
  too.
- **Hardcore:** no ordinary path ends or shortens a Hardcore chain. Its exits are natural
  end and the Emergency Pass. The pass ends the parent with `ended_early(emergency_pass)`
  and cancels every future round (§6 step 1). The safety floor is unchanged.
- **Replacement:** a new start during a chain is arbitrated against the parent's tier,
  frozen targets and chain end through the existing §3 replacement path. Arbitration treats
  the parent as enforcing for the whole chain, which can only make dominance harder to
  prove, never easier. On Android, starting a second Timer is still refused.

## 6. Planned rests compared with ordinary breaks (§4)

| | Planned rest | Ordinary break |
| --- | --- | --- |
| Origin | Part of the frozen plan, disclosed in the start confirmation | A user request during a commitment |
| Tier gate | None: it is part of the commitment, including at Hardcore | Easy: confirm. Harder: wait. Hardcore: not available |
| Harder counter `n` | Not consumed | Consumed on commit |
| Scope | The parent's own targets only | The requested targets across all contributors |
| User control | Cannot be requested, extended, shortened, skipped or stacked | "Resume now"; no renewal |
| History | `break_started`/`break_ended`, kind `planned`, grant `<parentID>.rest<k>` | kind `ordinary` |

During a focus round, an ordinary break is still available under the parent's tier rules.
It never moves the chain's boundaries: the chain runs on elapsed time and does not pause.
During a rest the parent is not an enforcing contributor, so it neither grants nor blocks an
ordinary break for other rules (see `FlintBreakScope`).

## 7. History and round outcomes (§11, PC-64, PC-84)

- Each parent records one `started` event (source `pomodoro`, `plannedEnd` = chain end) and
  at most one terminal event. Both histories already ignore unknown fields. The additive
  `rounds` field on `started` records the planned round count.
- **Each rest:** a `break_started` event (kind `planned`, grant `<parentID>.rest<k>`,
  `breakEnd` = logical rest end) and a `break_ended` event. These are written only when a
  monitor, host, service or reconciler observes the transition.
  - A late observation records the logical boundary time, with `inferred = true` and its
    `observedAt`.
  - A reboot-bounded observation is marked as uncertain time.
  - Duplicate callbacks and processes deduplicate on the stable grant/event identity.
- **Focus totals:** the existing aggregators subtract break intervals, so rest time never
  counts as focus time.
- **Round outcomes:** an observed planned rest `k` proves that rounds 1…k ended, and the
  parent's `completed` proves all of them. A deadline alone stays Unconfirmed or Running, as
  for any plan, and earns no round, completion or streak credit. On an early end, the
  in-progress round is not credited as a round, but its evidenced minutes still count as
  focus time, as for any early-ended session.
- **Notifications:** the parent completion notification is unchanged: it follows a
  committed terminal outcome, or is pre-scheduled as "planned focus time ended" and
  cancelled on an early end. This slice adds no round-transition notifications.

## 8. Glance, widgets and Live Activity

The status snapshot gains these fields for a Pomodoro parent:

- `phase` (focus or rest);
- `round` and `rounds`;
- `phaseEndsAt` (the wall estimate of the logical phase end).

Readers show "Focus · round 2 of 4" or "Rest · focus resumes 10:25". A reader that is
past `phaseEndsAt` without a newer publication shows the phase as unavailable, never a
stale focus or rest claim. A surface that cannot show phases truthfully shows only the
parent ("Pomodoro", chain end) and makes no claim about the phase.

## 9. Verification scope

- **Unit tests:**
  - phase vectors;
  - elapsed time under forward/backward wall changes and bounded reboot;
  - enforcement during rests, with other rules left untouched;
  - iOS carrier pre-registration, rollback and teardown;
  - Hardcore early end denied, and the Emergency Pass ending the chain;
  - history round counting and rest subtraction.
- **Not verified:**
  - physical-device DeviceActivity delivery of rest carriers;
  - locked-device latency;
  - a backward clock change while the host is not running;
  - Android OEM alarm and accessibility timing on hardware.
