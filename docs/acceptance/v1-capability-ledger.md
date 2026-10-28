# v1 capability acceptance ledger

Evidence checkpoint: **2026-10-28**, merged `integration/v1` at
[`2b91849`](https://github.com/Prakashmaheshwaran/flint-app/tree/2b91849534e757952c80662ddb90235129aaa8b9).
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
| 1.1 Blocking sessions | iOS | UNIT-TESTED | Registration-before-shield, finite session timing, replacement/stop arbiter and startup recovery. Rule-wide arbitration and device shielding remain separate. | [IS], [IR] | PC-01–10, PC-61–66, PC-74–76 |
| 1.1 Blocking sessions | Android | CI-EMULATOR-VERIFIED | UI-authored rule survives process restart; real Accessibility shield and Easy release. Service is explicitly re-enabled by the test; no automatic recovery claim. | [AE], [AG] | PC-01, PC-22, PC-61, PC-65 |
| 1.2 Block Now / instant timer | iOS | UNIT-TESTED | Timed manual/Shortcut session lifecycle; short-duration logical deadline adapter. Host-dead callback timing needs hardware. Pomodoro chaining is listed separately. | [IS], [IH] | PC-01–10, PC-63–66, PC-74–76 |
| 1.2 Block Now / instant timer | Android | CI-EMULATOR-VERIFIED | Real five-minute Timer completion, early-end record and restart persistence. Tested history case had blocking off. | [AH] | PC-01, PC-63–64, PC-67–68, PC-84 |
| 1.3 Date-specific one-shot future session | iOS | ABSENT | Personal model/editor registers weekday recurrence; immediate finite sessions do not supply a future date. | [IM] | PC-13–14, PC-81 |
| 1.3 Date-specific one-shot future session | Android | ABSENT | Personal Schedule/RuleDraft has weekdays and local minutes; no future absolute occurrence authoring. | [AM] | PC-13–14, PC-81 |
| 1.4 Recurring / Smart Schedules | iOS | UNIT-TESTED | Weekday/overnight validation, registration health, recovery. No Flint count cap; finite OS pool/refusals still apply. | [IR], [IM] | PC-02, PC-14, PC-54–58, PC-62, PC-73 |
| 1.4 Recurring / Smart Schedules | Android | UNIT-TESTED | Weekday/DST/boundary decisions tested. Separate local emulator evidence observes stationary schedule activation; this is not a hosted schedule E2E. | [AC] | PC-14, PC-54–58, PC-61–62, PC-77 |
| 1.5 Arbitrary future start beyond 24 h | iOS | ABSENT | Weekly recurrence can reach later weeks; arbitrary date-specific delayed first activation is absent. | [IM] | PC-81 |
| 1.5 Arbitrary future start beyond 24 h | Android | ABSENT | No arbitrary future date in release personal authoring; debug managed fields are not personal scheduling. | [AM] | PC-81 |
| 1.6 Block List | iOS | UNIT-TESTED | Opaque app/category/domain selection persistence and cap validation. Actual picker tokens and shields require hardware. | [IR], [IM] | PC-02, PC-48–50, PC-80 |
| 1.6 Block List | Android | CI-EMULATOR-VERIFIED | Pick app, save rule, restart, shield target. Full app/category/site catalog is not implemented. | [AE], [AS] | PC-01, PC-48–52, PC-80 |
| 1.7 Allow List | iOS | UNIT-TESTED | Schedule/Sleep app exceptions; immediate session mode is absent. Opaque category/system safety remains a device gate. | [IM], [IR] | PC-24–26, PC-48–50, PC-52, PC-80 |
| 1.7 Allow List | Android | UNIT-TESTED | App-selection inversion and terminal safety-floor tests. Domain allow-only is unsupported. | [AS], [AM] | PC-24–26, PC-48–52, PC-80 |
| 1.8 Website/domain blocking | iOS | UNIT-TESTED | Picker-token shields plus custom Safari configuration/normalization tests. Per-cause Safari production wiring is not merged at this checkpoint. | [IW] | PC-24–26, PC-32–33, PC-72 |
| 1.8 Website/domain blocking | Android | UNIT-TESTED | Domain/subdomain matching and known browser address-bar adapters. Path B has no URL visibility; no complete browser-version runtime matrix. | [AW] | PC-24–26, PC-32, PC-61, PC-77 |
| 1.9 Safari private-browsing/history restrictions | iOS | UNIT-TESTED | Derived settings/config writer tested; actual cause-owned apply/clear and Safari effect are not accepted from a writer alone. | [IW] | PC-41, PC-72, PC-77 |
| 1.9 Browser private-browsing/history restrictions | Android | ABSENT | URL inspection does not disable incognito or lock browser history. | [AW] | —; platform divergence |
| 1.10 Saved groups / routine presets | iOS | UNIT-TESTED | Four schedule templates, saved opaque selections, mapping/round trips. Social detox template has a 23:59–00:00 gap. | [IM] | PC-13, PC-80 |
| 1.10 Saved groups / routine presets | Android | UNIT-TESTED | Five presets, persisted named app groups and apply/delete logic. Full current UI group flow unobserved. | [AM] | PC-13, PC-80 |
| 2.1 Time Limits | iOS | UNIT-TESTED | Combined-selection daily threshold registration, validation/health. Device threshold and editor commitment policy remain unproven. | [IR], [IM] | PC-15, PC-37, PC-54–58, PC-61–62 |
| 2.1 Time Limits | Android | CI-EMULATOR-VERIFIED | Hosted test shields an already-exhausted zero budget. Growing positive budgets have separate local emulator observations and latency limits. | [AE], [AC] | PC-15, PC-37, PC-54–58, PC-61–62 |
| 2.2 Open Limits | iOS | UNIT-TESTED | Legacy explicit shield acceptance/counters tested. Bounded five-minute credits, conservative overlap and corrupt-counter migration still need implementation/evidence. | [IO] | PC-16, PC-28–38, PC-59–60, PC-75–76 |
| 2.2 Open Limits | Android | UNIT-TESTED | Live legacy launch policy plus durable commit-before-release grant receipt tests; pure timed-credit policy exists separately. ADR-012 explicit-credit runtime is not yet wired. | [AG], [AP] | PC-16, PC-28–38, PC-59–60 |
| 2.3 Easy / Harder / Hardcore | iOS | UNIT-TESTED | New manual/Shortcut/Focus session stop/replacement shares persisted Harder friction; Hardcore finite bounds. This does not prove all rule/limit editor rights. | [IS], [IP] | PC-04–27, PC-67–68, PC-79 |
| 2.3 Easy / Harder / Hardcore | Android | UNIT-TESTED | Pure ADR-012 matrix/waits/credit/pass tests plus existing shield tiers. Editor/new Timer and all-cause runtime changes are not merged. | [AP], [AG] | PC-04–27, PC-67–68, PC-79 |
| 2.4 Deep Focus / Hardcore commitment | iOS | UNIT-TESTED | Session weakening/replacement denial tested; finite expiry and permission recovery remain available. Broad rule/limit commitment and hardware effect remain pending. | [IS], [IP] | PC-04–16, PC-25–27, PC-68, PC-78–79 |
| 2.4 Deep Focus / Hardcore commitment | Android | CI-EMULATOR-VERIFIED | Hosted Hardcore shield omits ordinary break and explicit legacy pass releases target. This does not certify active editor locks or new Timer rights. | [AE], [AP] | PC-25, PC-41, PC-51, PC-67–68 |
| 2.5 Anti-bypass / clock / removal protections | iOS | UNIT-TESTED | Session monotonic timing, ledger and UI PIN tests. Current session removal request is not proof of ADR-012 opt-in/multi-owner behavior; revocation remains reachable. | [IS], [IP], [PIN] | PC-48–60, PC-69–71, PC-77–79 |
| 2.5 Anti-bypass / clock / removal protections | Android | UNIT-TESTED | Safety floor, measured time-change and boot-bound durable grant tests. Uninstall/disable interception removed; clock edits cannot be vetoed and reboot time is uncertain. | [AS], [AC], [AG] | PC-48–60, PC-69–71, PC-77 |
| 2.6 Emergency Pass | iOS | UNIT-TESTED | Cross-process rolling pass ledger exists; active-session legacy redemption is narrower than global 60-minute recovery. Whole-cause runtime acceptance remains open. | [IP], [IS] | PC-39–47, PC-59–60, PC-69, PC-83 |
| 2.6 Emergency Pass | Android | CI-EMULATOR-VERIFIED | Hosted legacy Hardcore target release only. Pure rolling/global policy tests do not establish the live coordinator has adopted it. | [AE], [AP], [AG] | PC-39–47, PC-59–60, PC-69, PC-83 |
| 3.1 iOS Focus Filter | iOS | UNIT-TESTED | Owned-session start/stop uses same arbiter and returns strict-tier errors. OS Focus delivery is unobserved. | [IS] | PC-04, PC-07–09, PC-79 |
| 3.1 iOS Focus Filter | Android | DEFERRED-BY-DESIGN | Apple-only API; no Android system-focus equivalent promised. | [SCOPE], [AM] | PC-08–09 only for future equivalent |
| 3.2 Shortcuts / Siri / contextual automation | iOS | UNIT-TESTED | Start Session/Start Pomodoro/Stop Session entry points share session policy; contextual triggers belong to Apple Shortcuts. No native location collection. | [IS], [IM] | PC-03–10, PC-63–66 |
| 3.2 Shortcuts / contextual automation | Android | ABSENT | No release automation/location/DND API; DEBUG seed receiver is test infrastructure. | [AM] | PC-03–10 for any future route |
| 3.3 Sleep / Morning Assist | iOS | SIM-VERIFIED | Sleep presentation at Large/AX5, saved tier and honest arming feedback; actual overnight enforcement/pass semantics unproven. | [SLEEP], [IR] | PC-14, PC-27, PC-41–42, PC-54–58, PC-82–83 |
| 3.3 Sleep / Morning Assist | Android | UNIT-TESTED | Overnight/day-shift/morning materialization and saves. No physical overnight/OEM or full boundary observation. | [AM] | PC-14, PC-41–42, PC-54–58, PC-82–83 |
| 3.4 Cross-device / desktop blocking | iOS | DEFERRED-BY-DESIGN | Local-only release; no rule sync, Mac client or browser extension. | [SCOPE] | —; out of personal v1 scope |
| 3.4 Cross-device / desktop blocking | Android | DEFERRED-BY-DESIGN | Debug local connected authoring is excluded from release and does not activate managed blocking. | [SCOPE], [AR] | —; out of personal v1 scope |
| 3.5 Android implementation / parity | iOS | DEFERRED-BY-DESIGN | Android aggregate is recorded on Android rows; no iOS parity claim inferred. | [SCOPE] | —; platform-specific aggregate |
| 3.5 Android implementation / parity | Android | DEVICE-PENDING | Native core, history and release-code hosted smoke exist; unresolved rows here and stock/OEM acceptance prevent full parity/release certification. | [AE], [AH], [AR], [DEVICE] | PC-01–84 as applicable |
| Gamification / streaks / rewards | iOS | UNIT-TESTED | Local completed-session streak in history summary; no Gems/achievement catalog or rewards engine. | [IH] | PC-84 for history-derived streak only |
| Gamification / streaks / rewards | Android | UNIT-TESTED | Local history streak aggregation; no Gems/achievement catalog or rewards engine. | [AH] | PC-84 for history-derived streak only |

## Flint additions and explicit sub-capabilities

| ID / capability | Platform | Status | Evidenced slice and remaining boundary | Evidence | Relevant PC IDs |
| --- | --- | --- | --- | --- | --- |
| F01 Full repeated Pomodoro focus/rest chain | iOS | ABSENT | Intent starts first focus round and saves cadence; remaining rounds do not chain. | [IM] | PC-27, PC-63–64, PC-84 |
| F01 Full repeated Pomodoro focus/rest chain | Android | ABSENT | Native finite Timer exists; repeated focus/rest configuration/execution absent. | [AM] | PC-27, PC-63–64, PC-84 |
| F02 Immediate Allow List session | iOS | ABSENT | Allow List is schedule/Sleep-specific at this checkpoint. | [IM], [IS] | PC-01, PC-48–50 |
| F02 Immediate Allow List session | Android | ABSENT | Timer targets apps as a block list; separate rule editor supports Allow List. | [AM] | PC-01, PC-48–50 |
| F03 App-open PIN | iOS | SIM-VERIFIED | PIN validation/change/removal/background privacy fixtures. UI privacy barrier, not OS authority. | [PIN] | PC-50; recovery must remain reachable |
| F03 App-open PIN | Android | ABSENT | No release PIN/app-lock flow in Settings/Timer navigation. | [AM] | PC-50 if added |
| F04 Safety-floor hardware acceptance | iOS | DEVICE-PENDING | Source/selection validation is insufficient for opaque categories, Always Allowed and default calling configurations. | [IR], [DEVICE] | PC-48–52, PC-77 |
| F04 Safety-floor hardware acceptance | Android | DEVICE-PENDING | Terminal floor implemented/tested before every cause; real dialer/Home/OEM and permission UI remain unverified. | [AS], [DEVICE] | PC-48–52, PC-77 |
| F05 Durable history / Today / Complete / Insights | iOS | SIM-VERIFIED | Production store/aggregator with explicitly seeded fixture UI; Screen Time reports stay separate. Host-dead physical monitor history unproven. | [IH] | PC-63–66, PC-70–71, PC-84 |
| F05 Durable history / Today / Complete / Insights | Android | CI-EMULATOR-VERIFIED | Actual Timer completion/early end survives restart and drives totals. Full pass/outbox/coverage producers and observed counters have narrower evidence. | [AH] | PC-63–66, PC-70–71, PC-84 |
| F06 Optional focus-end notification | iOS | UNIT-TESTED | Permission/controller and planned-time-ended scheduling/cancellation tests. Delivery is not completion/enforcement proof. | [IH] | PC-63–64, PC-84 |
| F06 Optional focus-end notification | Android | UNIT-TESTED | Inexact alarm journal/recovery and Focus end reminders channel tests; Doze/delivery unobserved. | [AN] | PC-63–64, PC-84 |
| F07 Read-only widgets / glance | iOS | UNIT-TESTED | Widget families, typed status/history adapters, freshness/absence tests. No widget delivery screenshots; live B producer hooks still pending. | [IK] | PC-61, PC-73, PC-84 |
| F07 Read-only widgets / glance | Android | UNIT-TESTED | Glance rendering/delivery adapters tested; no launcher-widget runtime capture. Live cause/detector publishers remain pending. | [AK] | PC-61, PC-73, PC-84 |
| F08 Live Activity / ongoing focus status | iOS | UNIT-TESTED | Default-off ActivityKit eligibility, stale bounds and dismissal tests. No signed ActivityKit/Dynamic Island or live session delivery proof. | [IK] | PC-61, PC-73 |
| F08 Live Activity / ongoing focus status | Android | UNIT-TESTED | Optional Path A notification and mandatory Path B FGS customization are distinct. Real publisher/service tick wiring pending. | [AK] | PC-61, PC-73, PC-77 |
| F09 Offline privacy policy / licenses screens | iOS | ABSENT | You has a privacy note and About link; full bundled legal screens are absent. Store drafts are not in-app legal screens. | [IL] | —; release transparency |
| F09 Offline privacy policy / licenses screens | Android | UNIT-TESTED | Native offline legal navigation, retry/back/restoration, generated release notices and packaged policy checks. | [AL] | —; release transparency |
| F10 Release packaging / store delivery | iOS | DEVICE-PENDING | Unsigned Debug/Release builds, six manifests, AppIcon and fixture scans. Signing, sixth-widget provisioning, archive, hardware and TestFlight are human gates. | [ID], [IK], [STORE] | PC-48–84 where device required |
| F10 Release packaging / store delivery | Android | CI-EMULATOR-VERIFIED | Actual minified release-code APK installed and shield/break smoke passed; AAB/exclusion scans pass. Owner signing/Play delivery/hardware remain open. | [AR], [STORE] | PC-01, PC-22, PC-51; bounded release smoke |
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
  [tests](../../ios-app/Tests/), [CI workflow](../../.github/workflows/ios.yml).
- <a id="ip"></a>**IP — iOS policy/ledger:** [#112](https://github.com/Prakashmaheshwaran/flint-app/pull/112),
  [#120](https://github.com/Prakashmaheshwaran/flint-app/pull/120);
  [policy tests](../../ios-app/Tests/FlintProtectionPolicyTests.swift),
  [ledger tests](../../ios-app/Tests/FlintProtectionLedgerTests.swift). Pure/transaction decisions do not prove all live callers.
- <a id="ir"></a>**IR — iOS recovery:** [#82](https://github.com/Prakashmaheshwaran/flint-app/pull/82),
  [#87](https://github.com/Prakashmaheshwaran/flint-app/pull/87);
  [reconciler tests](../../ios-app/Tests/FlintProtectionReconcilerTests.swift),
  [recovery safety tests](../../ios-app/Tests/FlintProtectionRecoverySafetyTests.swift).
- <a id="im"></a>**IM — iOS existing model/entry scope:** [schedule model](../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintScheduleRule.swift),
  [schedule controller](../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintSchedulesController.swift),
  [Shortcuts runner](../../ios-app/Flint/Features/Shortcuts/FlintShortcutsRunner.swift),
  [Sleep controller](../../ios-app/Flint/Features/Sleep/SleepModeController.swift),
  [tests](../../ios-app/Tests/FlintRoutinePresetsTests.swift).
- <a id="iw"></a>**IW — iOS web:** [configuration/tests](../../ios-app/Tests/FlintWebRestrictionsTests.swift),
  [writer](../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintWebRestrictions.swift).
  [#78](https://github.com/Prakashmaheshwaran/flint-app/pull/78) is outstanding, not evidence of merged runtime wiring.
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
  [runtime floor](../../android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/SafetyFloorGate.kt).
- <a id="ac"></a>**AC — Android continuous evaluation:** [#88](https://github.com/Prakashmaheshwaran/flint-app/pull/88),
  [CI 40776050830](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40776050830),
  [#146 local emulator evidence](../verification/android-continuous-eval-2026-10-28/README.md).
  Path B budget had a +20.608 s first-visible upper bound; cold-start ANR under load is unresolved.
- <a id="ag"></a>**AG — Android durable grants:** [#84](https://github.com/Prakashmaheshwaran/flint-app/pull/84),
  [#136](https://github.com/Prakashmaheshwaran/flint-app/pull/136),
  [CI 40778888207](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40778888207),
  [live coordinator](../../android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/BlockScreenCoordinator.kt).
- <a id="ap"></a>**AP — Android pure policy:** [#118](https://github.com/Prakashmaheshwaran/flint-app/pull/118),
  [policy fixtures](../../android-app/blocking/blocking-engine/src/test/kotlin/com/flint/peakfocus/blocking/engine/ProtectionPoliciesTest.kt).
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
  [Android detail](../verification/android-device-acceptance.md).
- <a id="store"></a>**STORE:** [single human submission checklist](../store/README.md).

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

## Status counts at this checkpoint

66 capability/platform rows: `ABSENT` **12**; `IMPLEMENTED-UNTESTED` **0**;
`UNIT-TESTED` **33**; `CI-EMULATOR-VERIFIED` **8**; `SIM-VERIFIED` **3**;
`DEVICE-PENDING` **4**; `DEVICE-VERIFIED` **0**; `DEFERRED-BY-DESIGN` **6**.
Counts describe the bounded evidence above, not a percentage of product completion.

## Open acceptance / documentation corrections

The checkpoint does not evidence arbitrary future one-shots, full Pomodoro chaining, immediate
Allow List Timers, Android automation, Android private-browser lockdown, Android PIN, or iOS
offline legal screens. Those are recorded above rather than inferred from broad parity claims.
Pure policy, schema or reader APIs are insufficient to close timed Open credits, global pass,
all-cause editor locks, live glance publishers or the complete durable lifecycle outbox.

Lane H's parked documentation items are covered here and in the READMEs: C01 finite Hardcore
and OS limits; C06 actual pass eligibility instead of calendar-week copy; C12 per-cause tested
rights; C22 removed Android Settings/uninstall interception; C23 measured clock/reboot limits;
C26 future-date and Open-credit gaps; C27 no refreshed competitor guarantee; C28 debug backend
separate from the no-account release. Historical research/changelog sections retain their dates
under superseding banners. The [screen audit](../ux/v1-screen-audit.md) remains the dated findings,
not a live closure ledger.

Final-refresh gate: lanes B/C are still landing work. Re-read fetched integration ancestry,
merged PRs, exact CI scope, platform checklists and store inventories when the coordinator
declares their stopping boundary; update this checkpoint and status counts before closeout.
