# ADR-012 red-team verification — 2026-10-29

Main audited integration revision: **`0f45cd22829c2ce6ec8de173eedb7c69b17779a9`**. This is a frozen review of code actually merged into `origin/integration/v1`, against the [original R-01–R-37 audit](adr-012-redteam-2026-10-28.md) at `2b91849534e757952c80662ddb90235129aaa8b9` and [ADR-012](../architecture/protection-contract.md), including its tier matrix and PC-01–PC-84.

**Result: 11 VERIFIED-CLOSED source scopes, 10 PARTIAL items, 2 REOPENED items across 23 claimed items, plus 3 NEW P0 findings.** These counts do not include R-17: it was pending at the frozen revision, then source-verified separately at [#151](https://github.com/Prakashmaheshwaran/flint-app/pull/151)'s exact merge `9554f6e7ce95e55fbf3ab3131c17323e8b92202e`. A source closure is not a physical-device acceptance result.

Product code remained read-only. This review traced production entry points, inspected relevant test bodies and merged PR bodies, and used two independent read-only platform reviewers. No tests, local platform builds, emulators, physical devices or live storage faults were run. Source-derived corruption/interleaving/clock sequences below are concrete code paths, not observed device experiments. PR authors' historical CI/test totals were not independently rerun or promoted into R2 evidence.

## Verdict rules and scope

- **VERIFIED-CLOSED:** the specified original path and reviewed variants are rejected by the merged source. Scope qualifications are part of the verdict, particularly R-01, R-02, R-03, R-34, R-36 and the Focus-only R-37 claim.
- **PARTIAL:** a meaningful part is fixed, but an identified variant or required behavior remains. R-12 is gated at this revision and remains runtime device-pending.
- **REOPENED:** the original production reproduction remains reachable despite a merged component fix. R-11/R-19 have a new ledger foundation, but the shipping runtime still uses the old authority.
- **NEW:** a separate concrete path found during this verification. R2-N01/N02/N03 are each P0 under the original rubric: a finite Hardcore restriction can be removed early.

Original severities are retained: P0 concrete Hardcore/pass/safety bypass; P1 contract violation; P2 copy/projection overclaim; P3 boundary/diagnostic hardening. Severity on a verified row describes the original defect, not a currently demonstrated bypass. R-24's copy fix is verified, but the original multi-round behavior remains partial. R-37 is counted only for the claimed Focus copy; its pass/Open portions are pending and are not asserted closed.

## Per-item verdicts at the main audited SHA

| Item | Original severity | Verdict | Closing work reviewed | PC cases / result |
| --- | --- | --- | --- | --- |
| R-01 | P0 | VERIFIED-CLOSED, PIN entry scope | [#152](https://github.com/Prakashmaheshwaran/flint-app/pull/152) | PC-50: locked gate exposes minimal recovery without the PIN. |
| R-02 | P0 | VERIFIED-CLOSED, original editor routes | [#139](https://github.com/Prakashmaheshwaran/flint-app/pull/139) | PC-10/11/12/14/15/16/18/27/56/79/82/83: current-tier transaction gate and queued weakening replace direct UI mutation. Native acceptance is separate. |
| R-03 | P0 | VERIFIED-CLOSED, new Timer exit | [#139](https://github.com/Prakashmaheshwaran/flint-app/pull/139) | PC-17/67/68: v2 Hardcore cannot use the ordinary hold or old direct deletion API. |
| R-05 | P0 | PARTIAL | [#174](https://github.com/Prakashmaheshwaran/flint-app/pull/174) | PC-04/54/55/58: original-boot elapsed authority fixed; reboot never adopts a new elapsed anchor or saved-remaining bound. |
| R-07 | P0 | PARTIAL | [#162](https://github.com/Prakashmaheshwaran/flint-app/pull/162) | PC-10/11/12/14/15/16/18/27/56/83: editor/queue gate exists; wall time still decides rule activity and queue due-ness. |
| R-11 | P0 | REOPENED | [#160](https://github.com/Prakashmaheshwaran/flint-app/pull/160); [#178](https://github.com/Prakashmaheshwaran/flint-app/pull/178) absent | PC-22/23/28/30/31/35/59/75/76: live foreground debit still buys an unbounded visit. |
| R-12 | P0 | PARTIAL; runtime DEVICE-PENDING | [#155](https://github.com/Prakashmaheshwaran/flint-app/pull/155) | PC-22/23/28/30/31/35/59/65/75/76: bounded source adapter exists; shield acceptance is false at this SHA. |
| R-13 | P0 | VERIFIED-CLOSED, source accounting | [#155](https://github.com/Prakashmaheshwaran/flint-app/pull/155) | PC-29/37/38/54/55/56/60/70/71: strict atomic ledger, preserved migration, trusted reset and commit-before-release. |
| R-14 | P1 | VERIFIED-CLOSED, conservative no-charge path | [#155](https://github.com/Prakashmaheshwaran/flint-app/pull/155) | PC-32/33: unprovable category overlap denies before consumption. |
| R-18 | P0 | PARTIAL | [#170](https://github.com/Prakashmaheshwaran/flint-app/pull/170) | PC-54/55/56/57/58/60: anchored schedules fixed; live daily usage still resets from current wall day/zone. |
| R-19 | P0 | REOPENED | [#160](https://github.com/Prakashmaheshwaran/flint-app/pull/160) | PC-37/47/69/70: production focus/pass reader still defaults selectively missing established accounting to unused rights. |
| R-20 | P0 | PARTIAL | [#174](https://github.com/Prakashmaheshwaran/flint-app/pull/174) | PC-02/05/64/66 and §8: shared lock and corrupt-present handling fixed; missing/unavailable session paths still clear. |
| R-21 | P0 | VERIFIED-CLOSED, callback identity | [#162](https://github.com/Prakashmaheshwaran/flint-app/pull/162) | PC-12/64/66: incarnation/revision IDs and exact callback matching protect the successor store. |
| R-22 | P0 | PARTIAL | [#162](https://github.com/Prakashmaheshwaran/flint-app/pull/162) | PC-62/70/71 and §8: missing post-marker domain preserved; missing established legacy domain before first migration still becomes empty. |
| R-24 | P1 | PARTIAL; copy claim verified | [#174](https://github.com/Prakashmaheshwaran/flint-app/pull/174) | PC-01/27: description says one round; multi-round input still creates one ordinary session. |
| R-25 | P1 | PARTIAL | [#174](https://github.com/Prakashmaheshwaran/flint-app/pull/174) | PC-03/10: supplied ID deduplicates only the current live session; entry points and durable/stale-confirmation replay remain incomplete. |
| R-29 | P1 | PARTIAL | [#78](https://github.com/Prakashmaheshwaran/flint-app/pull/78) | PC-11/41/62/72: live Safari lifecycle and tier-gated edits exist; accepted children still read mutable global configuration. See R2-N01/N02. |
| R-31 | P1 | VERIFIED-CLOSED, duplicate admission | [#162](https://github.com/Prakashmaheshwaran/flint-app/pull/162) | PC-70/71: whole domain rejected/quarantined, original bytes retained. |
| R-33 | P2 | PARTIAL | [#183](https://github.com/Prakashmaheshwaran/flint-app/pull/183) | PC-19/25/72: equal-tier latest end fixed; mixed-tier screen can still promise an earlier overall unblock. |
| R-34 | P0 | VERIFIED-CLOSED, saved tier | [#156](https://github.com/Prakashmaheshwaran/flint-app/pull/156) | PC-15/25: both paths and stale surface actions use saved Time Limit tier. |
| R-35 | P1 | VERIFIED-CLOSED, obsolete fields | [#162](https://github.com/Prakashmaheshwaran/flint-app/pull/162) | PC-65: full materialization writes all four owned shield fields. |
| R-36 | P3 | VERIFIED-CLOSED, early end/diagnostics | [#182](https://github.com/Prakashmaheshwaran/flint-app/pull/182) | PC-74/76: zero-slack deadline and late-warning diagnostics; actual delivery remains device-pending. |
| R-37 Focus only | P2 | VERIFIED-CLOSED, stated copy scope | [#182](https://github.com/Prakashmaheshwaran/flint-app/pull/182) | PC-08: copy matches Easy/Harder/Hardcore Focus-off behavior; other R-37 copy is pending. |

The 23 rows above contain 11 source closures, 10 partials and 2 reopened items. R-11/R-19 are labelled REOPENED in this final adjudication because the original runtime paths remain unchanged; the merged ledger work itself is not rejected.

## Merge provenance

For each claimed item, the requested `gh pr list --base integration/v1 --state merged --search "R-NN"` search was followed by reading the known PR body. Search indexing sometimes omitted the closing PR or returned unrelated PC-number matches; explicit PR metadata and Git ancestry resolved inclusion. Every merge below is an ancestor of the main audited SHA.

| PR | Merge commit | Reviewed scope |
| --- | --- | --- |
| [#152](https://github.com/Prakashmaheshwaran/flint-app/pull/152) | `77fa6701f193d7ac50247896d6cbff58147b0021` | PIN-free singleton recovery entry |
| [#174](https://github.com/Prakashmaheshwaran/flint-app/pull/174) | `d6321d189f9d2113a49d192edb58856332565d8d` | Session time, shared transition lock, start identity, Pomodoro copy |
| [#182](https://github.com/Prakashmaheshwaran/flint-app/pull/182) | `b2f923bd55b4fb63dcceda64f3b59a4fcbf197a4` | End timing/diagnostics and Focus copy |
| [#155](https://github.com/Prakashmaheshwaran/flint-app/pull/155) | `fbb21ce2ec5756550ab616ea47c9aec39a962fe3` | Gated iOS atomic bounded Open credits |
| [#162](https://github.com/Prakashmaheshwaran/flint-app/pull/162) | `b8959eab300e085213e2c080fc392d1f7f28df0d` | Rule mutation, migration, callback identity and shield fields |
| [#78](https://github.com/Prakashmaheshwaran/flint-app/pull/78) | `4c3ec5007429eb32fc82c44ab6cafd920c761945` | Safari lifecycle and editor |
| [#139](https://github.com/Prakashmaheshwaran/flint-app/pull/139) | `97b766207da0d44de859e0e77bba754da4a46dc7` | Android editor commitments and Timer rights |
| [#160](https://github.com/Prakashmaheshwaran/flint-app/pull/160) | `cae81db731c9814e71b665e286fe658d521dd5d0` | Android Open-credit ledger foundation |
| [#170](https://github.com/Prakashmaheshwaran/flint-app/pull/170) | `385c7a5ebd8616ae84a27956d94b4d5d9c78e5f2` | Android anchored rules and receiver evidence |
| [#183](https://github.com/Prakashmaheshwaran/flint-app/pull/183) | `4de08cf5c383cf9a77a6b8f7360c7c06aced4d57` | Android overlap headline selection |
| [#156](https://github.com/Prakashmaheshwaran/flint-app/pull/156) | `150b0ae3ac8adf718fc8a936f6d0fe9116ee3494` | Saved Time Limit tier in live enforcement |

[#178](https://github.com/Prakashmaheshwaran/flint-app/pull/178) was OPEN at review, head `939c60741e55bfbc7aa44085de7e9c94b97078bd`; its body says it supplies production Open runtime wiring and depends on [#175](https://github.com/Prakashmaheshwaran/flint-app/pull/175). Neither open head is certified here. [#151](https://github.com/Prakashmaheshwaran/flint-app/pull/151) merged during review after the main pin; its supplement below is explicitly separate. Later integration fixes cannot retroactively close paths at the main audited SHA.

## Reopened runtime findings

### R-11 — P0: the live Open visit still has no elapsed bound

**PC-22/23/28/30/31/35/59/75/76.** Both detectors reach [BlockScreenCoordinator.kt:357](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/BlockScreenCoordinator.kt#L357). Its foreground path at :444–463 automatically queues a debit, :500–511 marks the visit paid after acknowledgement, and :484–489 returns Allowed for that paid visit without checking an expiry. [DurableGrantController.kt:169](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/DurableGrantController.kt#L169) records the legacy debit with `untilEpochMs = 0`; positive-deadline expiry jobs therefore provide no five-minute recovery. [OpenVisits.kt:8](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/OpenVisits.kt#L8) does not age out a paid same-target visit.

**Reproduce:** set a Hardcore Open cap of one, enter the target with no independent blocker, wait for debit acknowledgement, and stay foreground beyond 300 seconds and midnight. Path A's stationary evaluations and Path B's polling repeat the same allow. Same-process recreation retains the visit; whole-process death loses it rather than restoring a bounded credit. An already-paid visit is allowed before the fresh counter-readiness check, so later unavailable storage with last-good state does not establish an end.

**Tests:** [OpenCreditStoreTest.kt:51](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/core/core-datastore/src/test/kotlin/com/flint/peakfocus/core/datastore/OpenCreditStoreTest.kt#L51) uses a test-owned acceptance transaction and synthetic receipt. Its real DataStore reopen case at :242 is useful ledger evidence, but does not call the shipping coordinator. [DurableCoordinatorTest.kt:344](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/blocking/blocking-overlay/src/testDebug/kotlin/com/flint/peakfocus/blocking/overlay/DurableCoordinatorTest.kt#L344) still asserts automatic foreground charging and paid-visit allowance, without crossing 300 seconds/midnight.

**Fix direction:** [#178](https://github.com/Prakashmaheshwaran/flint-app/pull/178) or an independently reviewed equivalent is required: explicit credit acceptance, one durable counter/grant/idempotency transaction, trusted midnight clipping, and both detectors consuming the same elapsed deadline with a stationary expiry wake. Remove the foreground-debit/paid-visit escape. Device acceptance is required after wiring; more frequent polling alone cannot supply a missing deadline.

### R-19 — P0: initialized accounting can still become fresh rights

**PC-37/47/69/70.** The production factory [FlintPreferences.kt:37](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/core/core-datastore/src/main/kotlin/com/flint/peakfocus/core/datastore/FlintPreferences.kt#L37) uses `DataStoreFocusStateStore`. [DataStoreStores.kt:255](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/core/core-datastore/src/main/kotlin/com/flint/peakfocus/core/datastore/DataStoreStores.kt#L255) accepts missing pass-use time as null and absent Open counts as an empty map; absent day/week use uninitialized sentinels. The serialized mutation calls the same reader at :204–205. [FocusLedgerCodec.kt:31](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/core/core-datastore/src/main/kotlin/com/flint/peakfocus/core/datastore/FocusLedgerCodec.kt#L31) also treats a missing receipt ledger as empty.

**Reproduce:** spend a pass, then fault only `emergency_pass_used_at_v1` absent while retaining week, receipts and other settings. The production pass policy receives unused state without crossing a week. Separately, spend an Open quota and lose only `open_counts_v1` with its day/initialized state intact: the live reducer sees zero. Cold service, reboot and time-receiver entry use the same reader. Present malformed bytes/IO are handled as unavailable; selective absence evades that handler.

The strict initialized Open-credit domain from #160 rejects missing critical fields in [OpenCreditStore.kt:18](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/core/core-datastore/src/main/kotlin/com/flint/peakfocus/core/datastore/OpenCreditStore.kt#L18), but the live focus reader does not use it. There are two accounting authorities. This is a storage-completeness finding, not a repetition of pending pass-renewal/global-scope work.

**Tests:** `OpenCreditStoreTest.kt:94` covers selective loss in the new domain. `StorageReadinessTest.kt:58` accepts completely fresh empty storage; :229 reopens a normally preserved pass/receipt. Neither asserts the failing established legacy reader sequence above.

**Fix direction:** validate one initialized accounting domain before Ready or reducer entry; distinguish a proven fresh install from selectively lost state, cross-check retained receipts, preserve original bytes, and reject incomplete imports. #178 addresses the Open portion only; pass-state completeness remains necessary.

## Partial fixes and residual paths

### R-05 — P0: elapsed session authority does not survive recovery into a new boot

**PC-04/54/55/58.** [FlintActiveSession.swift:103](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintActiveSession.swift#L103) correctly uses elapsed time in the original boot. All principal stop/replacement/reconcile/monitor expiry routes use that helper. However, its reboot fallback is `min(original plannedDuration, wallEnd-now)`, not the saved remaining duration; `rearmed` at :183–195 preserves the old boot ID and elapsed start.

**Reproduce:** start 60 minutes, consume 50, reconcile, reboot and roll wall time near the original start. Almost 60 minutes can return because no remaining checkpoint was saved. For early release, reboot while time remains, successfully reconcile in boot B, then advance wall time past the end in that same boot B. The old boot identity keeps choosing wall fallback; [FlintSessionController.swift:856](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintSessionController.swift#L856) clears the live session.

**Tests:** `FlintSessionControllerTests.swift:122,144,184,194` meaningfully exercise original-boot clock divergence. The reboot case at :203 checks only a forward wall fallback, not checkpoint/reboot/rollback or a second wall edit after recovery.

**Fix direction:** persist bounded remaining checkpoints, use them for reboot fallback, and adopt a new boot/elapsed anchor without resetting the remaining interval. Physical callback re-registration and killed-host recovery remain unproved.

### R-07 — P0: rule activity and queued weakening still follow wall time

**PC-11/14/54/56**, within the original broader rule-mutation PCs. Normal editor mutations now pass through the current-tier/revision arbiter; strengthening arms before retirement and weakening can be durably queued. But [FlintGatedRule.swift:42](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintGatedRule.swift#L42) tests frozen/template windows against raw `Date`; [FlintRuleArbiter.swift:90](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintRuleArbiter.swift#L90) derives active status from that result, and :266–267 applies queued edits when wall time reaches the end.

**Reproduce A:** create a new Hardcore 09:00–17:00 schedule, jump wall time from 10:00 to 18:00 with little elapsed time, then turn it off/delete it. The gate treats it as inactive and :565–571 clears/stops its native state. Sleep shares the representation. **Reproduce B:** queue off/delete at 10:00 for 17:00, then jump wall time to 18:00 and trigger foreground/time-change/monitor reconciliation: the future weakening is applied early. Daily Time/Open queues can similarly cross a wall midnight.

**Tests:** `FlintRuleArbiterTests.swift:38,91,103,246,262` exercises production gate results, unchanged native identity on deferral, and ordinary boundaries. `FlintRuleReviewTests.swift:99,123,162,177,197` adds busy/persistence paths. None proves invariance when wall and elapsed time diverge. #162 acknowledges this residual.

**Fix direction:** make active windows and queue deadlines use shared elapsed checkpoints and bounded reboot recovery. Retain the new transaction/revision checks. This is R-07's concrete residual, not a second finding against pending R-28.

### R-12 — P0 original severity: bounded source implementation, shipping gate and device acceptance incomplete

**PC-22/23/28/30/31/35/59/65/75/76.** [FlintOpenCredit.swift:12](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintOpenCredit.swift#L12) is `shieldAcceptanceEnabled = false` at the main pin. Both app/domain primary actions in [FlintShieldAction.swift:14](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Extensions/ShieldActionExtension/FlintShieldAction.swift#L14) and the legacy wrappers in [FlintOpenLimit.swift:240](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintOpenLimit.swift#L240) honor that gate; secondary/category actions only close. The original unlimited-release route is disabled, while the new explicit API is not yet offered through the shipping shield.

The new service registers a five-minute or midnight-clipped carrier before debit, commits a bounded grant, revalidates before native release, and reconciles expiry. Tests inspect real ledger bytes and fake registrar/shield calls; they are not Apple delivery evidence. #155 explicitly discloses that a busy outer transition can make shield application a no-op until a later reconciliation, and a busy warning can recover only at the padded carrier end. A unit assertion that a callback clears does not prove timely app-closed re-shielding.

**Release decision recorded during review:** the coordinator approved enabling the flag with lane H-I's explicit timed-credit copy integration. That later action is outside this pin. Its intended status is **source-complete once H-I merges; runtime DEVICE-PENDING**, never a blanket closed verdict from this report. Signed-device PC-28–PC-38 covering killed-host 300-second expiry, midnight, re-registration and lock contention is a hard pre-release gate; the coordinator specified a one-line disable if acceptance fails.

**Fix/evidence direction:** inspect the eventual merged enablement/copy together, then run the signed-device acceptance gate. Measure late/missed callbacks and finite recovery rather than treating padded-end recovery as an on-time five-minute success.

### R-18 — P0: schedule clocks are repaired, daily Time Limit accounting is not

**PC-54/55/56/57/58/60.** #170 connects saved-zone anchored rule projection to both detectors, and preserves cold-start receiver evidence. [RuleClockProjection.kt:25](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/blocking/blocking-engine/src/main/kotlin/com/flint/peakfocus/blocking/engine/RuleClockProjection.kt#L25) keeps same-boot elapsed active duration; [BlockDecisionEngine.kt:54](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/blocking/blocking-engine/src/main/kotlin/com/flint/peakfocus/blocking/engine/BlockDecisionEngine.kt#L54) consumes that runtime activity before raw schedule minutes. Editor reboot checkpoints and DST/one-shot occurrence handling close the original schedule example.

The remaining path is daily usage. [UsageQuery.kt:60](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/core/core-data/src/main/kotlin/com/flint/peakfocus/core/data/UsageQuery.kt#L60) queries start-of-day in the current device zone using raw wall time. [ConservativeDailyUsage.kt:20](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/core/core-model/src/main/kotlin/com/flint/peakfocus/core/model/ConservativeDailyUsage.kt#L20) clears its process-local high-water floor at raw midnight; its own comment distinguishes this from durable clock integrity. Path A directly consumes this at [FlintAccessibilityService.kt:291](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/blocking/blocking-accessibility/src/main/kotlin/com/flint/peakfocus/blocking/accessibility/FlintAccessibilityService.kt#L291); Path B uses [DailyLimitTracker.kt:18](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/blocking/blocking-usagestats/src/main/kotlin/com/flint/peakfocus/blocking/usagestats/DailyLimitTracker.kt#L18) and [PathBTimeLimitGate.kt:29](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/blocking/blocking-usagestats/src/main/kotlin/com/flint/peakfocus/blocking/usagestats/PathBTimeLimitGate.kt#L29).

**Reproduce:** exhaust a positive-budget Hardcore Time Limit, then advance wall time past local midnight with little elapsed time. Given zero/no usage from the OS for the newly queried wall-day window, the floor resets and both paths can declare the target unspent before the trusted commitment resets. Process recreation also loses the high-water floor. Actual provider samples/timing need a device; the source's wrong reset authority and zero-sample release path are definite.

**Tests:** `AnchoredEditorClockTest.kt:53` composes real editor transactions, holder projection and engine across clock changes; `ClockAnchorStoreTest.kt:24` checks retained startup evidence. These do not execute live `UsageQuery` against a forged midnight plus durable quota identity. Its new Open-ledger assertion at :404 is not a daily Time Limit test.

**Fix direction:** feed saved quota-zone/trusted-period identity and durable consumed-usage lower bounds into both daily usage paths. Retain the repaired schedule projection and receiver wake paths.

### R-20 — P0: serialization does not establish that a missing session was terminated

**PC-02/05/64/66 and ADR-012 §8.** The shared App Group `flock` in [FlintSessionTransitionLock.swift:29](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintSessionTransitionLock.swift#L29) now covers actual transitions; a competing host cannot install Y between X's protected identity check and teardown. Corrupt-present session records are preserved.

**Reproduce A:** arm session Y, retain initialized ledger/readability state, fault only `flint.activeSession` absent, then deliver old X's end. [FlintSessionController.swift:1053](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintSessionController.swift#L1053) clears the singleton when the unrelated marker is readable and the active key is absent. There is no generation-owned committed terminal/tombstone distinguishing this from data loss. **Reproduce B:** an established App Group read becomes empty/unavailable, then foreground reconciliation or `StopSessionIntent` reaches :834–850 or :637–642. These host paths clear without even the monitor's readability check. The lock cannot make an incomplete read authoritative.

**Tests:** `FlintSessionControllerTests.swift:226,242,254,264,276,291,303` cover real flock descriptors, fake-store state and several unreadable monitor cases. The second lock descriptor is in the same test process; this is not independently running host/extension UserDefaults. The orphan-end case at :882 expects absent state to clear; it does not distinguish lost critical data from an authorized stop.

Unchecked UserDefaults writes in [FlintGroupStore.swift:48](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintGroupStore.swift#L48) also leave crash/durability acknowledgement unproved; no particular CFPreferences failure outcome is asserted. Busy callbacks retain protection but can require a later host reconcile, so app-closed finite expiry under contention still needs evidence.

**Fix direction:** validate a complete session domain with acknowledged generation/terminal authority and bounded last-good recovery. Apply the same absent/unavailable policy to host, intents and extensions; retain the lock and exact callback-name checks.

### R-22 — P0: first migration still mistakes missing established rules for a fresh domain

**PC-62/70/71 and ADR-012 §8.** [FlintRuleReloadEnvironment.swift:16](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintRuleReloadEnvironment.swift#L16) rejects malformed domains and missing keys after the new marker/original/quarantine exists. That fixes post-migration loss. At :27–30, absence of both the rule key and the newly introduced metadata returns an authoritative empty array.

**Reproduce:** on an established pre-migration installation with active `flint.rule.X` native registration/shield and other valid legacy preferences, fault only `flint.rules` absent before the first updated launch. No new marker/archive exists yet. The loader returns empty; [FlintSchedulesController.swift:175](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintSchedulesController.swift#L175) reconciles it and [FlintActivityRegistration.swift:104](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintActivityRegistration.swift#L104) retires native ownership, with [FlintGatedRule.swift:299](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintGatedRule.swift#L299) clearing the store. Time/Open domains use the same absence rule.

**Tests:** `FlintRuleMigrationTests.swift:243` migrates first and then deletes the key; :256 uses a genuinely empty suite; :267 tests after a known corrupt blob. None preserves active legacy native state while all new migration metadata is still absent.

**Fix direction:** require positive fresh-install or complete validated legacy authority. Existing native ownership/retained last-good evidence must prevent destructive fresh initialization. Add a pre-first-migration partial-loss fixture, not another post-marker test.

### R-24 — P1: honest single-round copy, unfinished multi-round behavior

**PC-01/27.** [StartPomodoroIntent.swift:14](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Flint/Features/Shortcuts/StartPomodoroIntent.swift#L14) and :45 now say one round and instruct rerunning after the break. This is the claimed copy repair. The action still accepts a round count at :23–30 and builds multi-round configuration at :36–43; [FlintShortcutsRunner.swift:111](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Flint/Features/Shortcuts/FlintShortcutsRunner.swift#L111) stores that cadence then starts one ordinary focus interval.

**Reproduce:** request 25/5 × 4. Only the first 25-minute session is armed; no parent/frozen phase sequence or automatic rest/round transition exists on this path. Session tests prove one timed start, not the requested parent lifecycle.

**Fix direction:** implement the finite parent and immutable focus/rest phases with all-phase preflight, or remove inert multi-round surfaces from an explicitly single-round action. Copy closure does not establish PC-27.

### R-25 — P1: current-row deduplication is not durable operation replay

**PC-03/10.** [FlintSessionController.swift:483](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintSessionController.swift#L483) returns a matching supplied request ID only while that same current session is unexpired. [SessionViewModel.swift:163](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Flint/Features/Sessions/SessionViewModel.swift#L163) keeps its UUID in memory and rotates it after success. [FlintShortcutsRunner.swift:89](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Flint/Features/Shortcuts/FlintShortcutsRunner.swift#L89) supplies no ID; Focus uses a current-owner shortcut, not a durable receipt.

**Reproduce:** lose a start response, recreate the host/view model or retry through Shortcuts/Focus; no durable accepted result is available. Even a caller reusing the exact explicit ID after the session ends or is superseded can start anew. Separately, the Shortcuts confirmation at :25–29 retries with only `confirmed=true`; if its displayed predecessor changed during the asynchronous dialog, start has no expected predecessor/revision to reject the stale confirmation.

**Tests:** `FlintSessionControllerTests.swift:215` sends the same literal ID twice while one fake live row remains. It does not test terminal/superseded replay, host recreation, AppIntent propagation, changed request fingerprint or stale confirmation.

**Fix direction:** carry a stable operation ID/fingerprint and exact expected predecessor through every route. Persist accepted results separately from the live singleton and query them after response loss/process death; reject mismatched or stale requests.

### R-29 — P1: Safari lifecycle is connected, but accepted child content is still global

**PC-11/41/62/72.** #78 wires per-owner stores through [FlintProtectionLiveLayers.swift:52](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintProtectionLiveLayers.swift#L52) and tier-aware edits through [FlintWebRestrictionEditor.swift:64](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintWebRestrictionEditor.swift#L64). Normal weakening checks affected session logical ends/rule occurrences and requires the appropriate wait/confirmation; ending one owner does not indiscriminately clear another.

Accepted children still load the global configuration at [FlintWebRestrictionLifecycle.swift:122](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintWebRestrictionLifecycle.swift#L122) rather than storing immutable child policy. The concrete missing-state and competing-writer release paths are R2-N01 and R2-N02 below; they are not counted again here. Lifecycle tests check fake per-owner writes and malformed-state preservation, but neither a frozen child policy nor callback-followed-by-host reconciliation in a different saved zone.

**Fix direction:** persist accepted per-child Safari policy and reconcile all writers from the same occurrence authority. Pending global pass behavior is excluded from this R-29 verdict.

### R-33 — P2: mixed tiers still yield an incorrect overall unblock promise

**PC-19/25/72.** [BlockDecisionEngine.kt:85](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/blocking/blocking-engine/src/main/kotlin/com/flint/peakfocus/blocking/engine/BlockDecisionEngine.kt#L85) and [BlockOverlapPolicy.kt:9](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/BlockOverlapPolicy.kt#L9) now choose the latest/unknown end for equal tiers, fixing the reported equal-tier order dependency. Both still rank tier before end. [BlockCause.kt:120](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/BlockCause.kt#L120) projects only that selected cause, and [BlockScreenState.kt:161](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/feature/feature-blockscreen/src/main/kotlin/com/flint/peakfocus/feature/blockscreen/BlockScreenState.kt#L161) labels its deadline “Unblocks”.

**Reproduce:** cover one target with a Hardcore rule ending in 5 minutes and an Easy rule ending in 20 minutes, or ongoing. The screen promises unblock in 5 minutes although the other contributor continues. This is a display defect, not an authorization downgrade. The state at `BlockScreenState.kt:38–62` has no contributor collection to disclose the other cause.

**Tests:** `OverlapHeadlineProjectionTest.kt:25` chains engine/cause/screen for equal-tier input orders. At :64 it intentionally selects finite Hardcore over ongoing Easy without asserting honest final-unblock wording. `OverlapHeadlineTest.kt:28` retaining input-list length is not a contributor-display assertion.

**Fix direction:** keep strongest tier for rights, but separately derive all-contributor end/ongoing state and attribution. Show the final protection end, or explicitly label the chosen deadline as that named cause's end.

## New findings

### R2-N01 — NEW P0: missing global Safari configuration clears an active child

**PC-11/62/71/72; ADR-012 §8.** [FlintWebRestrictions.swift:203](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintWebRestrictions.swift#L203) treats a missing `flint.webRestrictions` key as default disabled configuration without an initialized-domain completeness check. [FlintWebRestrictionLifecycle.swift:122](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintWebRestrictionLifecycle.swift#L122) loads it at use; :93–99 clears the owner's child store when disabled.

**Reproduce:** opt into Safari filtering, arm a finite Hardcore session whose Safari child is active, fault only the global configuration blob absent, then foreground/reconcile. App Group and active session remain readable. The host now clears the child before its accepted end. This is different from malformed JSON, which returns unknown and preserves protection.

**Tests:** [FlintWebRestrictionLifecycleTests.swift:52](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Tests/FlintWebRestrictionLifecycleTests.swift#L52) explicitly expects a missing key to clear; it encodes the unsafe fresh-versus-lost assumption. Per-owner teardown tests cannot establish initialized-state integrity.

**Fix direction:** validate global-domain completeness and persist the accepted child snapshot. Treat unexplained absence as unavailable and preserve verified finite last-good protection; use a positive committed disable/terminal state for removal. A deterministic initialized-loss regression can establish the decision; real Safari enforcement still needs a signed device.

### R2-N02 — NEW P0: host Safari writer ignores the rule's saved occurrence

**PC-11/56/62/72.** Native rule application at [FlintGatedRule.swift:208](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintGatedRule.swift#L208) uses the rule's saved-zone/frozen/one-shot occurrence, and schedule components at :139–145 carry its zone. Host reconciliation instead reaches [FlintWebRestrictionHostReconciler.swift:42](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintWebRestrictionHostReconciler.swift#L42) through `FlintProtectionLiveLayers.swift:52–60`, then [FlintWebRestrictionLifecycle.swift:61](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintWebRestrictionLifecycle.swift#L61) evaluates the recurring template in the current device calendar. It ignores that accepted occurrence and can clear the same per-rule Safari store.

**Reproduce:** device zone UTC, enabled Hardcore 09:00–17:00 rule saved in America/Los_Angeles, time 20:00 UTC (13:00 Los Angeles), Safari restriction opted in and a site blocked only by that child. The native occurrence is active and applies the child. Foreground host reconciliation sees 20:00 outside 09:00–17:00 in UTC and clears it. Frozen extensions and one-shot windows are other inputs the host helper does not honor; the saved-zone example alone establishes the defect.

**Tests:** [FlintRuleCallbackTests.swift:114](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Tests/FlintRuleCallbackTests.swift#L114) exercises the correct occurrence helper, not the full subsequent host path. Host lifecycle fake-clock tests do not combine these writers with disagreeing zones.

**Fix direction:** make every writer use the same accepted occurrence and child snapshot. Add native-apply → full-host-reconcile regression with the specified zone mismatch, plus frozen/one-shot variants. Native rendering and callback ordering require device evidence.

### R2-N03 — NEW P0: missing established Android rules publish Ready(empty)

**PC-11/62/71; ADR-012 §8/§12.** [DataStoreEditorCommitments.kt:547](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/core/core-datastore/src/main/kotlin/com/flint/peakfocus/core/datastore/DataStoreEditorCommitments.kt#L547) maps absent `block_rules_v1` to an empty decoded list. Its initialized checks at :561–566 validate only sidecar/version presence; `prepare` at :267–305 does not require the effective policy key. `materialize` at :358–385 can retain a finite occurrence/tombstone but has no target definition to project. The return at :46–48 is empty.

**Reproduce:** create and arm a new finite Hardcore schedule for A; retain initialized editor sidecar/version, remaining interval and Harder state, but fault only `block_rules_v1` absent. [ActiveRulesBridge.kt:64](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/app/src/main/kotlin/com/flint/peakfocus/ActiveRulesBridge.kt#L64) publishes Ready(empty). [ActiveRulesHolder.kt:89](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/core/core-model/src/main/kotlin/com/flint/peakfocus/core/model/ActiveRulesHolder.kt#L89) removes the prior contribution because the read falsely succeeded; both paths then hide/omit A's block before its finite end. An IO error would have preserved last-good protection. Warm and cold reads share the same wrong absence decision.

**Tests:** `EditorCommitmentStoreTest.kt:310` covers unsupported sidecar and missing wait state, not a missing effective definition key with a surviving initialized sidecar. Publication retry tests cover thrown failure, not incorrect successful emptiness.

**Fix direction:** validate initialized policy definitions and their sidecars together before Ready or mutation. Preserve original bytes and bounded last-good targets on unexplained loss. An authorized last deletion must persist an explicit empty collection with matching transaction state; do not invent unknown target scopes from occurrence IDs. Add production store → bridge → holder regressions for selective loss and legitimate emptiness.

## Source-closed paths and test strength

### R-01 — P0, PC-50: the PIN entry barrier

[FlintApp.swift:179](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Flint/FlintApp.swift#L179) exposes “Emergency Pass & recovery” inside the locked gate without validating a PIN. [LockedRecovery.swift:21](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Flint/LockedRecovery.swift#L21) limits the model to tier/end/pass state; its OS recovery instructions at :109 are unconditional, including unreadable session state. Reopening a PIN-locked active singleton therefore has a recovery entry while private source content remains inaccessible.

`LockedRecoveryStateTests.swift:16,34,44,61,68` exercises presentation-state logic, privacy, eligibility and logical time, not the actual modal/accessibility window. No additional source fix to the original missing entry was found. Global/rule-only pass eligibility is pending R-04/R-06; real gate ordering, keypad, VoiceOver and pass enforcement need device/UI evidence.

### R-02 / R-03 — P0: Android editor and new Timer authority

[BlocklistViewModel.kt:133](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/feature/feature-blocklist/src/main/kotlin/com/flint/peakfocus/feature/blocklist/BlocklistViewModel.kt#L133) routes changes through commitments; save/delete/toggle/Sleep/limit pairs use it at :250–313. [DataStoreEditorCommitments.kt:117](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/core/core-datastore/src/main/kotlin/com/flint/peakfocus/core/datastore/DataStoreEditorCommitments.kt#L117) derives the current decision inside the transaction; confirmation at :150–170 rereads expected definitions/occurrence and matures the exact Harder wait. Hardcore weakening at :177–185 queues without replacing effective targets. New unbounded strict rules are rejected; legacy active rights are deliberately retained.

The Timer factory [TimerModels.kt:65](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/feature/feature-blocklist/src/main/kotlin/com/flint/peakfocus/feature/blocklist/TimerModels.kt#L65) mints v2 identity; the exit predicate at :107 excludes new Hardcore. [TimerViewModel.kt:244](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/feature/feature-blocklist/src/main/kotlin/com/flint/peakfocus/feature/blocklist/TimerViewModel.kt#L244) and :303–319 use durable STOP confirmation, backed by current-tier authority at `DataStoreEditorCommitments.kt:459–466`. [BlockRulesStore.kt:94](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/core/core-datastore/src/main/kotlin/com/flint/peakfocus/core/datastore/BlockRulesStore.kt#L94) excludes v2 from the old direct deletion API. Holding the old ordinary exit cannot bypass the new rule.

`EditorCommitmentStoreTest.kt:58,84,104,131,297,378` asserts effective definitions through queues, waits, failures, recreation and stale edits. `PCEditorViewModelTest.kt:38–74` composes real view models with production stores and controlled persistence. The “during credit” editor case does not establish a live accepted credit; projection-extension tests do not prove native arm acknowledgement. These verdicts close the original editor/hold paths, not every broader PC-27/recovery/native claim. Preserve the gates; fix R2-N03 separately and obtain stationary/boundary/device evidence.

### R-13 / R-14 — P0/P1: atomic credit accounting and conservative overlap

[FlintOpenCreditService.swift:71](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintOpenCreditService.swift#L71) serializes acceptance with the shared transition, rereads context inside the ledger transaction, preregisters recovery, then durably debits. At :113–145 it revalidates the committed grant/current contributors/registration before release. [FlintOpenCreditLedger.swift:76](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintOpenCreditLedger.swift#L76) checks required counters/grants/checkpoints; :265–310 preserves legacy counts and refuses missing established state; :314–385 uses saved quota zone and elapsed checkpoints. Reboot closes release while retaining consumption. Acceptance at :417–482 handles minimum shared cap, usable last credit, active-grant reuse and bounded replay history.

For R-14, [FlintOpenCredit.swift:309](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintOpenCredit.swift#L309) reads non-Open app/domain/category owners; unknown category membership and other contributors deny before charging in [FlintOpenCreditContributors.swift:28](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintOpenCreditContributors.swift#L28). The original category-overlap path therefore returns no-credit-used rather than charging a credit that cannot provide access.

`FlintOpenCreditTests.swift:64–103` checks actual ledger bytes and registered carrier at the release seam; :113–168 checks last-credit/expiry/recreation and repeated requests; :225–242 checks unchanged counts and no release behind other/category contributors; :576–632 checks failures and callback simulations. The concurrency test uses separate ledger handles in one test process, and “restart” reconstructs handles; neither is independently running host/extension crash recovery. Migration tests validate required v2 fields and preserved bytes. These close the source accounting/no-charge paths; retain the R-12 gate/device boundary and test real opaque tokens, process death and lock contention before release.

### R-21 / R-31 / R-35 — P0/P1/P1: callback identity, strict domains and complete shield writes

[FlintRuleProtection.swift:4](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintRuleProtection.swift#L4) includes immutable incarnation/effective revision in activity identity. [FlintRuleMonitor.swift:46](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintRuleMonitor.swift#L46) only deregisters stale names; :73 guards the frozen end, :74 handles adjacent occurrences and :81 requires both the exact threshold event and activity. [FlintGatedRule.swift:299](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintGatedRule.swift#L299) does not clear a shared successor store when retiring the old registration. Busy end callbacks do nothing; positive-only restoration cannot weaken the current store. The old strengthened-rule callback reproduction is source-closed independently of R-07's remaining wall-time authority.

[FlintRuleReloadEnvironment.swift:32](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintRuleReloadEnvironment.swift#L32) rejects duplicate IDs for the whole domain, including writes. [FlintRuleRecovery.swift:42](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintRuleRecovery.swift#L42) retains original/quarantine bytes with checked references and durable file steps. Duplicate schedules/Time/Open lists cannot choose a first-record winner. A complete repair/export UI is not certified.

[FlintGatedRule.swift:262](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintGatedRule.swift#L262) assigns explicit applications, app categories, web domains and web-domain categories on every authoritative full materialization. Allow→block and block→allow remove obsolete fields. Busy positive-only union may retain conservative extras temporarily, but a successful full write no longer omits the old mode's fields.

`FlintRuleCallbackTests.swift:7–59` extends rules and invokes old/premature/mismatched callbacks while asserting no clear; :71–94 checks all four writer assignments in both mode directions. `FlintRuleMigrationTests.swift:307–324` checks duplicate arrays, preserved bytes, refused saves and no native starts/stops/clears across all three domains. These use production Core with fake native adapters, not real Apple category rendering. Preserve the source fixes and validate late/duplicate callbacks, overlapping owner continuity and upgrade/locked-data behavior on signed hardware.

### R-34 — P0, PC-15/25: the saved Time Limit tier

[BlockScreenCoordinator.kt:324](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/BlockScreenCoordinator.kt#L324) uses the saved tier, defaults to Hardcore while tier storage is unknown, and permits the global default only for Ready legacy limits without a saved record. Shared detector evaluation at :371 and stale-surface reevaluation at :416–425 use it. Grant acceptance at :658–678 refreshes the cause; [DurableGrantController.kt:112](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/DurableGrantController.kt#L112) rejects ordinary Hardcore.

The original saved-Hardcore/global-Easy reproduction now denies a break, including a caller-supplied Easy surface. `DurableCoordinatorTest.kt:68–141` calls the production coordinator/Path B factory and asserts no receipt for that scenario, stale upgrades and unreadable tier state. It injects exhausted usage and a nonmounting surface, so actual detector sampling/visible overlays are not proven. Retain this fix; R-18's daily reset and pending cause-revision transactions are separate scopes.

### R-36 / R-37 Focus — P3/P2: deadline boundary and truthful Focus copy

[FlintSessionController.swift:1031](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Packages/FlintCore/Sources/FlintCore/FlintSessionController.swift#L1031) waits through the final second outside the transition lock, then rereads state; :1077 uses zero-slack expiry. An interrupted wait cannot authorize release and interval end rearms if needed. Lateness at :1085–1089 records late warnings as well as ends; duplicate names cannot complete twice. `FlintSessionControllerTests.swift:821–878` asserts the actual fake shield-clear time, short-wait retention and 300-second late diagnostic, not only a returned label. Physical 5-/10-minute host-dead delivery remains unproven.

[FlintFocusFilter.swift:19](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Flint/Features/FocusFilter/FlintFocusFilter.swift#L19) and :29–31 state the three tiers' actual Focus-off rights. [FocusFilterController.swift:67](https://github.com/Prakashmaheshwaran/flint-app/blob/0f45cd22829c2ce6ec8de173eedb7c69b17779a9/ios-app/Flint/Features/FocusFilter/FocusFilterController.swift#L67) reaches owner/tier-aware stop; only manual foreground confirmation can finish a Harder wait. The unconditional-stop copy defect is closed. Controller tests cover tier/owner behavior, not OS Focus dispatch or system-displayed localized text. The pass/Open portions of R-37 remain pending.

## R-17 supplement: later exact merge only

**VERIFIED-CLOSED, source scope; original P2; PC-84.** This verdict applies to [#151](https://github.com/Prakashmaheshwaran/flint-app/pull/151) merge **`9554f6e7ce95e55fbf3ab3131c17323e8b92202e`**, not the main audited revision. The commit was inspected directly with `git show`; the worktree/audit pin was not advanced.

The original reproduction is start-only history with a planned deadline, then host/process absence until after that deadline. [FlintHistorySummary.swift:120](https://github.com/Prakashmaheshwaran/flint-app/blob/9554f6e7ce95e55fbf3ab3131c17323e8b92202e/ios-app/Packages/FlintCore/Sources/FlintCore/FlintHistorySummary.swift#L120) and [FocusHistoryAggregator.kt:112](https://github.com/Prakashmaheshwaran/flint-app/blob/9554f6e7ce95e55fbf3ab3131c17323e8b92202e/android-app/core/core-history/src/main/kotlin/com/flint/peakfocus/core/history/FocusHistoryAggregator.kt#L112) mark it unconfirmed and bound credited duration by subsequent lifecycle evidence; start alone gives zero. Expiry wins even if a stale active ID remains. [FlintHistorySummary.swift:194](https://github.com/Prakashmaheshwaran/flint-app/blob/9554f6e7ce95e55fbf3ab3131c17323e8b92202e/ios-app/Packages/FlintCore/Sources/FlintCore/FlintHistorySummary.swift#L194) and [HistoryReconciler.kt:116](https://github.com/Prakashmaheshwaran/flint-app/blob/9554f6e7ce95e55fbf3ab3131c17323e8b92202e/android-app/core/core-history/src/main/kotlin/com/flint/peakfocus/core/history/HistoryReconciler.kt#L116) no longer append an invented completion from expired/missing state.

[FlintHistoryTests.swift:50](https://github.com/Prakashmaheshwaran/flint-app/blob/9554f6e7ce95e55fbf3ab3131c17323e8b92202e/ios-app/Tests/FlintHistoryTests.swift#L50) and [FocusHistoryContractTest.kt:62](https://github.com/Prakashmaheshwaran/flint-app/blob/9554f6e7ce95e55fbf3ab3131c17323e8b92202e/android-app/core/core-history/src/test/kotlin/com/flint/peakfocus/core/history/FocusHistoryContractTest.kt#L62) assert zero focused duration, zero completions/streak and unconfirmed status for the exact sequence. Adjacent cases cover cold rereads/no synthetic append, a later partial evidence prefix, future/open-ended missing records, duplicate late terminal/outbox delivery, and midnight/DST splitting. They are stronger than checking only an “unconfirmed” label. Test bodies were inspected, not executed.

No residual original false-full-interval path was found. Preserve evidence-based aggregation and validate actual terminal/outbox delivery separately; this is not a blanket PC-84 certificate for every producer, crash boundary or physical protection interval.

## Entry-point and failure coverage

| Surface / variant | Traced result and boundary |
| --- | --- |
| iOS host launch, foreground, time change | Shared live reconciler reaches session, rule, Time/Open and Safari layers without opening feature tabs. This makes R-07/R-20/R-22/R2-N01/N02 reachable production paths. |
| Shortcuts / Siri / App Intents | Start/stop/Pomodoro call the shared runner/controller. Harder background confirmation cannot complete; start identity and stale confirmation remain R-25. |
| Focus filters | Actual on/off dispatcher reaches owner/tier checks; live-owner dedupe is not durable request replay. Focus copy is R-37's reviewed slice. |
| iOS widget / Live Activity | Read-only timeline/deep links; no start/stop/clear mutation route found. History/status is evidence, not native enforcement authority. |
| Shield actions | Primary app/domain credit acceptance is gated at the main pin; secondary/category actions only close. No session-stop/rule-CRUD bypass through this shield route was found. |
| Monitor callbacks | Distinct session/rule/Open-carrier prefixes; stale/unknown names do not fall through into another owner's teardown. R-20 covers absent-session cleanup; R-21 closes revision routing. Busy callbacks retain protection but need finite recovery/device acceptance. |
| Android Path A and Path B | Both call the shared coordinator; rule projection is shared and stationary evaluation/wake paths exist. R-11 lacks a timed grant; R-18 daily usage still uses raw wall day; R2-N03 publishes false successful emptiness to both. |
| Boot / time-change / process death | Android editor anchors and queued state survive recreation; receiver startup retains old evidence and wakes reconciliation. Post-unlock BOOT_COMPLETED is used; locked-boot, force-stop and OEM survival are not claimed. iOS post-reboot session authority remains R-05. |
| Storage busy / corrupt / missing | Present corruption/IO generally becomes unavailable; selective absence can instead look successful and permit release (R-19/R-20/R-22/R2-N01/N03). A lock, fake-store race test or normal reopen does not prove critical-key completeness or acknowledged crash recovery. |

## Pending at the main audited SHA

These are **pending work, not newly open findings from this pass**:

- iOS: **R-04, R-06, R-08, R-23, R-26, R-27, R-28**.
- Android: **R-09, R-10, R-15, R-16, R-30, R-32**.
- Both: **R-17** was not merged at the main pin; the exact later merge is source-closed only in the supplement.
- Outside the limited copy claims: R-24's multi-round behavior and R-37's pass/Open copy remain pending. R-24 appears PARTIAL above because the original functional finding is still present.
- Runtime enablement/wiring: H-I's iOS shield enablement is outside the pin; Android #178/#175 were open when checked. Their eventual combined merged tree requires its own verification.

## What needs physical-device evidence

This report is not permission to skip the contract's acceptance levels. The source-derived faulty decisions above can first be addressed with targeted persistence/integration tests; no device is needed to demonstrate that a missing key returns empty or that a mixed-tier headline selects the wrong end. Device sessions were prohibited for this pass.

- **Signed iOS:** Screen Time entitlement/token behavior; PIN recovery gate/curtain/VoiceOver; actual Focus on/off and Shortcuts retry/confirmation; 5-/10-minute host-killed session ends and duplicate/early/late/missed warnings; elapsed time across sleep/reboot/locked data; independent host/extension contention and process death at persist/apply boundaries; continuous coverage on strengthening; Safari same-owner/overlapping-owner and saved-zone host reconciliation.
- **Open-credit hard pre-release gate:** signed-device PC-28–PC-38 with explicit shield acceptance, usable last credit, killed-host 300-second expiry, midnight clipping, no recharge/extension on reentry, re-registration failure, lock contention, missing warning, and real category/other-owner no-charge behavior. Observe actual target access/re-shield time, not just a callback log.
- **Android, separately on Path A and Path B:** stationary-target expiry/boundaries, saved Time Limit tier at actual exhaustion, queued off/delete and Sleep pairs, explicit Open runtime after integration, cold boot/time receivers, host/service death, real UsageStats under wall/zone changes, storage unavailable/repair, visible overlay latency and accessible holds/recovery. Force-stop/OEM/protected-data behavior requires evidence on the stated device/OS.
- **History:** actual lifecycle/outbox producers, process-death delivery and deduplication; a source-closed aggregator does not prove continuous physical enforcement.

## Handoff

Eight numbered R2 batches were delivered through Orca, with P0/P1 residuals sent as confirmed. Main audit counts are **11 source closures / 10 partial / 2 reopened / 3 new P0**, with **one additional source closure for R-17 only at its separate later merge**. No product changes, local test result or physical acceptance is claimed. The accompanying PR is documentation only and is for coordinator review/merge.
