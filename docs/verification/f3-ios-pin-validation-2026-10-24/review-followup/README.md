# F3 review follow-up

Application commit `6591c234db54e7c7dff6480624b963e8eaaf56e0` addresses both review nits on [PR #60](https://github.com/Prakashmaheshwaran/flint-app/pull/60), still stacked on [#64](https://github.com/Prakashmaheshwaran/flint-app/pull/64).

`FlintPINTests` now includes Arabic-Indic `١٢` (four UTF-8 bytes) and fullwidth `１２` (six bytes), so rejection exercises the ASCII predicate after the byte-length gate. The existing four-character Unicode examples remain. The short examples also participate in invalid initial/replacement and authenticated replacement checks.

`PINSetupView` observes the shared `FlintAppLock`. Both Save enablement and submission now require the lock's existing active-and-unlocked edit precondition, as well as valid matching entries. The authenticated save callback, cancellation, background invalidation, and persistence policy are unchanged.

## Results

| Check | Result |
| --- | --- |
| `make verify-ios` with approved destination override | PASS: app + four extensions built; 82 unit tests, including 9 PIN cases; zero failures |
| Native production-form state regression | PASS: 1 case, 65.705 seconds |
| Real-app secure setup/authentication/background regression | PASS: 1 case, 118.784 seconds |
| Source-commit GitHub checks | PASS: [iOS build-and-test](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40180600758) and GitGuardian |
| Cleanup | PASS: Large text, both PIN keys absent, zero fallback recovery; disposable host and its runner removed |

[results.json](results.json) records exact source, environment, test bundles and cleanup. [gate-evidence.txt](gate-evidence.txt), [form-evidence.txt](form-evidence.txt), and [ui-evidence.txt](ui-evidence.txt) retain the relevant test and accessibility output. Both UI runs completed without animation-idle warnings; existing XCTest deployment-target and metadata-extraction warnings remain outside this change.

The native form's disk record cleared after 2.031 seconds. The real-app checker initially observed a salt-only entry, then both keys absent after 0.256 seconds. Both runners required a further second of stable absence, stayed within the bounded polling window, and performed no fallback deletion. [form-manifest.json](form-manifest.json) and [ui-manifest.json](ui-manifest.json) retain these observations. This new evidence does not rewrite the historical wrapper failure or its reconciliation.

| Native evidence | Screenshot |
| --- | --- |
| Valid matching fields, inactive phase, Save disabled, zero callback attempts | [Inactive](screenshots/f3-review-inactive-save-disabled.png) |
| Active but locked, Save disabled, zero callback attempts | [Locked](screenshots/f3-review-locked-save-disabled.png) |
| Current-PIN authorization restores enabled Save | [Authorized](screenshots/f3-review-authorized-save-enabled.png) |
| One accepted save, then authenticated synthetic-record removal | [Isolated cleanup](screenshots/f3-review-isolated-store-clean.png) |
| Real-app authenticated secure replacement | [Secure form](screenshots/f3-review-authenticated-change-secure.png) |
| Real-app removal leaves App lock Off | [Final app state](screenshots/f3-review-real-app-pin-off.png) |

## Native regression design

The standalone [form-host](form-host/project.yml) compiles the unmodified production `PINSetupView`, design system and `FlintCore`. It keeps the form mounted while driving the real lock object through active, inactive, background and active-but-locked states. This makes the Save state directly observable while production privacy protection would normally hide the form. A callback counter verifies blocked actions never invoke persistence or show the generic save-failure message. Unlocking and authorizing the current PIN then permits a successful replacement.

The host is test-only and lives outside the production Xcode project. It uses a unique disposable UserDefaults suite and removes its synthetic PIN through the real authenticated removal API. No production preview hooks or authorization/project files were changed.

The existing real-app UI harness separately exercises genuine Home/background/resume, secure setup, wrong/current PIN challenges, cancellation, background invalidation, replacement and removal. The selected test is `FlintPINSetupUITests/testDSecureSetupIntegratesWithLifecycleAndAuthenticatedChanges`; its source is unchanged from [the historical harness](../ui-tests/Tests/FlintPINSetupUITests.swift).

## Environment and reproduction

The previously approved simulator UUID was absent from the live inventory. Preflight stopped before modifying any guest. The coordinator created the temporary iPhone 18 Pro / iOS 27 simulator `3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473` (Flint Review Followup 20261025). This follow-up uses only that replacement; it makes no claim to restore the missing device. The separate reviewer simulator was untouched.

All builds and simulator operations use `/tmp/flint-fixes-20261024/run_serialized.py --owner F3-review --timeout 60 -- …`. A temporary `xcodebuild` wrapper directs `make verify-ios` to the approved replacement and disables parallel testing; the Makefile and production project specification are unchanged. The baseline is Large text and no PIN. The coordinator owns final temporary-device removal and reviewer-device restoration.

Host reproduction: generate `form-host/project.yml` using XcodeGen, then run the `PINFormPhase` scheme on an explicitly approved simulator with parallel testing disabled. The host requires the production sources at their repository-relative paths. Real-app reproduction: install the freshly built Flint app and run the selected test from the existing `FlintAudit` scheme. Use the shared lock on this host.

The native form host validates view state against the real lock model; the real-app case validates lifecycle integration. Neither establishes physical-device Screen Time enforcement or anti-removal behavior.
