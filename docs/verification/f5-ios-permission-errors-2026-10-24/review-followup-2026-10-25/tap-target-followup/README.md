# Permission recovery tap-target follow-up

F4's composed-layout check measured **18.333pt** for Check access again's accessibility Button frame at Large in both Today and Insights. The original `.frame(minHeight: 44)` wrapped a Text-initialized Button, so the reserved row height did not establish the intended accessibility bounds. F4 subsequently confirmed that upper- and lower-edge Stop waiting taps already responded outside the pre-fix text frame. The confirmed defect is the small accessible Button frame, **not a demonstrated physical tap failure**. The earlier F5 harness checked reachability and containment but did not assert a minimum target size.

Product commit **08bbc204b9074e9fd56a9efa26faad96e995b155** puts the full-width, minimum-44pt frame **inside the button label**, with a rectangular content shape and plain button style. Check access again and Stop waiting share that label. Onboarding's Not now uses the same pattern. The reserved row height is unchanged. Authorization state, timeout/cancellation logic, and public APIs are unchanged from `d4b52f2`.

## Verification

- Full iOS gate **PASS** again: app + four extensions; **75 tests, zero failures**, including all 15 authorization-state regressions.
- Actual toolchain: **Xcode 27.1, build 27A9275**. The gate records this before building. Destination remains the parent-authorized iPhone 18 Pro / **iOS 27.0** temporary guest `3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473`.
- Native UI run: **6 passed, zero failures**, limited to the three affected flows at Large and AX5: denied Settings/check/return; pending request/foreground/Stop waiting/recovery; onboarding denied/check/Not now. The harness asserts that the actual accessibility Button frame is at least 44pt in both dimensions. It taps the padded corner of Stop waiting and Not now (normalized x=0.05, y=0.1), outside the centered text, and verifies cancellation/recovery or the saved onboarding completion. This establishes the corrected control's interaction bounds without claiming the earlier control dropped taps.
- The prior [16 broader UI checks and their screenshots](../README.md) remain preserved at their original source identity. Their model/state coverage is not relabeled as a run on this later binary.

The gate uses the repository Makefile with the same temporary destination-only override and parallel Simulator clones disabled. No unit tests are filtered. The targeted UI runner uses explicit `-only-testing` selections to avoid repeating unrelated cases. All heavy builds/device work remains under the shared lock; no unrelated Simulator or service was restarted.

## Evidence and integration

The [manifest](manifest.json) records the product commit, source and binary hashes, gate/UI summaries, exact harness/runners, measured button frames, and cleanup. F5 restored Large text and an ordinary launch without preview arguments at 10:05:27 UTC before releasing the guest. Parent-owned guest deletion/restoration remains outside F5's actions.

| Accessible Button frame | Large | AX5 |
| --- | --- | --- |
| Check access again (Today/onboarding) | 322 × 44pt | 322 × 101.667pt |
| Stop waiting | 322 × 44pt | 322 × 51pt |
| Not now | 354 × 44pt | 354 × 51pt |

[Gate log](logs/verify-ios.log.gz) · [Large UI log](logs/ui-large.log.gz) · [AX5 UI log](logs/ui-max.log.gz) · [measured frames](logs/button-frames.txt) · [binary, toolchain and cleanup sequence](logs/sequence.txt).

| Flow | Large | AX5 |
| --- | --- | --- |
| Denied controls | [Card](screenshots/large-denied-return.png) | [Card](screenshots/max-denied-return.png) |
| Pending request / Stop waiting | [Controls](screenshots/large-pending-controls-0.png) | [Controls](screenshots/max-pending-controls-1.png) |
| Onboarding / Not now | [Controls](screenshots/large-onboarding-denied-controls.png) | [Controls](screenshots/max-onboarding-denied-controls.png) |

The [updated native harness](harness/Tests/PermissionReviewTests.swift) and exact runners are saved in `harness/`; use the `tap-targets` argument with the UI runner. Replace its historical workspace/output/destination paths with current authorized paths when repeating. Prior evidence remains intact; no unrelated flows were re-run for this label-sizing change.

All six selected screenshots were visually inspected. The affected controls are readable and fully visible at the captured scroll positions. The known AX5 shared-tab text truncation remains visible in this standalone F5 branch and belongs to F4's composed-layout work. Real iPhone grant, denial, revocation, Settings switch availability, and enforcement remain unverified; the Simulator check establishes Settings activation and app recovery controls only.

F6 must consume `08bbc20` before F4 takes the updated F6 branch, because both Today and Insights use `AuthorizationControls`. No caller API change is required. `onboarding.skip` is an additional accessibility identifier. Keep F4's minimum-target assertion; this fixes the control rather than relaxing the test.

The earlier evidence-only commit was amended to express its verified source digests as explicit `path`/`sha256` fields after GitGuardian classified three Authorization-named file keys and their hashes as generic secrets. The values were recomputed and verified as SHA-256 checksums, and the scanner passed after the format change. No detection setting or review was bypassed, dismissed, or disabled.
