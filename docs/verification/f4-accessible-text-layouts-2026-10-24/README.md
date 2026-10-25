# F4 — Accessible text layouts

[PR #67](https://github.com/Prakashmaheshwaran/flint-app/pull/67) is stacked on F6 (#65), after F5 (#61). Android integration also requires #66 before #67 because it reserves the measured tab-bar height. F4 does not change MainActivity or merge F1 into this branch.

## Changes

iOS keeps complete tab names, wraps Today presets, adapts Start focus duration controls/options/footer, and reflows Rules and You rows. Protection status and its checked time have separate lines when needed. The root lays out flexible, clipped content above the tab bar's intrinsic height, giving navigation-backed scroll views real bounds and keeping the privacy note reachable. The Insights gradient is a background decoration, so its 470pt height cannot force a short viewport to expand. F6's authorization, availability, and report behavior are preserved.

Android measures scaled break-level titles before choosing columns or stacked cards. Today scrolls when its content exceeds the viewport. The Rule editor puts the Hardcore badge below its description when they cannot fit side by side. Tab labels retain equal columns when possible, share width according to measured text when needed, and have a two-row fallback. Full labels, text scaling, radio/tab semantics, selected state, haptics, and existing visual tokens remain intact.

No PIN, permission, picker, scheduling, or enforcement behavior changes. F5's inline errors, retry guard, dark feedback surfaces, and clipped Today viewport remain integrated. The measured root layout replaces fixed outer tab clearance, while retaining Today's 28pt content bottom spacing.

## Verification

| Check | Result and source |
| --- | --- |
| `make verify-ios` | PASS: 70 XCTest cases at final product source `d9d08bb` |
| `make verify-android` | PASS at `12bcca3`: debug/release builds, local-first release checks, JVM tests; Android source unchanged afterward |
| Android JUnit reports | 100 reports, 1,168 executions / 682 unique cases, zero failures/errors/skips; cached/up-to-date work reused |
| GitHub iOS, Android, GitGuardian | PASS at `d9d08bb`; saved in `logs/ci-d9d08bb.json` |
| Earlier full-screen iOS | Large layout and permission cases; AX5 layout, 240-minute controls, permission cases PASS on the `dec9530` host |
| Earlier Insights gradient regression | Large and AX5 top/tail checks PASS at `a6a2b3c`; paragraph ends above tabs |
| Final production-app native checks | Nine final-source case passes: You/About/complete privacy note and Today/Start/Rules/Insights navigation at Large and AX5 on iPhone SE3 (375×667) and iPhone 18 Pro (402×874), plus 18 Pro AX5 permission-error/retry. Results assembled from retained passing runs. |
| Supplemental 320×568pt fixture | Nine cases passed; You Large excluded from acceptance because the embedded safe-area/window boundary could not be validated |
| Android Timer | Normal width at 1.0×/1.5×/2.0×, plus 320dp at 2.0×: complete options, actual Hardcore selection, reachable Begin PASS |
| Android tabs | Normal width and 320dp/2.0×: complete labels inside ≥48dp targets and actual Insights selection PASS at `12bcca3` |
| Android Rule editor | Normal 1.0×, 320dp/1.5×, 320dp/2.0×: complete description/badge, actual Hardcore selection, unsaved-draft cancellation PASS |

Android draft cleanup used system Back and asserted the unique Rules destination with no editor fields. The standalone F4 base retains the footer-inset issue addressed by #66; these runs do **not** claim that the editor's covered Cancel button passed. The parent integration smoke owns centered Cancel and primary-control proof with both PRs combined.

Earlier full-screen iOS evidence ran on the `dec9530` host, unchanged through `516b27d`. The Insights gradient repair at `a6a2b3c` received a fresh gate and focused Large/AX5 regression. Real SE3 testing then confirmed that the privacy note could remain behind the tabs, so `d9d08bb` replaces the root's outer safe-area inset with an explicit content-and-bar layout. It received another iOS gate and focused production-app checks on both device sizes. Earlier captures remain historical evidence, not claims that every screen was retested at every commit.

## Evidence map

- `baseline-manifest.json`: original before images.
- `ios-native-manifest.json`: original passing full-screen captures; `ios-final-insights-manifest.json`: focused Insights captures at the earlier gradient repair `a6a2b3c`.
- `ios-narrow-manifest.json`: each retained passing narrow case, its source revision, result bundle, duration, and screenshot hashes.
- `ios-real-se-before-manifest.json`: confirmed production privacy-note obstruction before the root repair. `ios-final-native-manifest.json`: final-source production-app cases on both simulator sizes.
- `android-native-manifest.json` and `android-tabs-manifest.json`: native captures, source revisions, hashes, and fresh hierarchy snapshots; the latter includes the narrow tab defect before/after.
- `harness/`: frozen UI test/automation sources. Temporary host projects are evidence tools, not production app targets.
- `logs/`: compressed platform/UI logs, test counts, binary hashes, CI snapshots, and restoration records.
- `diagnostics.md`: failed/interrupted attempts and how their causes were distinguished from product defects. Failed captures do not count as acceptance.

Original result bundles and recordings are retained under `/tmp/flint-fixes-20261024/F4`; selected screenshots, hashes, logs, and harness sources are committed here. No app binaries or large result bundles are committed.

## Device state and limits

All builds and device sequences used the shared serialization lock with one guest at a time. Android was restored to font scale 1.0 and its original physical 1080×2400 display, then the exact `flint-test` AVD was stopped with data preserved. Final iOS cleanup restored Large text, verified the unchanged absent-PIN baseline without exposing key values, and shut down the shared iPhone before releasing the lock. The temporary F4 host was removed; all three task-created SE3 simulators were individually recorded, shut down, and deleted. Cleanup records confirm successful operations and no remaining task SE guest.

Simulators cannot establish Screen Time authorization/enforcement. Approved-state UI uses the existing debug preview; permission-error checks use F5's explicit UI-only fixture. The supplemental 320×568pt host embeds production views inside the iPhone 18 Pro scene; its safe-area limitations are documented in diagnostics. Authoritative compact-device acceptance uses the unchanged production app on a task-created iPhone SE (3rd generation) simulator at 375×667pt. Android's existing permissions were preserved. This evidence covers layout, accessibility labels, selected states, hit-target bounds and scrolling; it is not a full VoiceOver/TalkBack or physical-enforcement audit.
