# Evidence for the 2026-11-11 red-team re-verification

These are the adversarial test sources the re-verification wrote at `origin/main` `b888084` that are **not** already part of a fix PR. They live here uncompiled, outside every Gradle source set and the XcodeGen project, so no build or CI job picks them up. To rerun one, copy it into the module path shown below, then run it with the commands in the [record](../redteam-reverify-2026-11-11.md).

The tests for the four fixes are in their own PRs instead: #262 (R2p2-N03), #263 (R-04), #264 (R2p2-N02) and #265 (R2p2-N01).

| File | Copy to | Outcome at `b888084` |
| --- | --- | --- |
| `r07/FlintRuleAnchorResidualRedTeamTests.swift` | `ios-app/Tests/` | 17 tests: 13 pass. 3 fail by forging a well-formed anchor timeline: SUSPECTED, outside the fault model. 1 fails on an impossible premise (unknown boot with more uptime than wall time): rejected. |
| `r20/FlintRedTeamR20BreakLiftTests.swift` | `ios-app/Tests/` | 5 tests: 2 controls pass. 2 fail from an unreachable first-migration-during-break state (v0.1.0 had no ordinary breaks): not counted. 1 fails on the R2p2-N02 double fault, fixed by #264 at the break deadline. |
| `n01/RedTeamR2p2N01ResidualTest.kt` | `android-app/blocking/blocking-overlay/src/test/kotlin/com/flint/peakfocus/blocking/overlay/` | 12 tests: 5 closed paths pass. Of the 7 that fail, 2 test the reducers directly with a stale Boolean (the production guard path is fixed by #265), 3 test `covers`/`retireInvalid` with a stale input (SUSPECTED, bounded by detector re-measurement), 1 is the looser-definition guard case (fixed by #265), and 1 is unknown history (owner question; #250's documented design). |
| `n01/RedTeamR2p2N01ControllerTest.kt` | `…/blocking-overlay/src/testDebug/kotlin/com/flint/peakfocus/blocking/overlay/` | 2 tests: the zero-minute controller path passes. The positive-budget controller path fails because it calls the controller without the coordinator's guard (fixed by #265 at the guard). |
| `r16/RedTeamR16CoordinatorFrictionTest.kt`, `r16/RedTeamR16LegacyImportFrictionTest.kt` | `…/blocking-overlay/src/testDebug/kotlin/com/flint/peakfocus/blocking/overlay/` | 14 tests: 10 pass. `R16E` and `R16K` fail on expectations stricter than the contract (Hardcore is not seeded; a reboot denies instead of re-waiting). `R16B` fails on a missing seed but the request is `Denied` (no release). `R16M` shows a 60 s first wait for a legacy budget-only cause: SUSPECTED, P2. |
| `r30/RedTeamR30CoordinatorMountTest.kt` | `…/blocking-overlay/src/testDebug/kotlin/com/flint/peakfocus/blocking/overlay/` | 7 tests pass. |
| `r30/RedTeamR30TargetProjectionTest.kt` | `…/blocking-overlay/src/test/kotlin/com/flint/peakfocus/blocking/overlay/` | 2 tests: the healthy control passes. The app-detail projection after a mount failure fails: a display-only PARTIAL and an owner question. |
| `r30/RedTeamR30TimerArmingTest.kt`, `r30/RedTeamR30TimerViewModelTest.kt` | `android-app/feature/feature-blocklist/src/test/kotlin/com/flint/peakfocus/feature/blocklist/` | 5 and 2 tests pass. |
| `r18b-core/RedTeamR18bDayAccountingTest.kt` | `android-app/blocking/blocking-engine/src/test/kotlin/com/flint/peakfocus/blocking/engine/` (JVM module: `:blocking:blocking-engine:test`) | 12 tests pass. |
| `r18b-core/RedTeamR18bTrustedDayStoreTest.kt` | `android-app/core/core-datastore/src/test/kotlin/com/flint/peakfocus/core/datastore/` | 9 tests pass. |
| `r18b-core/RedTeamR18bHandoffTest.kt` | `…/blocking-overlay/src/testDebug/kotlin/com/flint/peakfocus/blocking/overlay/` | 3 tests pass. |
| `r18b-followups/TimeLimitBootstrapRetryRedTeamTest.kt` | `…/core-datastore/src/test/kotlin/com/flint/peakfocus/core/datastore/` | 8 tests, as last run: 5 pass. 2 failed only on a `~UTC` expectation the child had already corrected in this file; not re-run. 1 fails by seeding a `U\|IO` latch that production cannot write: not counted. |
| `r18b-followups/TimeLimitUnavailableReleaseRedTeamTest.kt` | `…/blocking-overlay/src/testDebug/kotlin/com/flint/peakfocus/blocking/overlay/` | 2 tests pass. |

The child tasks that wrote these ran OpenCode DeepSeek V4.1 Flash Ultrafast. The orchestrator checked every failing test's premise against production code before counting or rejecting it; the reasons are in the record.
