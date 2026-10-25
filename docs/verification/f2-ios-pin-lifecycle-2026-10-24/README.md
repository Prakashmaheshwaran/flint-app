# F2 — iOS PIN lock lifecycle

A configured PIN now gates cold launch and every return from actual backgrounding, including
presented sheets and active-session covers. Brief inactive interruptions hide content without
re-locking. Changing/removing the PIN requires current-PIN authentication; backgrounding
expires a pending change. Existing saved hashes, including seven-digit PINs, remain usable.
This is an app UI lock. It does not add or replace OS Screen Time enforcement.

## Implementation

`FlintAppLock` owns lock state and PIN authentication for both RootView and Settings.
`AppLockWindow` puts the gate above the scene's SwiftUI presentations. UIKit lifecycle hooks
cover content synchronously for snapshot privacy, and SwiftUI scenePhase reconciles activation
on launch. Window/key/keyboard side effects consume committed state on the main queue, rather
than re-entering the model from `@Published`'s pre-assignment notification.

The inactive/background distinction follows Apple's documentation for
[willDeactivateNotification](https://developer.apple.com/documentation/uikit/uiscene/willdeactivatenotification)
and [didEnterBackgroundNotification](https://developer.apple.com/documentation/uikit/uiscene/didenterbackgroundnotification).

## Verification

| Check | Result |
| --- | --- |
| `make verify-ios` | PASS — 73 XCTest tests, zero failures; includes 13 lock/authentication cases |
| Native simulator XCTest | PASS — 3 cases, 137.7 seconds, zero failures |
| Synthetic PIN cleanup | PASS — UI removal succeeded; persisted PIN absent; no fallback cleanup required in the final run |
| GitHub iOS CI | [PASS](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40159354940) |

Tested product commit: `37a3563d5fb2ead87e17ce7a97ea3405b787d5f0` (clean checkout).
Worktree: `/Users/prakash/.codex/worktrees/f2-pin-lifecycle-recovery/flint`.
Later evidence-only commits do not change this product source.

Local logs and full attachments:
`/tmp/flint-fixes-20261024/F2/verify-ios.log`,
`/tmp/flint-fixes-20261024/F2/ui-committed-state.log`, and
`/tmp/flint-fixes-20261024/F2/ui-committed-state.xcresult`.
[Result metadata and screenshot checksums](results.json).

| Captured state | Evidence |
| --- | --- |
| Gate after setup → Home → reopen | [Screenshot](screenshots/01-resume-after-setup-gated.png) |
| Wrong current PIN cannot remove lock | [Screenshot](screenshots/03-wrong-current-pin-cannot-remove.png) |
| Gate above presented schedule sheet | [Screenshot](screenshots/05-gate-above-presented-schedule.png) |
| Authenticated removal updates App lock to Off | [Screenshot](screenshots/06-authenticated-removal-off.png) |
| Gate above full-screen active preview session | [Screenshot](screenshots/08-gate-above-active-session.png) |
| Inactive privacy cover under Control Center | [Screenshot](screenshots/09-transient-control-center-privacy.png) |

The shared iPhone 18 Pro / iOS 27 simulator was used, with no extra simulator booted and no
security permissions enabled. Every local build, install and device test ran under
`/tmp/flint-fixes-20261024/run_serialized.py --owner F2`. The app binary was built from the
recorded worktree before installation. Native XCTest screenshots and assertions verify the
observed UI; the active-session case uses the existing DEBUG preview because the simulator
cannot grant Screen Time access or supply real application tokens.

The 13 focused unit tests cover initial setup, cold launch, wrong-PIN rejection, repeated
background returns, transient inactive transitions, current-PIN authentication, one-use change
authorization, cancellation/background expiration, authenticated removal, external record
changes, a missing store, and legacy seven-digit records. All tests use disposable preference
suites.

The standalone native XCTest harness is in [harness](harness/). It targets the installed
`com.flint.peakfocus` app, so build/install the intended binary before running its XcodeGen
project. This harness exercises F2's setup UI on its main-based branch; F3 owns the replacement
secure setup form and maintains its own combined validation/lifecycle harness.

The test sequence checks:

- Set PIN → Home → reopen → wrong PIN rejected → correct PIN unlocks; repeat after unlock.
- Existing-PIN change and removal reject a wrong current PIN; successful change invalidates
  the old PIN and successful removal leaves no stale gate on resume or cold launch.
- Backgrounding an authenticated PIN-change form closes it and requires fresh authentication.
- Cold launch/resume gate stays above a schedule sheet and a full-screen preview session;
  underlying controls are inaccessible, and the UI works again after authentication.
- Control Center interruption hides content and returns to the unlocked UI without a loop.

Synthetic PINs are removed after testing. Guarded failure cleanup only accepts hashes matching
this audit's documented synthetic fixtures, clears just the PIN hash/salt, and preserves
unrelated app data. Failed intermediate runs are retained in the local evidence folder and
are not counted as acceptance passes.

## Integration and limits

F3 PR #60 is stacked on F2 PR #64. Preserve `PINSetupView(onSave: (String) -> Bool)` and route
its callback to `appLock.savePIN(candidate)`, with `appLock.cancelPINChange()` on dismissal.
Authenticate the current PIN before presenting that form. The shared save API checks the
persisted record, so it is compatible with F3's Bool-returning `FlintPIN.set`. F4 owns the
Settings health card, which F2 does not change.

Screen Time shielding, OS settings protection, anti-removal behavior, real-device interruptions
and every app-switcher/device timing are not established by these simulator UI tests. The
privacy cover is best-effort snapshot protection, not a claim that iOS cannot capture content.
