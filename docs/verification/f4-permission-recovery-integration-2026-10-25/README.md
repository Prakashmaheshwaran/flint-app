# F4 — Permission recovery integration

The final product at `5e62cb889a8488e4935c2ed09c6197aeaf78d81b` passes the full **82-test iOS gate** and **eight affected native cases** on Today and Insights at Large and maximum accessibility text (AX5). Four transient-error/retry cases remain accepted at `85f066766fe03c75826f73c033a284903e414817`, with unchanged-path evidence. These are **12 unique accepted cases**, not 12 final-source reruns.

[PR #67](https://github.com/Prakashmaheshwaran/flint-app/pull/67) stays on `codex/f6-ios-insights-states`, after #61 and #65. Android #66 remains a semantic dependency before #67. The [original F4 record](../f4-accessible-text-layouts-2026-10-24/README.md) retains the adaptive-layout, iPhone SE privacy, and Android evidence; this follow-up adds the shared permission-recovery integration.

## Integration and source identity

F4 normally merged F6 product `3de5132e34fd2b0d6a1ee68cff7a7bfdbaac2e5b`, containing F5 secondary-target fix `08bbc204b9074e9fd56a9efa26faad96e995b155`, plus F6 evidence `9dacf0087435b2230586bc9fbc505a2ff57107e8`, to produce tested commit `5e62cb8`. Final F6 head `8e6645764e854478b21b96a0e68e36caec0785d4`, including F5 final evidence `3792264466d7bbdd8ba8b0a5453646a42bd63b0e`, was then merged normally as `3dbd04f55046a0bc0f4908c346cbc3bc9bb27eea`. That last merge changes only documentation; non-documentation source is identical to `5e62cb8`.

The integration inherits shared `AuthorizationControls` for denied Settings guidance/recheck and undecided/transient request/retry/cancel states. Secondary controls place their full-width, minimum-44pt frame and content shape inside their labels. No shared implementation was copied or independently patched in F4.

F4's measured and clipped root/tab viewport, adaptive Today presets, Insights header, and non-sizing gradient remain intact. RootView/PIN composition is unchanged from `f67ad4b`. Hosted `FlintTests` imports the app; duplicate `AuthorizationModel` and `InsightsState` test-source entries are removed, while report-extension presentation sources remain. Android tree `617f9027ad784d31697df17309329b3b024e84e7` is unchanged from `f67ad4b`. No repository `scripts/`, `Makefile`, or generated Xcode project was hand-edited.

See [final product identity](manifests/source-identity-final-product.json), [final upstream merge identity](manifests/final-upstream-identity.json), and [built executable hashes](manifests/product-02.json). Historical binary paths may have been reused by later builds; their recorded hashes describe the captured run, not a promise about current DerivedData contents.

## Verification

The full `make verify-ios` gate passed **82/82, zero failures or skips**, at `5e62cb8` under **Xcode 27.1 / 27A9275 / iPhoneSimulator27.1 SDK**, running the authorized iPhone 18 Pro guest on iOS 27.0. The gate used a destination-only Make override and disabled parallel simulator clones; no XCTest filtering was applied to the app gate. [Gate summary](manifests/gate-02-summary.json), [compressed log](logs/verify-ios-02.log.gz), [toolchains](manifests/build-toolchains.json).

All final native cases used the freshly installed, hash-verified `5e62cb8` production app at **402×874pt** and the frozen UI harness. Only the four affected methods were selected, once at each text size:

| Screen / scenario | Large | AX5 | Product source |
| --- | --- | --- | --- |
| Today denied guidance, Settings, return, recheck | PASS 16.825s | PASS 28.882s | `5e62cb8` |
| Insights denied guidance, Settings, return, recheck | PASS 19.216s | PASS 35.280s | `5e62cb8` |
| Today pending, whole Stop waiting, corner cancellation | PASS 13.441s | PASS 20.043s | `5e62cb8` |
| Insights pending, whole Stop waiting, corner cancellation | PASS 15.162s | PASS 24.363s | `5e62cb8` |
| Today complete transient feedback and actual Retry | PASS 18.387s | PASS 26.075s | Retained at `85f0667` |
| Insights complete transient feedback and actual Retry | PASS 18.902s | PASS 28.995s | Retained at `85f0667` |

The four retained transient cases exercise unchanged model/preview, primary action, feedback, Today/Stats, and root paths. The subsequent source change expands secondary controls and onboarding skip; it does not change those primary/error paths. The initial AX5 denied passes are superseded by the final affected runs and are not counted twice. [Accepted matrix](manifests/native-accepted-matrix.json), [final cases and screenshot hashes](manifests/native-final-affected.json), [historical attempts and probes](manifests/native-initial-and-probes.json).

The native harness asserts complete tab labels and selected state, minimum-44pt tab/control bounds, and whole-control containment in the intersection of the actual ScrollView, window, and measured tab top, with only 0.01pt rounding tolerance. Guidance/error prose must fit horizontally. Tall prose is captured at the first line, overlapping scroll positions, and final line; separate control captures establish that the following actions can be reached.

Denied flows launch the public Settings URL, observe Settings becoming foreground, return to Flint, and actually tap Check access again. **The unsigned simulator opened Settings root.** These checks do not require or claim a Flint settings page, visible permission switch, or successful authorization grant, and no Settings values were changed. Denied state remains explicit after recheck.

Pending flows use the existing UI-only hung fixture. Requesting access is disabled; the whole Stop waiting target is brought above the tabs, then tapped **3pt inside its bottom-right corner**. Every case cancels, restores enabled Allow access without an error, and preserves the selected tab.

| Stop waiting target | Frame (x, y, width, height), pt | Actual tab top | Clearance above tabs |
| --- | --- | --- | --- |
| Large Insights | (40, 395.333, 322, 44) | 764 | 324.667pt |
| Large Today | (40, 676, 322, 44) | 764 | 44pt |
| AX5 Insights | (40, 550.667, 322, 51) | 638.667 | 37pt |
| AX5 Today | (40, 585.667, 322, 51) | 638.667 | **2pt** |

The full AX5 Today target fits, including its successful corner at (359, 633.667); the narrow clearance is recorded rather than hidden by a permissive tolerance. [Measured bounds and tap coordinates](manifests/final-secondary-target-bounds.json).

## Selected captures

- Final AX5 [Today whole Stop waiting](screenshots/ui-03-affected-ax5-today-stop-waiting-whole-target.png) and [successful corner cancellation](screenshots/ui-03-affected-ax5-today-corner-cancelled-without-error.png).
- Final AX5 [Insights whole Stop waiting](screenshots/ui-03-affected-ax5-insights-stop-waiting-whole-target.png) and [successful corner cancellation](screenshots/ui-03-affected-ax5-insights-corner-cancelled-without-error.png).
- Final Large [Today recheck](screenshots/ui-03-affected-large-today-recheck-whole-control.png) and [Insights recheck](screenshots/ui-03-affected-large-insights-recheck-whole-control.png).
- Final AX5 [Today recheck](screenshots/ui-03-affected-ax5-today-recheck-whole-control.png) and [Insights recheck](screenshots/ui-03-affected-ax5-insights-recheck-whole-control.png).
- Retained AX5 [Today complete transient feedback](screenshots/ui-02-ax5-today-complete-transient-feedback.png) and [Insights complete transient feedback](screenshots/ui-02-ax5-insights-complete-transient-feedback.png) at `85f0667`.

## Evidence and handoff

`manifests/` contains per-case source/results/durations, screenshot SHA-256 records, build identities, and before/after guest guards. `logs/` contains compressed build/native output and cleanup records. `harness/` freezes the initial and final test sources, wrappers, destination override, and baseline guard. These are historical verification tools with machine-specific paths and guest IDs; review and reauthorize any future device run instead of invoking them blindly. Original result bundles and recordings remain under `/tmp/flint-fixes-20261024/F4/review-followup`; no app binaries or `.xcresult` bundles are committed.

All build/device work used the shared serialization lock and only existing temporary guest `3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473`. No SE guest was created in this follow-up and the reviewer guest was untouched. At **2026-10-25 10:18:32 UTC**, final cleanup restored and read back **Large**, verified the absent-PIN baseline without logging values, and launched the production app without fixtures. App termination reported nothing to terminate because the tests had already stopped it; the following launch and final guard both succeeded. [Cleanup](logs/ui-03-affected-cleanup.txt), [final baseline](manifests/ui-03-affected-baseline-after.json). The parent owns subsequent combined verification and temporary-guest deletion; F4 has no remaining device/build run.

The [diagnostic record](diagnostics.md) preserves the initial strict AX-height failures, successful off-label taps, and observed Xcode/CoreSimulator transition. They are not counted as acceptance. This follow-up establishes simulator layout, bounds, scrolling, and observable fixture actions. It does not establish real Screen Time authorization/enforcement, full VoiceOver/TalkBack traversal, or a fresh compact-device/Android run. Original compact-device and Android evidence remains separately attributed in the linked F4 record.
