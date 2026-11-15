# Red-team re-verification of source-closed residuals — 2026-11-11

**Pinned source: `origin/main` `b88808478ba1cbbd17cf631f162c259b7962c8e6`** ("Merge pull request #246"). This pass re-attacks the red-team table rows in [the capability ledger](../acceptance/v1-capability-ledger.md) that were merged as "SOURCE-CLOSED (not re-verified by red-team)". It uses the original findings in the [audit](adr-012-redteam-2026-10-28.md), [pass 1](adr-012-redteam-verification-2026-10-29.md), [pass 2](adr-012-redteam-verification-pass2-2026-10-30.md) and [ADR-012](../architecture/protection-contract.md).

In scope: R2p2-N02 (#228), including the double-fault, due-queue and default-seam follow-ups; R2p2-N03, R2p2-N01 and R-16 (#247); R-30 (#249); R-07, R-20 and R-04 (#251); and R-18b (#225, #250, #254). R-28 is skipped, because the one-shot feature PR #257 is changing it.

**Result.** Four residuals reopen on sub-paths the merged change did not cover; the other five hold:

| Residual | Verdict | Fix PR |
| --- | --- | --- |
| R2p2-N03 | REOPENED (two never-used paths) | #262 |
| R-04 | REOPENED (crash-state recovery) | #263 |
| R2p2-N02 | REOPENED (double-fault follow-up) | #264 |
| R2p2-N01 | REOPENED (stale looser-definition evidence) | #265 |
| R-30 | PARTIAL (one display surface) | — |
| R-07, R-20, R-16, R-18b | RE-VERIFIED CLOSED for their reported paths | — |

The original reproduction stays closed for eight residuals. The exception is R2p2-N03: its finding named a second case ("or valid legacy install which never used Open") that #247 never covered. The other reopened sub-paths are variants, double faults or crash states, and R2p2-N02's is one of the follow-ups recorded with #228. Several reported failures were not counted, because they assume a state that no released build or production code can reach. Each is listed with its reason under [Not counted](#not-counted-as-holes).

## Method and evidence rules

- **One child per residual.** Ten adversarial child tasks ran on OpenCode DeepSeek V4.1 Flash Ultrafast (variant `high`), each in its own worktree at `b888084`; R-18b was split into the #225 core and the #250/#254 follow-ups. Each child got:
  - the original finding text;
  - the merged PR's code diff (docs and ledger hunks removed, so the "closed" conclusion was withheld);
  - the relevant ADR-012 sections.

  Each was asked to break the fix, including through other entry points, ordering and double faults, process death, clock and zone changes, and legacy-migration states.
- **What counts as a hole.** A failing XCTest/JUnit/Robolectric test on `b888084`. Reasoning alone is **SUSPECTED**. The orchestrator re-checked every failing test's premise against production code before counting it. For every counted hole, the failing test was re-run on unchanged `b888084` source under the orchestrator's own lock (owner `redteam-orch`) before the fix was applied. The one exception is #264: there the test needs the fix's injectable ledger seam, so the fail is shown by disabling the fix's ledger lookup instead (see its section).
- **Runners.** All tests ran under the shared `flint-lock` locks on this Mac: the iOS simulator iPhone 17 (`284643C6-B980-454C-9FF6-7FF2A863AC46`, iOS 27.0) and JVM/Robolectric for Android.
- **No physical-device claims.** No physical device, emulator UI or signed build was used. Anything only a device can decide is **DEVICE-PENDING**.
- **Delegation failure.** At about 15:30 EST the OpenCode gateway refused further requests ("Your account has no available balance. The API key is suspended for billing."). The consequences:
  - The independent reviews of #263 and #264 failed to start.
  - The R-16 and R-18b follow-up children stopped before writing their final reports. For R-18b, the orchestrator read the JUnit results of the child's own locked run. For R-16, whose run had not started, the orchestrator ran the child's test classes itself under the lock. It judged each failing test directly in both cases. The R-30 child's report arrived later and agrees with the orchestrator's reading of its results.
  - No other model was substituted.

## Verdicts at `b888084`

| Residual (merged change) | Verdict | Sub-path results |
| --- | --- | --- |
| R2p2-N02 (#228), iOS | **REOPENED (double fault); original CLOSED** | Original successor overwrite, default seam, due queue, clock/zone/reboot: RE-VERIFIED CLOSED. Unavailable break context with an absent or undecodable session projection: REOPENED; break released until session end. Fix [#264](https://github.com/Prakashmaheshwaran/flint-app/pull/264). |
| R2p2-N03 (#247), iOS | **REOPENED** | Fresh install in production order: RE-VERIFIED CLOSED. Legacy install that never used Open: REOPENED. Interrupted fresh bootstrap: REOPENED. Refill/bypass direction: CLOSED. Fix [#262](https://github.com/Prakashmaheshwaran/flint-app/pull/262). |
| R-07 (#251), iOS | **RE-VERIFIED CLOSED** | 13 attack tests pass. Forged but well-formed anchor value: SUSPECTED, outside the fault model. Unknown-boot over-count: rejected (impossible premise). Native DST/monitor delivery: DEVICE-PENDING. |
| R-20 (#251), iOS | **RE-VERIFIED CLOSED** for the reported path | Retained-shield first upgrade and normal legacy idle: pass. Loss during an ordinary break after migration: the same defect as N02's double fault, fixed by #264. First-migration-during-break: not counted (unreachable). Cross-process race: DEVICE-PENDING. |
| R-04 (#251), iOS | **REOPENED** | Ordinary stop and 12 guards: CLOSED. In the real crash state, the picker draft is re-applied under the restored predecessor (weakening) and the Emergency Pass scope is refused: REOPENED. Fix [#263](https://github.com/Prakashmaheshwaran/flint-app/pull/263). |
| R2p2-N01 (#247), Android | **REOPENED (positive budget); zero-minute CLOSED** | Zero-minute commit through the reducer and the real controller, replay, a session added, a day boundary, unprovable measurement: RE-VERIFIED CLOSED. Evidence for a looser, stale definition still permits new access: REOPENED. Fix [#265](https://github.com/Prakashmaheshwaran/flint-app/pull/265). Two further paths SUSPECTED and bounded; one owner question. |
| R-16 (#247), Android | **RE-VERIFIED CLOSED** | Migration through the real controller: preserved 900 s wait, confirm, resume, 900 s again; 10 of 14 tests pass. A legacy budget-only Time cause starting at 60 s: SUSPECTED, bounded to the upgrade-day occurrence. |
| R-30 (#249), Android | **RE-VERIFIED CLOSED for the claimed scope; PARTIAL** | 15 of 16 attack tests pass (glance, Timer Armed, retries, fallback, safety floor, restart). The app-detail per-target projection still claims "enforcing now" after a reported mount failure (display only). |
| R-18b (#225, #250, #254), Android | **RE-VERIFIED CLOSED** | Core trusted day and durable floor: 24 tests pass. Follow-ups: labelling and capture-retry tests pass. One adjacent measurement gap SUSPECTED; one owner question. Physical/OEM measurement: DEVICE-PENDING. |

## Per-residual evidence

### R2p2-N02 — stale ordinary-break restoration (iOS, #228)

Tests: `ios-app/Tests/FlintRedTeamN02Tests.swift`, as curated in PR #264.

These pass on `b888084`:
- the production fallback never writes a successor's reused `flint` store (Y current, or none current);
- a Hardcore successor started through the real `startBlockNow` applies its full A+B selection, and a stale X fallback then writes nothing;
- the production default provider reads exactly what `FlintGroupStore.saveActiveSession` writes (X, Y, and undecodable bytes);
- a permanently due queue (context always nil) still re-shields the still-current session at the deadline, idempotently, retained `.deadline` plus `applicationPending`;
- a same-boot wall jump and a zone change keep restoring, and a cross-boot record ends `.reboot` with no write.

**Double fault, reopened.** When break context is unavailable and `flint.activeSession` is absent or undecodable:
- `restoreUnavailableAuthorities` verified no owner and wrote nothing;
- session reconcile returns `unavailable()` without touching the store;
- so target A stayed released at +301 s, +601 s and +1801 s, with zero restoration writes. This is the child's run on `b888084`, 8 tests with 3 failures.

The R-20 child reached the same defect from the session side (its test C). The commitment is an Easy or Harder session (Hardcore takes no ordinary break), and the release lasts until the session's own end.

**#264 fix and its evidence.** When the projection can't be read, #264 verifies the owner from the ledger's latest **applied**, non-idle generation instead. With that lookup disabled, which is exactly the `b888084` ownership logic, the new test fails with `("Optional(Set([target B]))") is not equal to ("Optional(Set([target A, target B]))") … (writes: 0)`. With the fix, 65 tests pass: N02, the native-break, break-service and break-authorization suites.

Not counted:
- A grant record saved without `focusSessionID`: no released build wrote one, since ordinary breaks shipped after v0.1.0.
- A demand that session reconcile itself re-apply while the projection is unreadable: with no break nothing was released, and #264 re-shields at the break deadline.

DEVICE-PENDING: real `ManagedSettingsStore` writes, the Shortcut/App Intent start, StopSessionIntent, and the extensions.

### R2p2-N03 — first Open quota after initialization (iOS, #247)

Tests: `ios-app/Tests/FlintOpenCreditRedTeamN03Tests.swift`, 11 tests in production order: `prepareInitialization`, an App Group-equivalent ledger migration, the first Open rule, then the first credit.

On `b888084`, 3 tests fail with 9 assertions (local run, `redteam-orch`):
- **Legacy install that never used Open** (the finding's own second case). A v0.1.0 install with session or schedule evidence and no Open rule takes the legacy branch, which writes no receipt, so its `[]` reads as "established", the credit is refused, and `remaining == nil` instead of 3.
- **Interrupted fresh bootstrap.** The receipt is `preparing`, and the host's `publish` creates the ledger before the bootstrap resumes. The import records `unavailable` with unreadable rules.
- **Failed bootstrap, then ledger creation, then resume.** Same outcome.

Bypass guards pass both before and after the fix:
- a legacy user who deleted every rule and lost the counter stays unavailable. The child's first draft of this test omitted the `[]` that v0.1.0 `saveAll([])` leaves behind; it was corrected to model the real deletion state.
- a surviving or spent counter beats both the receipt and the never-used proof;
- unreadable restored rules stay unavailable;
- a lost ledger file is never re-imported;
- a rotated identity gets no fresh pool.

Fix: [#262](https://github.com/Prakashmaheshwaran/flint-app/pull/262); 28 targeted tests pass with it, and `make verify-ios` passes at its final head. The independent reviewer recommended APPROVE WITH NITS and found no refill path. It flagged one narrow residual: on the interrupted-bootstrap path, if the next launch skips Open reconcile and the user saves a rule before any ledger transaction, the import still reads "established" and falls back to the old fail-closed "unavailable until reset". DEVICE-PENDING: signed-device first-use import (D08/D14).

### R-07 — anchorless rule activity (iOS, #251)

Tests: `FlintRuleAnchorResidualRedTeamTests` (evidence copy in this record's evidence directory), 17 tests. The 13 that pass on `b888084` cover:
- the checked-in anchorless fixture: off and delete defer without elapsed time, `time_uncertain` is recorded, and the release is bounded;
- an undecodable anchor;
- a second anchor loss after one re-anchor;
- a backward wall clock before the first observation;
- a device zone change;
- zero, negative and over-26-hour retained durations, which fail closed as `.unavailable`;
- queue windows whose start is far past or in the future;
- legacy imports, bounded by their imported occurrence;
- normal mutations never stripping occurrence evidence;
- an unpersistable re-anchor, which is unavailable and then retried from the retained start;
- `time_uncertain`, which never becomes release authority.

Not counted:
- **Forged anchor timeline (3 failing tests): SUSPECTED, outside the fault model.** The tests rewrite a stored, well-formed `timeAnchor.timeline` to a wrong value. No app path produces that state, and anything able to write App Group storage could flip the rule's `enabled` flag just as easily.
- **Unknown boot ID "over-count" (1 failing test): rejected.** The production clock is `CLOCK_MONOTONIC_RAW`, which restarts at boot, so after an undetected reboot the new uptime is at most the true elapsed time. The test's premise (3 days of uptime but 30 minutes of wall time since the anchor) cannot happen.

Residuals as recorded in the ledger: never-observed legacy imports, and rules with no retained occurrence or queue, still start on wall time. Native DST and monitor delivery remain DEVICE-PENDING.

### R-20 — first session-authority migration (iOS, #251)

Tests: `FlintRedTeamR20BreakLiftTests` (evidence copy). These controls pass on `b888084`:
- a live session whose shield is still applied is not made idle by losing `flint.activeSession` at first upgrade;
- a normally ended v0.1.0 install still reaches idle, so there is no dead end.

Not counted: the two first-migration-during-an-ordinary-break tests. v0.1.0 had no ordinary breaks (they arrived with #214, after session authority), so a v2 or pre-ledger install can never be mid-break. The tests created the state by downgrading a v3 ledger.

Covered elsewhere: the post-migration "record lost during a break, nothing re-shields" test is the N02 double fault, fixed by #264.

SUSPECTED, code-read only: the shield keys outside `hasAppliedSettings`.

DEVICE-PENDING: a cross-process host/extension race (the transition lock is a real `flock`, so it can't be interleaved in one process), and real ManagedSettings reads.

### R-04 — retained legacy predecessor (iOS, #251)

Tests: `ios-app/Tests/FlintR04RecoveryRedTeamTests.swift`, 17 tests, in PR #263. Each seeds the real crash state: the real `startBlockNow` accepts the replacement, the crash is injected right after acceptance, and the test then performs exactly `startBlockNow`'s three post-commit writes (target snapshot, projection, picker) without the native apply.

On `b888084`, 5 tests fail:
- the Emergency Pass scope and the real `preview()` are refused (`scopeUnavailable`), because the replacement's id-keyed snapshot survives;
- the same `reconcile()` that restores predecessor X re-applies the picker draft as if it were X's targets (`applyCount == 1`, `nativeWrites=["pickerDraft(selectionB)"]`).

The second is a weakening: targets only X blocked are released with no friction and no pass, while the ledger reports X as the owner.

12 guards pass: ordinary stop reaches X; foreign and mismatched projections are refused; an expired finite predecessor is not resurrected; a live stronger replacement is applied; a partially applied replacement is retained; a third start; a wall jump; an idempotent crash during recovery; a pass on X ends only X; an open-ended legacy Hardcore predecessor can't be replaced.

Fix [#263](https://github.com/Prakashmaheshwaran/flint-app/pull/263): 229 tests pass with it. DEVICE-PENDING: hardware resumption and crash.

### R2p2-N01 — credit or break under committed Time evidence (Android, #247)

Tests (evidence copies): `RedTeamR2p2N01ResidualTest` (12) and `RedTeamR2p2N01ControllerTest` (2, with the real `DataStoreProtectionTransactionStore`, the real usage store and `GlobalRecoveryController`).

These pass on `b888084`:
- a zero-minute Hardcore limit committed after review is denied with nothing charged, in both the reducer and the real controller;
- replaying the same receipt, or a fresh one, after a zero-minute commit;
- a Hardcore session added between review and commit;
- a trusted quota-day boundary between review and commit;
- `ACCESS_MISSING`, `QUERY_FAILED`, `HISTORY_UNKNOWN`, `STORAGE_UNAVAILABLE` and unmeasured budgets never permitting new access.

**Reopened.** The finding's own fix direction asked to "bind nonzero usage evidence to current limit revision/period". #247 derived only the zero-minute case. A strengthening edit (30 min lowered to 1 min, with 20 min already used) can commit before the detector re-measures. The coordinator's guard (`TimeLimitDomainReadiness.permitsNewAccess`) then accepts the last observation, measured for the looser 30-minute definition, and the production credit and ordinary-break paths proceed. The reducers have no usage channel, so the child's controller test charges a credit (`expected:<Denied> but was:<OpenAllowed>`).

Fix: [#265](https://github.com/Prakashmaheshwaran/flint-app/pull/265). The guard now refuses evidence measured against a looser cap than the committed one. Equal or stricter evidence still permits, because Path A measures the minimum of the legacy and committed caps. The new guard test fails on `b888084` (`TimeLimitDomainReadinessTest.kt:50`) and passes with the fix. Residual: an edit landing between that guard and the serialized transaction, a window of milliseconds.

SUSPECTED, bounded: `covers` and `retireInvalid` keep a live break while a just-committed exhausted Hardcore Time Limit has not yet been re-measured.
- The child's tests pass a stale `timeSpent`.
- Production rebuilds `ordinaryInput` from the latest detector observation on every evaluation and refuses any exemption while the raw Time definitions differ from the snapshot.
- So the exposure is bounded by detector re-measurement (cadence ≤ 15 s).

Owner question: when a period's earlier usage is unknown, `TrustedTimeLimitReader` reports `OBSERVED` with `accountingUnknownUntilReset`, and the guard permits credits and breaks. #250 records this as intended ("affects only the warning, never breaks or credits"). The child's test asserts the opposite.

### R-16 — imported Harder ordinary-break friction (Android, #247)

Tests (evidence copies): `RedTeamR16LegacyImportFrictionTest` (13) and `RedTeamR16CoordinatorFrictionTest` (1), driving the real DataStore migration, the real `DataStoreProtectionTransactionStore` and `GlobalRecoveryController.requestOrdinary`. The child stopped before reporting, so the orchestrator ran its classes under the lock (owner `redteam-orch-r16`): 14 tests, 4 failed.

**The original fix direction's end-to-end path passes:**
1. A legacy Harder Timer with `break_session_v1=2` migrates.
2. The count is seeded at 2.
3. The first ordinary break waits the preserved 900 s.
4. Explicit confirmation grants 300 s and increments the count to 3.
5. After resume, a second request again waits 900 s.

Also passing:
- legacy Time and exhausted-Open Harder contributors keep their seeded wait;
- a legacy Easy contributor overlapping a legacy Harder one doesn't lower the wait;
- an imported plus a new Harder contributor waits the maximum and increments each exactly once;
- the seeded count survives reconcile and a store reopen;
- a pending wait survives process recreation and matures only on confirmation;
- a wall-clock jump during the wait doesn't mature it early;
- legacy edit rights stay allowed but never waive the wait;
- the coordinator-level migrated Harder flow (900 s, confirm, escalate).

Not counted:
- **Legacy Hardcore (`R16E`).** The break is correctly `Denied` with nothing spent; the test then expects a seeded Harder count on a Hardcore cause, which §12 does not seed.
- **Reboot during a pending wait (`R16K`).** The next request is `Denied`, stricter than the expected fresh wait.
- **Scheduled legacy Harder rule (`R16B`).** It is not seeded, but a diagnostic run shows the request is `Denied` outright: no early release (possibly over-blocking, an availability observation).

SUSPECTED, P2: a Harder Time cause with no Time template, which exists only as a legacy `flint_limits` per-app budget, is modelled as a synthetic `legacy-time` occurrence that never receives the preserved `break_session_v1` count. In a diagnostic run its first wait was 60 s (`breaksTaken=0`), against 900 s for a seeded cause (`R16M`). §12 seeds only occurrences active at migration, and the migration cannot know whether a legacy budget was exhausted then, so the exposure is bounded to that one occurrence on the upgrade day. Recorded as a follow-up, not a demonstrated hole.

### R-30 — block-surface mount failure in health and Armed (Android, #249)

Tests (evidence copies): `RedTeamR30CoordinatorMountTest` (7), `RedTeamR30TimerArmingTest` (5), `RedTeamR30TimerViewModelTest` (2) and `RedTeamR30TargetProjectionTest` (2). These pass on `b888084`:
- a failed primary mount survives policy ticks, rebinds and Path B health reports;
- every rejected attach shape is published as failed and retried;
- a failing fallback tightens to failed and keeps retrying;
- a safety-floor target is never mounted and never reports a failure;
- process restart keeps no stale failure, and the next failed mount is recorded;
- no Timer attempt shape reaches Armed while the record is failing;
- a second Timer can't borrow Armed;
- a Timer started during a failure is saved but never reads Armed;
- Armed returns only after a real mount;
- the fallback-only copy makes no enforcement claim.

Two tests pass that pin documented scope rather than a requirement: a successful mount on the other path, or for an unrelated target, clears the latest outcome (the record is process-wide).

**PARTIAL, display only.** `TargetRuntimeHealth` does not consult `block-surface-mount-failed` or `block-surface-fallback-only`, so the app-detail per-target projection still reports `enforcingNow` and `blockingNow` after a reported mount failure. #249 claimed only glance and Timer Armed, so this is an unclaimed surface. Because the record is process-wide, attributing it to every target is a product decision; see the owner questions. Enforcement is unchanged.

SUSPECTED, pre-existing design, no test: a failed Path A mount is retried only on the next `showBlocked` (window event, boundary or enforcement signal). Path B polls every second; Path A does not, so a user parked in the blocked app may stay unshielded until the next boundary.

DEVICE-PENDING:
- an overlay hidden or removed after a successful attach (Android 12 `HIDE_OVERLAY_WINDOWS`, system dismissal), which health cannot observe;
- a fallback dropped by background-activity-launch limits;
- real `BadTokenException` and revocation timing.

Protocol note: the R-30 child disclosed that it ran its rerun script once outside the `android` lock (1 m 25 s, same result as its locked runs).

### R-18b — daily Time Limits (Android, #225, #250, #254)

**Core (#225): 24 tests, all passing on `b888084`.** Evidence copies: `RedTeamR18bDayAccountingTest` (12), `RedTeamR18bTrustedDayStoreTest` (9) and `RedTeamR18bHandoffTest` (3). They cover:
- a 48-hour forged wall leap with a zero OS sample for the new raw day, through the real reader and both coordinator handoffs;
- process recreation;
- reboot, which resumes exactly the saved remainder;
- device zone changes east, west and to a fixed offset;
- re-zoning an exhausted Hardcore limit, which is deferred, including across a reboot with the wall 3 days ahead;
- a budget raise while exhausted (deferred) and a cut (immediate);
- per-package floors;
- Path A and Path B disagreeing;
- backward-then-forward clock leaps;
- the OS sum collapsing below the floor;
- a DST fold and gap in the saved zone;
- zero-minute budgets;
- stale period tickets;
- release only at the trusted reset.

**Follow-ups (#250, #254).** These pass in `TimeLimitBootstrapRetryRedTeamTest` and `TimeLimitUnavailableReleaseRedTeamTest`:
- state labelling never publishes Ready for partial or missing owned bytes;
- a zero-minute limit becomes enforceable on the in-process capture;
- a failed post-capture reconcile keeps one capture, and the next editor preparation initializes the ledger;
- a lost capture acknowledgement leaves the ledger uninitialized until an editor write;
- a durable capture without a ledger stays fail-closed;
- a Time accounting fault never erases a configured limit or its shield;
- a zero-minute limit shields across an unavailable emission.

Not counted from that run:
- Two `~UTC` comparison failures were test expectations; the child had corrected them in the file before stopping, and they were not re-run.
- One test seeded a `U|IO` capture-failure latch and expected a retry. Production never writes `U|IO`: every failure in the payload block goes through `wireCorrupt()`, so only `U|CORRUPT` can be written (`TimeLimitBootstrapContext.kt`).

SUSPECTED, adjacent: with Usage Access revoked, a limit not yet exhausted can go unmeasured, because the 30 s foreground gap cap is shorter than the 60 s stationary cadence. An exhausted day stays enforced. Owner question: a `U|CORRUPT` latch (malformed legacy Time bytes) is never retried by design, which leaves positive budgets unmeasured for the install's life. DEVICE-PENDING: real `UsageStatsManager` behaviour across zone changes, backward edits and reboot.

## Not counted as holes

| Report | Why it is not counted |
| --- | --- |
| R-20: first migration during an ordinary break (2 tests) | Unreachable. v0.1.0 had no ordinary breaks; the test downgraded a v3 ledger. |
| R-07: forged but well-formed anchor timeline (3 tests) | Outside the fault model: it requires rewriting a valid stored value. SUSPECTED. |
| R-07: unknown boot ID over-count (1 test) | The premise is physically impossible under `CLOCK_MONOTONIC_RAW`. |
| R2p2-N02: grant without `focusSessionID` (1 test) | No released build wrote such a record. |
| R2p2-N02: session reconcile re-apply while unreadable (1 test) | No release without a break; #264 re-shields at the deadline. |
| R2p2-N01: `covers`/`retireInvalid` with a stale input (3 tests) | Production rebuilds the input each evaluation. SUSPECTED, bounded by cadence. |
| R2p2-N01: unknown earlier history (1 test) | A documented design choice (#250). Owner question. |
| R-16: Hardcore not seeded / reboot denies / schedule denies (3 tests) | Stricter than the contract; no release. |
| R-18b follow-ups: `U\|IO` latch (1 test) | Production cannot write `U\|IO`. |

## Fix pull requests

All four are opened as drafts by this re-verification, never merged, and each records failing-then-passing evidence. Each must pass its platform gate under the lock and get an independent review by a fresh child before it can be marked ready. The gateway's billing stop blocked the reviews of #263 and #264, and #265's review cannot start for the same reason. Current state per PR is in the result summary of this work item.

| PR | Branch | Change | Local gate | Independent review |
| --- | --- | --- | --- | --- |
| [#262](https://github.com/Prakashmaheshwaran/flint-app/pull/262) | `fix/redteam-r2p2-n03` | Keep the first Open quota after legacy or interrupted initialization. | `make verify-ios` at `9c20247`: 1325 tests, 0 failures | APPROVE WITH NITS (at `2092c21`); nits fixed in `9c20247`, one Medium declined with reason. Marked ready. |
| [#263](https://github.com/Prakashmaheshwaran/flint-app/pull/263) | `fix/redteam-r04` | Keep a restored legacy predecessor's own targets and its pass scope. | `make verify-ios` at `6b056b6`: 1331 tests, 0 failures | Blocked by the billing stop; draft. |
| [#264](https://github.com/Prakashmaheshwaran/flint-app/pull/264) | `fix/redteam-r2p2-n02` | Re-shield an expired session break when the session record is unreadable. | `make verify-ios` at `7aa02c9`: 1321 tests, 0 failures | Blocked by the billing stop; draft. |
| [#265](https://github.com/Prakashmaheshwaran/flint-app/pull/265) | `fix/redteam-r2p2-n01` | Refuse stale Time evidence measured for a looser limit. | `make verify-android` at `4de355a`: "Android gate passed" | Blocked by the billing stop; draft. |

## Owner questions

1. **Unknown same-day history (#250).** Should credits and ordinary breaks stay permitted when a period's earlier usage is unknown (`accountingUnknownUntilReset`), as #250 decided? The R2p2-N01 child argues they should be refused.
2. **Permanent Time capture failure (#254).** Should a `U|CORRUPT` Time capture latch stay permanent, or should positive budgets fail closed (shielded) rather than unmeasured?
3. **App-detail health (R-30).** Should a process-wide block-surface mount failure mark every target's app-detail state as degraded, or should that surface stay "blocked now" until per-target mount evidence exists?

## Evidence files

The red-team test sources that are not part of a fix PR are kept, uncompiled, in [`redteam-reverify-2026-11-11-evidence/`](redteam-reverify-2026-11-11-evidence/). See its README for which tests pass, which fail and why each failure is or isn't counted. No product code or build file was changed by this record.
