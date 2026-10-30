# v1 capability acceptance ledger

Evidence checkpoint: **2026-10-30**, merged `integration/v1` at
[`ce441b60f5cfd5d6680f9c532e977757cd3d9cdb`](https://github.com/Prakashmaheshwaran/flint-app/tree/ce441b60f5cfd5d6680f9c532e977757cd3d9cdb).
This supersedes the initial `2b91849` implementation checkpoint; dated evidence below
retains its original source and does not become a run of this whole integration tree.
This is the current Flint implementation/acceptance index. The numbered capabilities come
from the [historical Opal inventory](../research/01-opal-feature-inventory.md); competitor
pricing and platform claims have not been re-researched. All protection tiers remain free.

## Reading a status

Each row is one capability on one platform. A status applies only to the **evidenced slice**
in that row; an outstanding gap is never a passing result. The PC column maps relevant
[ADR-012 §14](../architecture/protection-contract.md#14-acceptance-cases-and-release-evidence)
requirements, **not a certification that every mapped case passes**. Evidence keys below
link source/tests, merged PRs and dated runs. Old captures keep their original source/date.
No automated or simulator result establishes physical-device enforcement.

- `ABSENT`: the named behavior has no reachable implementation in the inspected release scope.
- `IMPLEMENTED-UNTESTED`: reachable source exists; no adequate capability-specific execution found.
- `UNIT-TESTED`: the cited logic/persistence/adapter tests pass; visible OS behavior is unproven.
- `CI-EMULATOR-VERIFIED`: the cited Android hosted E2E exercised the stated visible flow.
- `SIM-VERIFIED`: the cited iOS fixture presentation was observed; real authorization/shields are unproven.
- `DEVICE-PENDING`: an explicit hardware acceptance gate remains after the cited implementation checks.
- `DEVICE-VERIFIED`: physical hardware run with exact build/device/evidence; **no rows qualify**.
- `DEFERRED-BY-DESIGN`: explicitly outside this local v1 scope or an API exclusive to the other platform.

## Inventory capabilities

| ID / capability | Platform | Status | Evidenced slice and remaining boundary | Evidence | Relevant PC IDs |
| --- | --- | --- | --- | --- | --- |
| 1.1 Blocking sessions | iOS | UNIT-TESTED | Registration-before-shield, generation/tombstone authority, durable start replay, replacement/stop and bounded reboot checkpoints are tested. R-20 pre-migration and R2p2-N02 stale-break fallback remain open; real independent processes/shields are unverified. | [IS], [IR], [BFINAL], [REDTEAM] | PC-01–10, PC-61–66, PC-74–76 |
| 1.1 Blocking sessions | Android | CI-EMULATOR-VERIFIED | UI-authored rule survives process restart; real Accessibility shield and Easy release. Service is explicitly re-enabled by the test; no automatic recovery claim. | [AE], [AG] | PC-01, PC-22, PC-61, PC-65 |
| 1.2 Block Now / instant timer | iOS | UNIT-TESTED | Finite manual/Shortcut sessions use logical elapsed expiry and bounded reboot re-anchoring; early end callbacks cannot release early. Host-dead delivery/re-registration remains a device gate; no Pomodoro chain is inferred. | [IS], [IH], [BFINAL] | PC-01–10, PC-63–66, PC-74–76 |
| 1.2 Block Now / instant timer | Android | CI-EMULATOR-VERIFIED | Actual five-minute Timer completion/early end and restart persistence have hosted history evidence with blocking off. #173 now refuses arming without a live detector and distinguishes Applying/Armed/Degraded; mount-failure health remains open. | [AH], [CFINAL] | PC-01, PC-63–64, PC-67–68, PC-84 |
| 1.3 Date-specific one-shot future session | iOS | ABSENT | The persisted optional oneShot window and arbiter fixtures exist, but production authoring cannot create one; no complete future-date/terminal run is evidenced. | [IM], [BFINAL], [REDTEAM] | PC-13–14, PC-81 |
| 1.3 Date-specific one-shot future session | Android | ABSENT | Personal Schedule/RuleDraft has weekdays and local minutes; no future absolute occurrence authoring. | [AM] | PC-13–14, PC-81 |
| 1.4 Recurring / Smart Schedules | iOS | UNIT-TESTED | Saved-zone registration/callback day checks, DST identity, gated revisions, frozen selections, trusted occurrence clocks and queued weakening are tested. R-07 no-anchor upgrade remains; native DST/overnight/capacity acceptance is unrun. | [IR], [IM], [BFINAL], [REDTEAM] | PC-02, PC-14, PC-54–58, PC-62, PC-73 |
| 1.4 Recurring / Smart Schedules | Android | UNIT-TESTED | Saved/anchored occurrence and DST/boundary reconciliation plus deferred editor changes are tested. Earlier stationary activation is local emulator evidence, not hosted schedule E2E; remaining migration/Time-Limit-day work is explicit below. | [AC], [CFINAL] | PC-14, PC-54–58, PC-61–62, PC-77 |
| 1.5 Arbitrary future start beyond 24 h | iOS | ABSENT | Weekly recurrence can reach later weeks; arbitrary date-specific delayed first activation is absent. | [IM] | PC-81 |
| 1.5 Arbitrary future start beyond 24 h | Android | ABSENT | No arbitrary future date in release personal authoring; debug managed fields are not personal scheduling. | [AM] | PC-81 |
| 1.6 Block List | iOS | UNIT-TESTED | Opaque selection persistence/caps, immutable active targets and read-only App/Site detail are tested. Real picker/category membership and shields remain device gates. | [IR], [IM], [BFINAL], [UXFINAL] | PC-02, PC-48–50, PC-80 |
| 1.6 Block List | Android | CI-EMULATOR-VERIFIED | Hosted app pick/save/restart/shield flow; merged Apps and sites/detail uses the read-only per-target reader. Full catalog/category support and real picked-detail hardware acceptance are not established. | [AE], [AS], [CFINAL], [UXFINAL] | PC-01, PC-48–52, PC-80 |
| 1.7 Allow List | iOS | UNIT-TESTED | #218 refuses new/edited empty allow lists, including Sleep Full Assist without an allow group. Legacy saved empty lists keep running with a needs-attention note. Phone/default calling/in-call/SOS under .all(except:) is DEVICE-PENDING; move to option B if unsafe. Immediate Allow List sessions remain absent. | [IM], [IR], [BFINAL], [DEVICE] | PC-24–26, PC-48–50, PC-52, PC-80 |
| 1.7 Allow List | Android | UNIT-TESTED | App-selection inversion, all-cause terminal floor and unresolved calling/Home-role handling are tested. Domain allow-only is unsupported; actual default/custom dialer/Home/in-call remains device-pending. | [AS], [AM], [CFINAL] | PC-24–26, PC-48–52, PC-80 |
| 1.8 Website/domain blocking | iOS | UNIT-TESTED | #78 production per-cause Safari apply/clear, #197 established-key handling and #220 frozen child policies are merged/tested. Real Safari/token/category/overlap/pass cleanup and Private Browsing effects remain unverified on hardware. | [IW], [BFINAL], [UXFINAL] | PC-24–26, PC-32–33, PC-72 |
| 1.8 Website/domain blocking | Android | UNIT-TESTED | #153 source-pinned known-browser matrix, throttled same-tab reads and degraded/unconfirmed/unsupported health are tested. Path B is app-only; no observed complete browser/version/private-mode matrix. | [AW], [CFINAL], [UXFINAL] | PC-24–26, PC-32, PC-61, PC-77 |
| 1.9 Safari private-browsing/history restrictions | iOS | UNIT-TESTED | Cause-owned Safari filtering and immutable child policy are wired/tested; Private Browsing/history lock is an expected OS side effect requiring signed-device observation, not proven by the writer or CI. | [IW], [BFINAL], [DEVICE] | PC-41, PC-72, PC-77 |
| 1.9 Browser private-browsing/history restrictions | Android | ABSENT | URL inspection does not disable incognito or lock browser history. | [AW] | —; platform divergence |
| 1.10 Saved groups / routine presets | iOS | UNIT-TESTED | Saved groups/presets and frozen active selections are tested; editing a group does not authorize changing an active commitment. Social detox retains a 23:59–00:00 gap; no sync. | [IM], [BFINAL] | PC-13, PC-80 |
| 1.10 Saved groups / routine presets | Android | UNIT-TESTED | Five presets, named groups and transactional gated apply/delete are tested. General editor-domain archive/template migration remains parked; full current UI group flow is unobserved. | [AM], [CFINAL] | PC-13, PC-80 |
| 2.1 Time Limits | iOS | UNIT-TESTED | Threshold registration/observation, current-tier editor rights and trusted rule boundaries are tested. Real threshold delivery/reset remains unrun; R-07 no-anchor upgrade and R-28 remainder prevent whole-contract acceptance. | [IR], [IM], [BFINAL], [REDTEAM] | PC-15, PC-37, PC-54–58, PC-61–62 |
| 2.1 Time Limits | Android | CI-EMULATOR-VERIFIED | Hosted shield uses an already-exhausted zero budget. Saved per-limit tiers are consumed by both detectors; positive-budget latency has narrower local observations. R-18 daily reset/measurement and lifecycle/migration work are tracked separately. | [AE], [AC], [CFINAL], [REDTEAM] | PC-15, PC-37, PC-54–58, PC-61–62 |
| 2.2 Open Limits | iOS | DEVICE-PENDING | Coordinator R-12 bounded-grant decision: source-complete, runtime DEVICE-PENDING; #155 atomic up-to-300 s grants and #189 enabled acceptance. Separate first-use import defect R2p2-N03 remains open. Hard signed-device PC-28..38 gate: killed-host 300 s expiry, midnight, re-registration, lock contention; one-line revert plus fallback copy on failure. | [IO], [BFINAL], [DEVICE], [REDTEAM] | PC-16, PC-28–38, PC-59–60, PC-75–76 |
| 2.2 Open Limits | Android | UNIT-TESTED | #160/#175/#178 wire explicit up-to-300 s credits, minimum covering cap, durable per-target quota/import, trusted midnight clipping and both detectors; foreground entry does not charge. R2p2-N01 stale Time-Limit overlap race remains; physical expiry/midnight is unverified. | [AG], [AP], [CFINAL], [REDTEAM] | PC-16, PC-28–38, PC-59–60 |
| 2.3 Easy / Harder / Hardcore | iOS | UNIT-TESTED | Session/rule/limit/Sleep mutation authority, persisted Harder waits and scoped five-minute ordinary breaks are merged/tested. R2p2-N02 stale fallback and R-07 upgrade residual prevent universal closure. | [IS], [IP], [BFINAL], [REDTEAM] | PC-04–27, PC-67–68, PC-79 |
| 2.3 Easy / Harder / Hardcore | Android | UNIT-TESTED | #139 durable editor/deferred rights and new/legacy Timer split are merged; #156/#175/#178 wire per-limit tiers, global pass and scoped breaks/credits. R-16 legacy Harder friction remains a distinct residual. | [AP], [AG], [CFINAL], [REDTEAM] | PC-04–27, PC-67–68, PC-79 |
| 2.4 Deep Focus / Hardcore commitment | iOS | UNIT-TESTED | Finite session/rule commitment, shared mutation authority and tier-gated exits are tested; ordinary weakening is denied/deferred. Open red-team variants and signed-device safety/expiry remain gates. Removal-consent runtime is separately tracked below. | [IS], [IP], [BFINAL], [REDTEAM] | PC-04–16, PC-25–27, PC-68, PC-78–79 |
| 2.4 Deep Focus / Hardcore commitment | Android | CI-EMULATOR-VERIFIED | Hosted Hardcore shield/pass flow now reviews and confirms global recovery; #139 new Timer denies ordinary hold while grandfathered exits remain. This does not certify every editor, mount or OEM route. | [AE], [AP], [CFINAL] | PC-25, PC-41, PC-51, PC-67–68 |
| 2.5 Anti-bypass / clock / removal protections | iOS | UNIT-TESTED | Actual expiry callers use elapsed time and bounded reboot checkpoints; shared ledger/PIN/generation tests exist. R-20 pre-migration and R-07 no-anchor remain; optional deletion guard runtime is separate. Revocation stays reachable; no reboot tamper-proof claim. | [IS], [IP], [PIN], [BFINAL], [REDTEAM] | PC-48–60, PC-69–71, PC-77–79 |
| 2.5 Anti-bypass / clock / removal protections | Android | UNIT-TESTED | Anchored rules, boot-bound grants, strict initialized accounting and lifecycle time confidence are tested. Daily Time-Limit reset and R2p2-N01 remain separately tracked. No Settings/uninstall/disable interception or cross-reboot tamper-proof guarantee. | [AS], [AC], [AG], [CFINAL], [REDTEAM] | PC-48–60, PC-69–71, PC-77 |
| 2.6 Emergency Pass | iOS | UNIT-TESTED | #161 commits rolling-seven-day use and global 60-minute recovery before release, ends transients and suspends/resumes rules; PIN-free minimal recovery is merged. R-04 retained legacy predecessor can still make scope unavailable; hardware resumption/crash acceptance is unrun. | [IP], [IS], [BFINAL], [DEVICE], [REDTEAM] | PC-39–47, PC-59–60, PC-69, PC-83 |
| 2.6 Emergency Pass | Android | CI-EMULATOR-VERIFIED | #175 live global 60-minute recovery and rolling-use import, #205 bounded recovery handoff. Hosted Hardcore test performs review/review/confirm and observes release; complete 60-minute resumption/reboot/multi-window/OEM acceptance is unrun. | [AE], [AP], [AG], [CFINAL] | PC-39–47, PC-59–60, PC-69, PC-83 |
| 3.1 iOS Focus Filter | iOS | UNIT-TESTED | Owned finite Focus sessions share mutation authority, replay tokens and strict-tier errors; Easy Focus-off differs from strict finite stop. Real OS Focus delivery is unobserved. | [IS], [BFINAL] | PC-04, PC-07–09, PC-79 |
| 3.1 iOS Focus Filter | Android | DEFERRED-BY-DESIGN | Apple-only API; no Android system-focus equivalent promised. | [SCOPE], [AM] | PC-08–09 only for future equivalent |
| 3.2 Shortcuts / Siri / contextual automation | iOS | UNIT-TESTED | Start Session/Start Pomodoro/Stop Session share session authority and durable replay handling. Pomodoro still starts one round; Siri discovery/background execution need hardware, and contextual triggers belong to Apple Shortcuts. | [IS], [IM], [BFINAL] | PC-03–10, PC-63–66 |
| 3.2 Shortcuts / contextual automation | Android | ABSENT | No release automation/location/DND API; DEBUG seed receiver is test infrastructure. | [AM] | PC-03–10 for any future route |
| 3.3 Sleep / Morning Assist | iOS | SIM-VERIFIED | Dated Large/AX5 Sleep fixtures; #187/#224 preserve/show Rules tier, #218 refuses new empty Full Assist choices. Actual overnight/morning/pass and legacy safety acceptance remain unrun; audio is separate. | [SLEEP], [IR], [BFINAL] | PC-14, PC-27, PC-41–42, PC-54–58, PC-82–83 |
| 3.3 Sleep / Morning Assist | Android | UNIT-TESTED | #201/#207 preserve independent explicit night/morning tiers and first-enable semantics; #172 has dated large-text/compact emulator presentation. Overnight/boundary/OEM enforcement remains unrun. | [AM], [CFINAL], [UXFINAL] | PC-14, PC-41–42, PC-54–58, PC-82–83 |
| 3.4 Cross-device / desktop blocking | iOS | DEFERRED-BY-DESIGN | Local-only release; no rule sync, Mac client or browser extension. | [SCOPE] | —; out of personal v1 scope |
| 3.4 Cross-device / desktop blocking | Android | DEFERRED-BY-DESIGN | Debug local connected authoring is excluded from release and does not activate managed blocking. | [SCOPE], [AR] | —; out of personal v1 scope |
| 3.5 Android implementation / parity | iOS | DEFERRED-BY-DESIGN | Android aggregate is recorded on Android rows; no iOS parity claim inferred. | [SCOPE] | —; platform-specific aggregate |
| 3.5 Android implementation / parity | Android | DEVICE-PENDING | Native core, history and release-code hosted smoke exist; unresolved rows here and stock/OEM acceptance prevent full parity/release certification. | [AE], [AH], [AR], [DEVICE] | PC-01–84 as applicable |
| Gamification / streaks / rewards | iOS | UNIT-TESTED | #151/#202/#219 count confirmed outcomes/coverage conservatively; a start/deadline alone is Unconfirmed with zero completion/streak credit. No Gems/achievement catalog or rewards engine. | [IH], [FTRUTH] | PC-84 for history-derived streak only |
| Gamification / streaks / rewards | Android | UNIT-TESTED | Confirmed local history drives streaks; missing-terminal/uncertain-gap cases never invent completion/streak credit. No Gems/achievement catalog or rewards engine. | [AH], [FTRUTH] | PC-84 for history-derived streak only |

## Flint additions and explicit sub-capabilities

| ID / capability | Platform | Status | Evidenced slice and remaining boundary | Evidence | Relevant PC IDs |
| --- | --- | --- | --- | --- | --- |
| F01 Full repeated Pomodoro focus/rest chain | iOS | ABSENT | Intent explicitly exposes one focus round; legacy cadence arguments do not chain or overwrite saved in-app cadence. No repeated parent/round execution. | [IM], [BFINAL], [REDTEAM] | PC-27, PC-63–64, PC-84 |
| F01 Full repeated Pomodoro focus/rest chain | Android | ABSENT | Native finite Timer exists; repeated focus/rest configuration/execution absent. | [AM] | PC-27, PC-63–64, PC-84 |
| F02 Immediate Allow List session | iOS | ABSENT | Allow List is schedule/Sleep-specific at this checkpoint. | [IM], [IS] | PC-01, PC-48–50 |
| F02 Immediate Allow List session | Android | ABSENT | Timer targets apps as a block list; separate rule editor supports Allow List. | [AM] | PC-01, PC-48–50 |
| F03 App-open PIN | iOS | SIM-VERIFIED | PIN create/relaunch/wrong-PIN/unlock/removal fixtures pass at default/AX5 in exact #223 UI run; #152 exposes minimal recovery without private content. No OS authority or physical VoiceOver proof. | [PIN], [UXFINAL], [BFINAL] | PC-50; recovery must remain reachable |
| F03 App-open PIN | Android | ABSENT | No release PIN/app-lock flow in Settings/Timer navigation. | [AM] | PC-50 if added |
| F04 Safety-floor hardware acceptance | iOS | DEVICE-PENDING | #218 approved floor scope: reject new empty allow lists; retain legacy ones with attention. Signed-device Phone/default calling app/in-call/SOS under .all(except:) and opaque/all-category shields is a release gate; move to option B if shielding occurs. | [IR], [BFINAL], [DEVICE] | PC-48–52, PC-77 |
| F04 Safety-floor hardware acceptance | Android | DEVICE-PENDING | Terminal floor is tested before every cause, with #188/#215 unresolved-role/fresh-mount handling. Real default/custom dialer, Home, in-call and OEM permission surfaces remain unverified. | [AS], [CFINAL], [DEVICE] | PC-48–52, PC-77 |
| F05 Durable history / Today / Complete / Insights | iOS | SIM-VERIFIED | Dated seeded presentation remains fixture evidence. #151/#190/#202/#210/#219 test confirmed vs Unconfirmed, logical clear/end, stable replay IDs and conservative uncertain gaps. Durable typed iOS break projection and host-dead hardware history remain open. | [IH], [FTRUTH], [BFINAL] | PC-63–66, PC-70–71, PC-84 |
| F05 Durable history / Today / Complete / Insights | Android | CI-EMULATOR-VERIFIED | Real hosted Timer completion/early-end/restart evidence plus #165/#167 durable terminal/pass/break outbox projection and #217/#219 confidence/gap tests. Duplicate terminal arbitration is still in flight; no complete operation-history/hardware claim. | [AH], [FTRUTH], [CFINAL] | PC-63–66, PC-70–71, PC-84 |
| F06 Optional focus-end notification | iOS | UNIT-TESTED | Preference/OS authorization and generic planned-end schedule/cancel tests. Host-dead cancellation and denied/late delivery remain device gates; delivery is not confirmed completion. | [IH], [FTRUTH], [DEVICE] | PC-63–64, PC-84 |
| F06 Optional focus-end notification | Android | UNIT-TESTED | Inexact alarm/journal recovery and distinct Focus end reminders channel are tested. Denied/channel-disabled/late/Doze delivery is unrun; reminder delivery is not completion evidence. | [AN], [DEVICE] | PC-63–64, PC-84 |
| F07 Read-only widgets / glance | iOS | UNIT-TESTED | #163 production commit publisher and #171 strict-validUntil reader/queue are merged/tested; shared shipping-view previews are labelled fixtures. No SpringBoard widget placement, host-dead freshness or physical delivery proof. | [IK], [GLANCE] | PC-61, PC-73, PC-84 |
| F07 Read-only widgets / glance | Android | CI-EMULATOR-VERIFIED | #169 wires live Hub/Path B leases and actual installed minified widget worker/unavailable/Open Flint test passes. Protected/history images use labelled fixtures; no hardware launcher/permission/reboot proof. #206 contextual health is tested. | [AK], [GLANCE] | PC-61, PC-73, PC-84 |
| F08 Live Activity / ongoing focus status | iOS | UNIT-TESTED | Default-off finite-armed ActivityKit plans, strict staleness, dismissal/opt-out races and runtime publish-to-read mapping are tested. Shared-view previews do not prove delivered Activity/Dynamic Island or lock-screen privacy. | [IK], [GLANCE] | PC-61, PC-73 |
| F08 Live Activity / ongoing focus status | Android | UNIT-TESTED | #169/#206 production publisher/service customization and contextual health are wired/tested. Optional Path A ID1002, mandatory Path B ID1001 and end reminders remain distinct; notification fixture pixels do not prove real policy/OEM delivery. | [AK], [GLANCE] | PC-61, PC-73, PC-77 |
| F09 Offline privacy policy / licenses screens | iOS | ABSENT | You has a privacy note and About link; full bundled legal screens are absent. Store drafts are not in-app legal screens. | [IL] | —; release transparency |
| F09 Offline privacy policy / licenses screens | Android | UNIT-TESTED | Native offline legal navigation, retry/back/restoration, generated release notices and packaged policy checks. | [AL] | —; release transparency |
| F10 Release packaging / store delivery | iOS | DEVICE-PENDING | Unsigned Debug/Release/privacy/fixture CI and #223 presentation are evidenced at their exact sources. Six signed bundles/profiles, five Family Controls approvals, hardware, archive/TestFlight and store delivery remain owner gates. | [ID], [IK], [UXFINAL], [STORE] | PC-48–84 where device required |
| F10 Release packaging / store delivery | Android | CI-EMULATOR-VERIFIED | Installed minified release-code rule/shield/break plus unavailable widget smoke pass at exact PR merge refs; APK/AAB/local-first checks are separate. Final owner signer/Play splits/update/hardware remain open; no old hash is a final-candidate attestation. | [AR], [GLANCE], [STORE] | PC-01, PC-22, PC-51; bounded release smoke |
| F11 Bundled sleep audio / stories / meditation | iOS | DEFERRED-BY-DESIGN | Content production excluded from local v1. Slow Uplift preference does not imply audio/reminders. | [SCOPE], [IM] | — |
| F11 Bundled sleep audio / stories / meditation | Android | DEFERRED-BY-DESIGN | No media delivery in local v1. | [SCOPE], [AM] | — |

## Evidence index

All PRs below are merged into this checkpoint unless explicitly described as outstanding.
Runs were read back through GitHub Actions; a run belongs to its linked PR head/merge tree,
not automatically to every later integration commit. Source links are repository-relative
for review against the checkpoint above. Test files establish scope; green suite totals are
not a substitute for a matching capability assertion.

- <a id="is"></a>**IS — iOS sessions:** [#91](https://github.com/Prakashmaheshwaran/flint-app/pull/91),
  [#130](https://github.com/Prakashmaheshwaran/flint-app/pull/130);
  [controller](../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintSessionController.swift),
  [timing](../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintSessionTiming.swift),
  [tests](../../ios-app/Tests/), [CI 40776064823](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40776064823).
- <a id="ip"></a>**IP — iOS policy/ledger:** [#112](https://github.com/Prakashmaheshwaran/flint-app/pull/112),
  [#120](https://github.com/Prakashmaheshwaran/flint-app/pull/120);
  [policy tests](../../ios-app/Tests/FlintProtectionPolicyTests.swift),
  [ledger tests](../../ios-app/Tests/FlintProtectionLedgerTests.swift),
  [CI 40769576347](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40769576347).
  Pure/transaction decisions do not prove all live callers.
- <a id="ir"></a>**IR — iOS recovery:** [#82](https://github.com/Prakashmaheshwaran/flint-app/pull/82),
  [#87](https://github.com/Prakashmaheshwaran/flint-app/pull/87);
  [reconciler tests](../../ios-app/Tests/FlintProtectionReconcilerTests.swift),
  [recovery safety tests](../../ios-app/Tests/FlintProtectionRecoverySafetyTests.swift),
  [CI 40780953866](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40780953866).
- <a id="im"></a>**IM — iOS existing model/entry scope:** [schedule model](../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintScheduleRule.swift),
  [schedule controller](../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintSchedulesController.swift),
  [Shortcuts runner](../../ios-app/Flint/Features/Shortcuts/FlintShortcutsRunner.swift),
  [Sleep controller](../../ios-app/Flint/Features/Sleep/SleepModeController.swift),
  [tests](../../ios-app/Tests/FlintRoutinePresetsTests.swift).
- <a id="iw"></a>**IW — iOS web:** [configuration/tests](../../ios-app/Tests/FlintWebRestrictionsTests.swift),
  [writer](../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintWebRestrictions.swift).
  [#78](https://github.com/Prakashmaheshwaran/flint-app/pull/78),
  [#197](https://github.com/Prakashmaheshwaran/flint-app/pull/197) and
  [#220](https://github.com/Prakashmaheshwaran/flint-app/pull/220) are merged;
  see BFINAL for frozen policies and exact-source CI, not hardware Safari proof.
- <a id="io"></a>**IO — iOS Open Limits:** [current implementation](../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintOpenLimit.swift),
  [accounting tests](../../ios-app/Tests/FlintOpenLimitsTests.swift), [ShieldAction](../../ios-app/Extensions/ShieldActionExtension/FlintShieldAction.swift).
- <a id="pin"></a>**PIN:** [dated simulator evidence](../verification/f2-ios-pin-lifecycle-2026-10-24/README.md),
  [validation evidence](../verification/f3-ios-pin-validation-2026-10-24/README.md),
  [#116](https://github.com/Prakashmaheshwaran/flint-app/pull/116).
- <a id="sleep"></a>**SLEEP:** [#110](https://github.com/Prakashmaheshwaran/flint-app/pull/110),
  [dated fixtures](../verification/h-ios-sleep-2026-10-28/README.md).
- <a id="ih"></a>**IH — iOS history:** [#90](https://github.com/Prakashmaheshwaran/flint-app/pull/90),
  [#117](https://github.com/Prakashmaheshwaran/flint-app/pull/117), [#132](https://github.com/Prakashmaheshwaran/flint-app/pull/132),
  [CI 40775660771](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40775660771),
  [fixture captures](../verification/f-ios-history-2026-10-28/README.md),
  [history contract](../architecture/focus-history.md), [source/tests](../../ios-app/Tests/FocusHistoryPresentationTests.swift).
- <a id="ae"></a>**AE — Android hosted E2E:** [#83](https://github.com/Prakashmaheshwaran/flint-app/pull/83),
  [suite/boundaries](../verification/android-e2e.md),
  [green 5-case run 40779002986](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40779002986).
  The four shield/consent cases and real Timer history case have distinct fixtures and limits.
- <a id="as"></a>**AS — Android safety floor:** [#93](https://github.com/Prakashmaheshwaran/flint-app/pull/93),
  [#105 guard removal](https://github.com/Prakashmaheshwaran/flint-app/pull/105),
  [coordinator matrix](../../android-app/blocking/blocking-overlay/src/testDebug/kotlin/com/flint/peakfocus/blocking/overlay/SafetyFloorCoordinatorMatrixTest.kt),
  [runtime floor](../../android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/SafetyFloorGate.kt),
  [CI 40753783076](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40753783076).
- <a id="ac"></a>**AC — Android continuous evaluation:** [#88](https://github.com/Prakashmaheshwaran/flint-app/pull/88),
  [CI 40776050830](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40776050830),
  [#146 local emulator evidence](../verification/android-continuous-eval-2026-10-28/README.md).
  Path B budget had a +20.608 s first-visible upper bound; cold-start ANR under load is unresolved.
- <a id="ag"></a>**AG — Android durable grants:** [#84](https://github.com/Prakashmaheshwaran/flint-app/pull/84),
  [#136](https://github.com/Prakashmaheshwaran/flint-app/pull/136),
  [CI 40778888207](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40778888207),
  [live coordinator](../../android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/BlockScreenCoordinator.kt).
- <a id="ap"></a>**AP — Android pure policy:** [#118](https://github.com/Prakashmaheshwaran/flint-app/pull/118),
  [policy fixtures](../../android-app/blocking/blocking-engine/src/test/kotlin/com/flint/peakfocus/blocking/engine/ProtectionPoliciesTest.kt),
  [CI 40768045817](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40768045817).
  This PR explicitly does not wire every live caller.
- <a id="am"></a>**AM — Android personal model/UI scope:** [model](../../android-app/core/core-model/src/main/kotlin/com/flint/peakfocus/core/model/Models.kt),
  [draft](../../android-app/feature/feature-blocklist/src/main/kotlin/com/flint/peakfocus/feature/blocklist/RuleDraft.kt),
  [Timer](../../android-app/feature/feature-blocklist/src/main/kotlin/com/flint/peakfocus/feature/blocklist/TimerScreen.kt),
  [feature tests](../../android-app/feature/feature-blocklist/src/test/kotlin/com/flint/peakfocus/feature/blocklist/),
  [manifest](../../android-app/app/src/main/AndroidManifest.xml).
- <a id="aw"></a>**AW — Android web scope:** [address adapters](../../android-app/blocking/blocking-engine/src/main/kotlin/com/flint/peakfocus/blocking/engine/BrowserAddressBars.kt),
  [matching tests](../../android-app/blocking/blocking-engine/src/test/kotlin/com/flint/peakfocus/blocking/engine/WebsiteMatchingTest.kt).
- <a id="ah"></a>**AH — Android history:** [#89](https://github.com/Prakashmaheshwaran/flint-app/pull/89),
  [#95](https://github.com/Prakashmaheshwaran/flint-app/pull/95), [#129](https://github.com/Prakashmaheshwaran/flint-app/pull/129),
  [#125 real Timer run](https://github.com/Prakashmaheshwaran/flint-app/pull/125),
  [retained E2E/source hashes](../verification/f-android-history-2026-10-28/README.md),
  [aggregator/tests](../../android-app/core/core-history/src/test/kotlin/com/flint/peakfocus/core/history/FocusHistoryContractTest.kt).
- <a id="an"></a>**AN — Android notification:** [#97](https://github.com/Prakashmaheshwaran/flint-app/pull/97),
  [history source](../../android-app/core/core-history/src/main/kotlin/com/flint/peakfocus/core/history/),
  [CI 40774519151](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40774519151).
- <a id="ik"></a>**IK — iOS glance/release:** [#127](https://github.com/Prakashmaheshwaran/flint-app/pull/127),
  [CI 40776986262](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40776986262),
  [glance tests](../../ios-app/Tests/FlintGlanceTests.swift),
  [privacy retention](../verification/ios-history-privacy-2026-10-28/release-retention.json).
- <a id="ak"></a>**AK — Android glance:** [#126](https://github.com/Prakashmaheshwaran/flint-app/pull/126),
  [CI 40778992856](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40778992856),
  [glance source/tests](../../android-app/app/src/main/kotlin/com/flint/peakfocus/glance/),
  [current status API #131](https://github.com/Prakashmaheshwaran/flint-app/pull/131).
- <a id="il"></a>**IL — iOS legal scope:** [You/Settings](../../ios-app/Flint/Features/Settings/SettingsView.swift),
  [bundled resources](../../ios-app/project.yml).
- <a id="al"></a>**AL — Android legal:** [#102](https://github.com/Prakashmaheshwaran/flint-app/pull/102),
  [legal implementation](../../android-app/feature/feature-settings/src/main/kotlin/com/flint/peakfocus/feature/settings/),
  [CI 40759739496](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40759739496).
- <a id="id"></a>**ID — iOS release:** merged [#71](https://github.com/Prakashmaheshwaran/flint-app/pull/71),
  [#74](https://github.com/Prakashmaheshwaran/flint-app/pull/74), [#77](https://github.com/Prakashmaheshwaran/flint-app/pull/77),
  [#85](https://github.com/Prakashmaheshwaran/flint-app/pull/85), [#134](https://github.com/Prakashmaheshwaran/flint-app/pull/134),
  [release gate](../store/ios/release-ci.md), [privacy inventory](../store/ios/privacy-api-inventory.md).
- <a id="ar"></a>**AR — Android release:** merged [#70](https://github.com/Prakashmaheshwaran/flint-app/pull/70),
  [#86](https://github.com/Prakashmaheshwaran/flint-app/pull/86), [#92](https://github.com/Prakashmaheshwaran/flint-app/pull/92),
  [#103](https://github.com/Prakashmaheshwaran/flint-app/pull/103),
  [installed release run 40768362729](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40768362729),
  [AAB/signing boundaries](../store/android/README.md).
- <a id="scope"></a>**SCOPE:** [v1 build plan](../roadmap/v1-build-plan.md),
  [ADR-012](../architecture/protection-contract.md), [ADR-010/011 debug boundary](../architecture/00-architecture-decisions.md).
- <a id="device"></a>**DEVICE:** [consolidated runnable plan](device-acceptance-plan.md),
  [iOS detail](../verification/ios-device-acceptance.md),
  [Android detail](../verification/android-device-acceptance.md).
- <a id="store"></a>**STORE:** [single human submission checklist](../store/README.md).

- <a id="bfinal"></a>**BFINAL — final iOS source integration:** merged
  [#162](https://github.com/Prakashmaheshwaran/flint-app/pull/162) editor authority,
  [#185](https://github.com/Prakashmaheshwaran/flint-app/pull/185) trusted rule clocks,
  [#196](https://github.com/Prakashmaheshwaran/flint-app/pull/196) generation/reboot/replay
  ([head 49df18f CI](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40992496167)),
  [#161](https://github.com/Prakashmaheshwaran/flint-app/pull/161) global recovery
  ([head 91399ba CI](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40979063744)),
  [#155](https://github.com/Prakashmaheshwaran/flint-app/pull/155) credit source
  ([head 0d82c20 CI](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40959163475)),
  [#189](https://github.com/Prakashmaheshwaran/flint-app/pull/189) enabled shield action,
  [#214](https://github.com/Prakashmaheshwaran/flint-app/pull/214)/[#221](https://github.com/Prakashmaheshwaran/flint-app/pull/221) scoped breaks
  ([head 684b80c CI](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/41004111878)),
  [#218](https://github.com/Prakashmaheshwaran/flint-app/pull/218) empty-list floor,
  [#197](https://github.com/Prakashmaheshwaran/flint-app/pull/197)/[#220](https://github.com/Prakashmaheshwaran/flint-app/pull/220) Safari authority
  ([head 54a0f70 CI](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/41006554580)),
  [#224](https://github.com/Prakashmaheshwaran/flint-app/pull/224) Sleep tier.
  Production authorities are [session](../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintSessionController.swift),
  [rule arbiter](../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintRuleArbiter.swift),
  [pass](../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintEmergencyPass.swift),
  [credits](../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintOpenCredit.swift),
  [breaks](../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintOrdinaryBreaks.swift).
  Open variants below qualify every claim; injected adapters are not separate native processes.
- <a id="cfinal"></a>**CFINAL — final Android runtime:** merged
  [#139](https://github.com/Prakashmaheshwaran/flint-app/pull/139) editor rights,
  [#156](https://github.com/Prakashmaheshwaran/flint-app/pull/156) per-limit runtime tier,
  [#170](https://github.com/Prakashmaheshwaran/flint-app/pull/170) anchored rules,
  [#175](https://github.com/Prakashmaheshwaran/flint-app/pull/175)/[#205](https://github.com/Prakashmaheshwaran/flint-app/pull/205) global recovery,
  [#160](https://github.com/Prakashmaheshwaran/flint-app/pull/160)/[#178](https://github.com/Prakashmaheshwaran/flint-app/pull/178) credit ledger/runtime
  ([head 0866e77 unit/build](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40988381206),
  [hosted E2E](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40988381310)),
  [#192](https://github.com/Prakashmaheshwaran/flint-app/pull/192) missing-key integrity,
  [#173](https://github.com/Prakashmaheshwaran/flint-app/pull/173) Timer arm/refusal
  ([head 065dc9d CI](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/41007079614)),
  [#188](https://github.com/Prakashmaheshwaran/flint-app/pull/188)/[#215](https://github.com/Prakashmaheshwaran/flint-app/pull/215) floor,
  [#193](https://github.com/Prakashmaheshwaran/flint-app/pull/193) Path B off-main startup,
  [#153](https://github.com/Prakashmaheshwaran/flint-app/pull/153) website matrix,
  [#201](https://github.com/Prakashmaheshwaran/flint-app/pull/201)/[#207](https://github.com/Prakashmaheshwaran/flint-app/pull/207) Sleep,
  [#213](https://github.com/Prakashmaheshwaran/flint-app/pull/213) mixed-tier end.
  [Actual coordinator](../../android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/BlockScreenCoordinator.kt),
  [transaction store](../../android-app/core/core-datastore/src/main/kotlin/com/flint/peakfocus/core/datastore/ProtectionTransactionStore.kt),
  [Timer arm reducer](../../android-app/feature/feature-blocklist/src/main/kotlin/com/flint/peakfocus/feature/blocklist/TimerArming.kt).
  Force-stop unbinds Accessibility: #173 refuses a new protected Timer without a live detector;
  test setup re-enabling the service does not establish force-stop survival.
- <a id="ftruth"></a>**FTRUTH — confirmed history:** merged
  [#151](https://github.com/Prakashmaheshwaran/flint-app/pull/151),
  [#190](https://github.com/Prakashmaheshwaran/flint-app/pull/190),
  [#199](https://github.com/Prakashmaheshwaran/flint-app/pull/199),
  [#202](https://github.com/Prakashmaheshwaran/flint-app/pull/202),
  [#165](https://github.com/Prakashmaheshwaran/flint-app/pull/165)/[#167](https://github.com/Prakashmaheshwaran/flint-app/pull/167),
  [#210](https://github.com/Prakashmaheshwaran/flint-app/pull/210),
  [#217](https://github.com/Prakashmaheshwaran/flint-app/pull/217)/[#219](https://github.com/Prakashmaheshwaran/flint-app/pull/219).
  #219 head `6f5ffe8`: [iOS unit/unsigned gate](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/41003684744),
  [Android variant/build gate](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/41003684769),
  [12-case E2E](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/41003684791).
  [Swift consumer](../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintHistorySummary.swift),
  [Kotlin consumer](../../android-app/core/core-history/src/main/kotlin/com/flint/peakfocus/core/history/FocusHistoryAggregator.kt).
  Missing terminals cannot become completed/streaks; uncertain gaps reduce a conservative bound.
  Counts belong to distinct runs/Gradle variants and are not additive.
- <a id="glance"></a>**GLANCE — publishers and delivery boundaries:** merged
  [#163](https://github.com/Prakashmaheshwaran/flint-app/pull/163)/[#171](https://github.com/Prakashmaheshwaran/flint-app/pull/171)
  ([head f19e320 CI](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40797573355));
  [#157](https://github.com/Prakashmaheshwaran/flint-app/pull/157)/[#158](https://github.com/Prakashmaheshwaran/flint-app/pull/158)/[#169](https://github.com/Prakashmaheshwaran/flint-app/pull/169)
  ([final head ffaebff installed release smoke](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40972039921));
  [#184](https://github.com/Prakashmaheshwaran/flint-app/pull/184)/[#206](https://github.com/Prakashmaheshwaran/flint-app/pull/206)
  ([head bc4c992 CI](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40992674646)).
  [Actual installed widget test](../../android-app/app/src/androidTest/kotlin/com/flint/peakfocus/e2e/GlanceStartupWidgetTest.kt)
  proves unavailable/Open Flint through a real AppWidgetHost; protected/history/notification
  fixture pixels and shared iOS view previews do not prove live enforcement or hardware delivery.
- <a id="uxfinal"></a>**UXFINAL — presentation:** merged
  [#145](https://github.com/Prakashmaheshwaran/flint-app/pull/145)/[#147](https://github.com/Prakashmaheshwaran/flint-app/pull/147)/[#189](https://github.com/Prakashmaheshwaran/flint-app/pull/189) iOS detail/shield;
  [#172](https://github.com/Prakashmaheshwaran/flint-app/pull/172)/[#179](https://github.com/Prakashmaheshwaran/flint-app/pull/179)/[#181](https://github.com/Prakashmaheshwaran/flint-app/pull/181)/[#203](https://github.com/Prakashmaheshwaran/flint-app/pull/203)/[#176](https://github.com/Prakashmaheshwaran/flint-app/pull/176) Android Sleep/Help/detail on [#177](https://github.com/Prakashmaheshwaran/flint-app/pull/177)'s reader.
  [#223 exact head 91fcbd2 UI](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/41005146280):
  ten fixtures plus eleven Insights cases, including default/AX5 PIN. Earlier failed/superseded
  UI runs and dated screenshots remain historical; this is no Screen Time/VoiceOver result.
- <a id="redteam"></a>**REDTEAM — source review, not runtime certification:**
  [original audit](../verification/adr-012-redteam-2026-10-28.md) and
  [R2 pass 1 at 0f45cd2](../verification/adr-012-redteam-verification-2026-10-29.md).
  Its eleven source closures/ten partial/two reopened verdicts are frozen at that source;
  later merges above supersede particular paths without rewriting the historical report.
  Wind-down residuals below also incorporate coordinator-routed pass-2 findings; final pass-2
  source/report inclusion is checked in the final delta.

[IS]: #is
[IP]: #ip
[IR]: #ir
[IM]: #im
[IW]: #iw
[IO]: #io
[PIN]: #pin
[SLEEP]: #sleep
[IH]: #ih
[AE]: #ae
[AS]: #as
[AC]: #ac
[AG]: #ag
[AP]: #ap
[AM]: #am
[AW]: #aw
[AH]: #ah
[AN]: #an
[IK]: #ik
[AK]: #ak
[IL]: #il
[AL]: #al
[ID]: #id
[AR]: #ar
[SCOPE]: #scope
[DEVICE]: #device
[STORE]: #store
[BFINAL]: #bfinal
[CFINAL]: #cfinal
[FTRUTH]: #ftruth
[GLANCE]: #glance
[UXFINAL]: #uxfinal
[REDTEAM]: #redteam

## Status counts at this checkpoint

66 capability/platform rows: `ABSENT` **12**; `IMPLEMENTED-UNTESTED` **0**;
`UNIT-TESTED` **31**; `CI-EMULATOR-VERIFIED` **9**; `SIM-VERIFIED` **3**;
`DEVICE-PENDING` **5**; `DEVICE-VERIFIED` **0**; `DEFERRED-BY-DESIGN` **6**.
Counts describe the bounded evidence above, not a percentage of product completion.

## PC assertion evidence at its actual level

This is an assertion index, not a list of whole PC cases passed. The evidence index
above pins executed PR sources/runs; native adapter injection, Robolectric and pure
fixtures do not supply physical callback, corruption, inter-process or OS safety proof.

| PC requirements | Executed assertion slice / source | Level and remaining acceptance |
| --- | --- | --- |
| PC-01–10, 54–55, 58, 64–66, 74–76 | `FlintSessionControllerTests`, `FlintSessionAuthorityTests`, `FlintSessionCheckpointTests`, `FlintSessionAuthorityProjectionTests`: register-before-retire, exact generation/tombstone/replay, saved-remaining reboot bounds, no early callback release (#196/#182). | Native XCTest with injected clocks/adapters; R-20 pre-migration and actual independent host/monitor/killed-host delivery remain open. |
| PC-10–21, 54–56, 60, 70–71 | `FlintRuleArbiterTests`, `FlintRuleReviewTests`, occurrence-clock/migration tests (#162/#185): exact revision, deferred queue, max Harder wait and strict domains. | Native unit/transaction tests; R-07 no-anchor and R-28 remainder plus real DST/monitor/zone acceptance remain. A synthetic future-one-shot fixture is not reachable date authoring. |
| PC-18–26, 59, 65, 72, 75 | `FlintOrdinaryBreakServiceTests`, `FlintOrdinaryBreakNativeTests`, `FlintBreakAuthorizationTests` (#214/#221): scoped 300 s grants, atomic waits, carrier failure/restore. | Injected native store/carrier tests; R2p2-N02 successor overwrite and signed expiry/lock contention remain. |
| PC-28–38, 59–60 | `FlintOpenCreditTests` and `FlintOpenCreditMigrationTests` (#155): three-credit/fourth-denied, concurrent last credit, final credit, no-charge category/other-cause denial, midnight clipping, missing initialized counter. | Native unit/ledger tests; enabled #189 requires signed-device killed-host 300 s expiry, midnight, re-registration and lock contention before release. |
| PC-39–47, 50, 69, 83 | iOS `FlintEmergencyPassScopeTests`, `SuspensionTests`, `ResumeTests`, `RuleClockTests` (#161/#185); Android `ProtectionTransactionStoreTest`, `GlobalRecoveryCoordinatorTest` (#175/#205). | Native/DataStore/Robolectric transaction and injected resume assertions; R-04 predecessor residual and complete 60-minute/multi-window/reboot hardware recovery remain. |
| PC-10–21, 27, 52–55, 64, 67–68, 70–71 | Android `EditorCommitmentStoreTest` (#139/#173/#192), `TimerArmingTest` and refusal fixtures: DataStore gate, new/legacy Timer, anchored exits and no-detector refusal. | Executed JVM/transaction tests; mount-failure acknowledgement and physical stock/OEM controls remain. |
| PC-15, 25, 28–38, 47 | Android `DurableCoordinatorTest`, `OpenCreditStoreTest`, timed-credit policy/coordinator tests (#156/#178): saved tier, explicit debit, unavailable accounting and trusted reset. | JVM/Robolectric plus actual DataStore reopen; R2p2-N01 freshness race and measured positive-budget/device matrix remain. |
| PC-01, 22, 25, 41, 51, 61, 65, 84 (bounded flows) | `FlintSmokeTest` authored rule/shield, global recovery review/review/confirm, exhausted zero budget; `FocusHistoryTest`; final #219 E2E has twelve total cases including setup-policy tests. | Hosted API 36 emulator. Setup-only recovery (#166/#198/#208/#216) never retries app assertions; full pass duration, force-stop/OEM/browser/safety acceptance is not proved. |
| PC-61, 73, 84 | `FlintGlanceTests` #171 publish/blob/read and strict validity; Android publisher/health mapping #169/#206; actual `GlanceStartupWidgetTest` unavailable/Open Flint. | iOS unit/shared-view fixtures; Android JVM plus installed minified widget host. Real protected launcher/ActivityKit/notification/permission transitions remain hardware gates. |
| PC-50, 84 | #223 exact UI run: PIN create/relaunch/wrong/correct/removal at default/AX5, onboarding/schedule and Insights fixtures. | Hosted iOS simulator; no real Screen Time, report, full VoiceOver or device safety proof. |
| PC-63–66, 70–71, 84 | Mirrored #151/#202/#219 history tests: missing terminal is Unconfirmed, owner confidence, stable replay, clear/reopen, uncertain break/pass/rest/coverage worst-case cuts. | Native/real-store/JVM tests with bounded hosted Timer history. No measured complete iOS break history, duplicate-terminal closure or universal exactly-once/clock proof. |

## Open at v1 wind-down

These are open source/contract variants at this checkpoint, separate from the hardware
campaign. B/C final reports and the final R2 pass-2 report determine closure; an open
PR, lane claim, green unrelated test or softened copy does not close a finding.

| Item | Remaining path / acceptance | State |
| --- | --- | --- |
| R2p2-N02 (P0, iOS) | Stale ordinary-break fallback can overwrite a successor's protection. | OPEN; priority release blocker. |
| R2p2-N03 (P1, iOS) | Fresh-rule bootstrap can poison first Open-credit migration. | OPEN; no migration closure inferred from #155/#185. |
| R-07 (P0 residual, iOS) | Upgrade/occurrence without a trusted anchor still has a wall-time weakening path. | PARTIAL; anchored-path fixes are merged. |
| R-20 (P0 residual, iOS) | Pre-migration missing/unavailable session authority is not closed by post-generation fixtures. | PARTIAL. |
| R-04 (P1 residual, iOS) | Retained legacy predecessor can make Emergency Pass scope unavailable. | PARTIAL; ordinary global recovery is merged. |
| R-16 (P1 residual, Android) | Legacy Harder break/friction route remains outside full cause-scoped rights. | PARTIAL; final in-flight fix must be checked. |
| R2p2-N01 (P1, Android) | Credit acceptance can charge after a zero-minute Time Limit is added using stale measurement; the next evaluation still blocks, so this is a no-charge violation, not a demonstrated Hardcore access bypass. | OPEN; positive-budget API also depends on R-18b. |
| R-30 (P1 residual, Android) | Overlay mount failure is not reflected in health/Armed acknowledgement. | PARTIAL; no-live-detector refusal in #173 is merged. |
| R-24 / F01 (P1 original, iOS) | Interim one-round honesty is source-closed; full repeated focus/rest parent remains absent. | Full rounds remain open scope; do not reopen the fixed interim-copy finding. |
| R-28 (P1, iOS) | Saved zone, dated/repeating registration, callback day checks and DST identity are source-closed; persisted one-shot terminal state, production one-shot creation/PC-81 and a rule-zone editor remain absent. | PARTIAL; no promotion of the closed source slice into whole-case/device acceptance. |
| R-18 daily Time Limits (P0 residual, Android) | Trusted saved-zone day/reset and retained measurement floor. | #225 in flight; not counted merged at this checkpoint. |
| R-08 iOS removal guard | Explicit consent/derived finite-owner runtime. | Pure phase 1 #200 merged; #226 in flight. |
| iOS required-reason API inventory | Later lock deadlines call listed SystemBootTime API systemUptime. | #230 declarations/verifier in flight; final signed-archive re-inventory remains required. |
| PC-84 Android terminal arbitration | Duplicate natural/explicit terminal histories. | #227 in flight; no whole-history exactly-once claim. |

**Coordinator release decisions:** R-12 is source-complete with Open acceptance enabled
by #189, runtime **DEVICE-PENDING**, a hard signed-device PC-28..38 gate. That coordinator
label covers the bounded-grant implementation; the separate first-use import defect
R2p2-N03 prevents whole-Open-capability closure. Revert
`FlintOpenCredit.shieldAcceptanceEnabled` in one line and restore fallback shield
copy if killed-host 300 s expiry, midnight, re-registration or lock contention fails.
R-26 uses option A: refuse new empty allow lists, retain saved empty ones with attention;
signed-device Phone/default calling app/in-call/SOS under `.all(except:)` remains
**DEVICE-PENDING**, moving to option B if safety fails. Android force-stop unbinds
Accessibility; #173 refuses Timer arming without a live detector.

## Remaining unevidenced capabilities and release boundary

Absent behaviors remain date-specific one-shots / arbitrary future starts on both
platforms, a repeated Pomodoro chain, immediate Allow List Timers, Android automation,
browser private-mode/history lockdown and app PIN, and iOS offline legal screens.
Broader editor-domain migration and Android limit lifecycle work remain parked
[#180](https://github.com/Prakashmaheshwaran/flint-app/pull/180),
[#186](https://github.com/Prakashmaheshwaran/flint-app/pull/186),
[#222](https://github.com/Prakashmaheshwaran/flint-app/pull/222); drafts are not merged features.

Neither platform has build/device-bound physical enforcement acceptance. Signed iOS
Screen Time, calling/category safety, killed-host callbacks, thresholds, Siri/Focus,
Safari/private-mode effects, pass/credit resumption and delivered widgets/ActivityKit
remain unrun. Android needs stock/OEM/API/browser/Doze/reboot, safety, positive-budget
measurement and real launcher/notification/permission-transition evidence. Generic reminders
do not prove completion. iOS owner lifecycle confidence call sites, typed acknowledged
ordinary-break history and complete operation history remain incomplete; consumer
plausibility cannot detect every internally consistent unmarked clock jump. No source-wide
exactly-once or complete time-confidence guarantee is made.

Six-bundle iOS signing/provisioning, five Family Controls distribution approvals, final
archive/privacy validation, owner-signed Android AAB/Play split/update evidence, genuine
media, public policy/support URLs and actual store declarations/upload/review/publication
remain human-owned. Live release readback found only v0.1.0's historical debug APK;
no integration-v1 release/tag/store availability is claimed.

Lane H's parked C01/C06/C12/C22/C23/C26–C28 documentation corrections are reflected in
the platform READMEs and this ledger. Dated research, red-team reports, UI captures and
historical changelog entries retain their original contents and source limits.
