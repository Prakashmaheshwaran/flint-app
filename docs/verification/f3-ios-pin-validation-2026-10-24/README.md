# F3 — secure iOS PIN setup

The historical results below are preserved. The subsequent review fixes and their new verification are recorded in [review-followup/README.md](review-followup/README.md).

The PIN setup sheet accepts exactly 4–6 ASCII digits, asks for confirmation, and conceals both entries. Invalid or mismatched input leaves Save disabled with a format hint or inline error. Neither the form nor the core setter trims, filters, or truncates the entered value. The core setter refuses invalid replacements without modifying the saved record. Existing PIN verification and the salted-hash format are unchanged, including legacy seven-digit or nonnumeric PINs.

Combined implementation: `faa54baca331ca6d424fa9033b69bbfb5d30c2e2` in `/Users/prakash/.codex/worktrees/f3-pin-validation/flint`, incorporating F2 `9c2e65c64a245b6329dd8ed4b84d58ac0134bb41` by a normal merge (F2 application source last changed at `37a3563`). [PR #60](https://github.com/Prakashmaheshwaran/flint-app/pull/60) is stacked on [#64](https://github.com/Prakashmaheshwaran/flint-app/pull/64): merge #64 first, retarget #60 to `main`, then merge #60. Later evidence-only commits do not change the tested application source.

## Verification

- `make verify-ios`: PASS at the combined implementation above. The app and four extensions built; 81 unit tests passed with zero failures, including 8 focused PIN tests. The focused cases cover all valid lengths/leading zeros, blank/short/overlong input, letters, whitespace, Unicode/control characters, invalid replacement preservation, legacy verification/change/removal, unavailable storage, and an authenticated invalid replacement followed by a corrected save.
- Simulator UI: all 4 cases PASS in 336.954 seconds, with zero animation-idle warnings. Covers matching invalid lengths, real malformed paste, secure confirmation, valid 4/5/6-digit persistence and cold-launch unlock, background gating, authenticated change/removal, and cancellation/background invalidation. Fresh installation from this worktree; iPhone 18 Pro / iOS 27, `4A9BF370-052B-4A38-B05C-0D5EBD91ADD4`; Xcode 27.0 (27A266a). The standalone XCTest harness is an isolated adaptation of the audit harness.
- Targeted background-change regression: PASS in 105.207 seconds with zero animation-idle warnings, ordinary UI cleanup successful, zero fallback recoveries and no stored PIN at the end. The same D assertions then run again as part of the complete suite.
- All local builds and simulator operations ran inside `/tmp/flint-fixes-20261024/run_serialized.py --owner F3`. Unit tests use disposable preference suites.

Full local gate log: `/tmp/flint-fixes-20261024/F3/verify-ios.log`. Result-bundle paths are recorded in `results.json`.

## Evidence and reproduction

`results.json` records the tested source commit, build path context, simulator, test counts, result bundles and cleanup. `ui-evidence.txt` keeps the assertions and relevant accessibility snapshots. Secure values are omitted in the screenshots; the accessibility snapshots identify `SecureTextField` and report only masked bullet values.

| Scenario | Screenshot |
| --- | --- |
| Matching seven-digit entries cannot save | [Length error](screenshots/f3-seven-digits-rejected-secure.png) |
| Pasted mixed text is rejected without filtering | [Paste error](screenshots/f3-invalid-paste-rejected.png) |
| Confirmation must match | [Mismatch](screenshots/f3-confirmation-mismatch.png) |
| Six-digit secure setup and later unlock | [Secure form](screenshots/f3-valid-6-digits-obscured.png), [unlocked](screenshots/f3-valid-6-digits-unlocked.png) |
| Newly saved PIN gates return from background | [Background gate](screenshots/f3-combined-setup-background-locked.png) |
| Authenticated change retains secure confirmed entry | [Change form](screenshots/f3-combined-authenticated-change-secure.png) |
| Removal rejects the old PIN after replacement | [Current-PIN challenge](screenshots/f3-combined-removal-requires-current-pin.png) |
| Final UI removal leaves app lock off | [Cleanup](screenshots/f3-combined-cleanup-pin-off.png) |

The `ui-tests/` folder contains the standalone XcodeGen/XCTest harness used for the combined run. It targets the separately installed Flint app and is intentionally outside the ordinary unit-test gate. Use a disposable simulator with no pre-existing PIN. Generate the harness with `xcodegen generate` in that folder, install the freshly built Flint app, and run its `FlintAudit` scheme with `-parallel-testing-enabled NO`. On the shared audit host, all build/install/device/test steps must run through `/tmp/flint-fixes-20261024/run_serialized.py --owner F3 --timeout 60 -- …`. The recorded combined command was:

```sh
python3 /tmp/flint-fixes-20261024/run_serialized.py --owner F3 --timeout 60 -- \
  bash /tmp/flint-fixes-20261024/F3/verify_sheet_dismissal.sh
```

The harness restores the simulator clipboard and exercises removal of its synthetic PINs. The surrounding runner refuses a pre-existing PIN. Final stored-key absence was confirmed by the separate read-only reconciliation below. Font size and security permissions are unchanged.

Malformed-input cases use real clipboard paste because XCTest's number-pad typing cannot insert letters.

The integrated background-change case also checks that dismissal of the secure setup sheet completes. An earlier run reproduced persistent XCTest animation-idle waits after that sheet was backgrounded, including after unlock. That run was stopped and its matching synthetic PIN record was recovered under the shared lock. It is retained in `/tmp/flint-fixes-20261024/F3/ui-sheet-idle-interrupted-manifest.json` and is not acceptance evidence. The scoped correction disables presentation animations during the existing background resets; the final result records the targeted rerun and full suite separately.

## Cleanup reconciliation

The final XCTest bundle reports four passing cases, including removal and the final no-gate UI check. The temporary runner's immediate disk checker then observed PIN-key presence and refused recovery of an unrecognized record. The wrapper exited 1 before saving its XCTest exit-code/cleanup fields. The original incomplete manifest is preserved in `ui-passed-incomplete-manifest.json`; this wrapper failure is not described as a clean pass.

At 04:57:03 UTC on October 25, a separate read-only check under the shared F3 lock found both hash and salt keys absent in all three one-second-spaced samples. It performed no deletion or recovery. `post-run-storage-check.json` records only key presence/type metadata, and `ui-cleanup-reconciliation.json` ties that later absence to the passing XCTest result. No unknown record was deleted. The intermediate key shape was not captured, so persistence timing is a supported explanation, not proof of a particular orphan-key state.

The temporary runner was subsequently hardened to poll for at most 10 seconds and require one second of stable key absence. It distinguishes any key presence from a complete two-string record, saves the XCTest result before cleanup, saves metadata on errors, and validates every candidate before any guarded recovery. Device-free synthetic checks passed for absent/known/orphan records, bounded polling, stable absence and refusal before mutation. This helper correction did not change application source or XCTest assertions. Its exact host-local patch is `/tmp/flint-fixes-20261024/F3/ui-runner-persistence-correction.patch`; the four passing cases were not rerun solely for that checker correction.

## Integration with F2 and F4

F3 replaces Settings' alert and its `newPIN` state with `PINSetupView(onSave: appLock.savePIN)` in a sheet. The form dismisses on a successful callback or cancellation; failed saves stay open. F2 owns existing-PIN authentication and app-lock lifecycle.

The integrated Settings code retains F2's current-PIN challenge and background invalidation. Its sheet `onDismiss` calls `appLock.cancelPINChange()` to cover both Cancel and swipe dismissal. Background presentation resets use a transaction with animations disabled: the separate lock window must not leave the secure sheet's dismissal transition pending. The same cancellation/authentication state resets remain in place. `FlintPIN.set` now returns a discardable Bool; old statement-style call sites remain source-compatible. The F4 health card is untouched.

## Limits

Simulator results establish PIN input, persistence and the tested lifecycle/authentication interactions. They do not establish physical-device Screen Time enforcement or anti-removal behavior. Existing extension exhaustiveness/ExtensionKit and XCTest deployment-target warnings remain outside F3.
