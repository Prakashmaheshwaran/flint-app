# F5 review follow-up — Screen Time recovery

This follow-up addresses the P2 and three P3 findings posted on PR #61 at `851ce84`. The original [24 October evidence](../README.md) is preserved. Its retry behavior and direct test-source compilation are superseded by the changes documented here.

## Result

- Denied access offers **Open Settings** and **Check access again**. A model guard refreshes real status and requests only for `.notDetermined`, so an older caller cannot re-prompt a denied state. Transient failures retain their complete error and **Retry Screen Time**.
- Today and onboarding use the same `AuthorizationControls`. Onboarding scrolls so its explanation, feedback, and actions remain reachable. F4's tab/preset work remains separate.
- A pending request has **Stop waiting**, a 120-second timeout, and caller cancellation support. Completion resumes the caller independently of provider cancellation cooperation. An attempt ID prevents old provider completions from changing a newer attempt. Stopping the wait does not claim to dismiss Apple's sheet.
- Foreground refresh preserves a pending `.notDetermined` request. A newly observed approval or denial resolves it. Swift cancellation and `FamilyControlsError.authorizationCanceled` do not produce a red technical error.
- `FlintTests` imports the production app with `@testable import Flint`. Its host-app dependency generates `TEST_HOST` and `BUNDLE_LOADER`; the second compilation of `AuthorizationModel.swift` has been removed.

## Apple API findings and limits

Apple's [requestAuthorization documentation](https://developer.apple.com/documentation/familycontrols/authorizationcenter/requestauthorization(for:)) describes individual authentication, a status update or error on completion, and silent success after prior authorization. It does **not** establish a universal guarantee about re-prompting after every individual denial. This implementation deliberately uses Settings recovery for `.denied` rather than promising another prompt.

[WWDC22's Screen Time session](https://developer.apple.com/videos/play/wwdc2022/110336/) describes the app-specific Screen Time Restrictions switch and Screen Time's Apps with Screen Time Access list after authorization. Flint's copy says to enable it **if listed**, and does not promise a switch exists after an initial denial. The only URL used is [UIApplication.openSettingsURLString](https://developer.apple.com/documentation/uikit/uiapplication/opensettingsurlstring), which Apple documents as opening the app's custom Settings page if available. No private Screen Time URL is used. In the tested Simulator, the public URL activated Settings and the captured destination was its main page; no actual authorization switch was changed.

The installed iOS 27 SDK was inspected for the public authorization statuses, async request API, cancellation error, and UIKit Settings constant. Real iPhone authorization, initial denial/revocation, Settings-switch availability, tokens, and enforcement remain **unverified**.

## Build and deterministic verification

- Product source: `d4b52f2028254e4f5f7537e8ee57edc8aec187fc`, branch `codex/f5-ios-permission-error-layout`, base `ebc0d24`.
- Worktree: `/Users/prakash/.codex/worktrees/f5-permission-errors-recovery/flint`.
- Full iOS gate **PASS**: app + four extensions and **75 tests, zero failures**, including **15 production-module authorization tests**.
- The repository `make verify-ios` ran with a temporary Makefile override that changes only the test destination and disables parallel Simulator clones. No tests were filtered. The normal gate chooses the first available iPhone; that would violate the parent's exact-guest restriction.
- The gate built the working tree subsequently committed as `d4b52f2`. No app-source changes occurred between that build, the commit, and the UI installation. The manifest records source and binary hashes.
- Product-head iOS CI and GitGuardian both passed at `d4b52f2` (iOS [run 40181345083](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40181345083)).

The tests cover denied/no re-prompt, existing approval/no re-request, transient retry, denial taking precedence over a provider error, return without approval, both cancellation error types, concurrent-call suppression, foreground refresh while the sheet is pending, external approval and revocation, external approval during a hung request, explicit stop and late completion during a newer request, caller cancellation of a noncooperative provider, an already-cancelled caller, and a deterministic timeout followed by recovery. The hung providers are suspended continuations that **ignore cancellation**; tests assert the caller has returned while the provider is still suspended, then deliver its late result. No wall-clock timeout is needed for model timeout coverage.

## Native UI verification

Final Large/AX5 run: **16 passed, zero failures** (eight at each size). The native harness covers actual Simulator helper failure/retry, denied guidance and Settings round trip, complete transient error plus separate session error and recovery, pending request across background/foreground followed by Stop waiting/recovery, timeout/retry/recovery, silent cancellation, onboarding denied controls, and an ordinary launch without synthetic state or a PIN gate. It asserts complete labels, relative placement, horizontal containment, the Today viewport above tabs, and enabled/hittable controls entirely within the viewport after scrolling. Screenshots were inspected for readable copy, contrast, and clipping.

| Flow | Large | AX5 |
| --- | --- | --- |
| Denied guidance and both actions | [Card](screenshots/large-denied-guidance-0.png) | [Beginning](screenshots/max-denied-guidance-2.png), [end and controls](screenshots/max-denied-guidance-3.png) |
| Open Settings destination | [Settings activated](screenshots/large-settings-destination.png) | Foreground/return assertions passed; no switch changed |
| Pending request and Stop waiting | [Controls](screenshots/large-pending-controls-0.png) | [Controls](screenshots/max-pending-controls-1.png) |
| Onboarding denied recovery | [Controls](screenshots/large-onboarding-denied-controls.png) | [Guidance](screenshots/max-onboarding-denied-3.png), [all controls](screenshots/max-onboarding-denied-controls.png) |
| Full transient error, separate session error | [Both messages](screenshots/large-transient-long-error-0.png) | [Explanation](screenshots/max-transient-long-error-0.png), [error beginning](screenshots/max-transient-long-error-1.png), [continuation](screenshots/max-transient-long-error-2.png), [end and retry](screenshots/max-transient-long-error-3.png) |
| Timeout recovery | [Message and retry](screenshots/large-timeout-guidance-0.png) | [Message and retry](screenshots/max-timeout-guidance-2.png) |
| Approval after transient retry | [Recovered](screenshots/large-transient-recovered.png) | [Recovered, session error preserved](screenshots/max-transient-recovered.png) |
| Ordinary launch, no fixtures/PIN gate | [Large](screenshots/large-ordinary-launch-cleanup.png) | [AX5](screenshots/max-ordinary-launch-cleanup.png) |

The existing tab and preset truncation visible at AX5 remains F4's separate scope; these images verify the permission feedback and recovery actions.

[Gate log](logs/verify-ios.log.gz) · [Large UI log](logs/ui-large.log.gz) · [AX5 UI log](logs/ui-max.log.gz) · [binary/install/size sequence](logs/sequence.txt) · [final cleanup](logs/cleanup.txt) · [source and evidence manifest](manifest.json).

The [native harness](harness/Tests/PermissionReviewTests.swift), [XcodeGen spec](harness/project.yml), and exact gate/UI/cleanup runners are saved under `harness/`. To repeat, copy the harness to a temporary directory, replace the destination/worktree/output paths with explicitly authorized current paths, regenerate the project, and freshly build/install the intended app. This temporary guest is scheduled for parent-owned cleanup and must not be assumed to exist later.

The first Large run passed seven checks and failed the onboarding post-skip assertion because its `-flint.onboarded NO` launch override continues to outrank the persisted value. The harness now removes that override before verifying the saved Not now choice. This was a harness-only correction; no product code changed. The earlier failed build was a missing `FlintCore` import in the new control; it was fixed before the passing gate. Exploratory logs are retained separately from final passing evidence.

Fixtures are explicitly DEBUG-only and process-local. They never read, grant, revoke, or persist Screen Time authorization. `denied`, `transient`, `hung`, `timeout`, and `cancelled` exercise view states. The `timeout` fixture uses two seconds; production uses 120 seconds. Native UI timing exercises a cooperative fixture, while the model tests establish recovery from a genuinely noncooperative provider.

## Guest ownership and cleanup

All builds and device operations use `/tmp/flint-fixes-20261024/run_serialized.py --owner F5-review`. The parent replaced the missing original guest with the dedicated temporary **Flint Review Followup 20261025**, iPhone 18 Pro / iOS 27, UUID **3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473**. New evidence never attributes work to the missing UUID. The unrelated reviewer guest is untouched by F5. UI preflight rejects any other booted guest; parallel test clones are disabled.

The UI exit trap restores Large text and launches the app without preview arguments. The ordinary-launch test verifies that authorization/session fixtures and a PIN gate are absent. The parent owns eventual deletion of this temporary guest and restoration of the reviewer guest; F5 does not delete or reset either guest.

## F6/F4 integration

F6 should replace its independent authorization error/Allow/Try again controls with `AuthorizationControls(auth: auth, identifierPrefix: "insights.authorization")`. An optional `onApproved` callback can reload its report. The view identifiers are `.action`, `.error`, `.guidance`, `.check`, and `.cancel` under the supplied prefix. Existing status/lastError/isRequesting/refresh/requestAuthorization access remains; `cancelRequest()` is new. Do not assign `lastError`. F6's denied description and any separate Check access again copy should also avoid promising a prompt. The model guard protects all callers even before this UI adoption.

Retain F4's adaptive presets and RootView safe-area inset when consolidating. Remove the legacy outer 104pt Today clearance once RootView reserves tab space; retain the clipped viewport and inline feedback surfaces. No StartFocusSheet, PIN, entitlement, account, analytics, or network changes were made.
