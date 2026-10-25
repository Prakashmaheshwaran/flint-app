# F5 — readable Today permission errors

**25 October review follow-up:** [Denied recovery, cancellation/timeout handling, production-module tests, and new verification](review-followup-2026-10-25/README.md). The report below preserves the original implementation and evidence; the follow-up supersedes its retry-state and test-target descriptions.

The 24 October audit found that the red Screen Time error occupied an independent bottom overlay and covered the permission explanation. Today now lays out the explanation, the full error, and its retry action sequentially. Dark feedback surfaces preserve contrast against the landscape. Content scrolls in a clipped viewport above the existing floating tab bar. Session errors also stay in this flow; they no longer mask an authorization error.

Authorization requests have one in-flight guard shared across callers. The action is disabled while requesting; each later retry calls the authorization provider again. Failed requests retain the reported system state and error. A return without approval leaves access off and gives a retry explanation. Refreshing an approved state clears stale authorization feedback.

## Build and state verification

- Worktree: `/Users/prakash/.codex/worktrees/6b27/flint`
- Branch: `codex/f5-ios-permission-error-layout`
- Application source: `46d30c2d968c508818bd5aa1226d4ec6fcdcf593`, based on `ebc0d24f2b0b71b99c16a308103a1ff41563abb7`. Later commits add verification artifacts only.
- `make verify-ios`: **PASS**, app and four extensions built; **64 XCTest cases, zero failures**, including four new authorization-state tests. This gate and the UI sequence ran consecutively under one shared lock on the committed source.
- [Complete build/test log](logs/verify-ios.log.gz).

The new tests cover overlapping calls, failure state and retry, approval after failure, approval observed during refresh, and a provider return that does not approve access. They use injected status/request closures; no real Screen Time permission is granted by the tests.

## Simulator UI verification

**PASS: six UI test executions, zero failures**, on iPhone 18 Pro / iOS 27 Simulator. The freshly installed application came from this worktree's build. The native harness asserts separate explanation/error/action bounds, full error labels, horizontal containment, a scroll viewport above the tabs, and reachable enabled actions. Screenshots were also inspected for contrast and clipping. The long permission fixture is taller than the screen at maximum size and is read by scrolling.

| Flow | Large | Accessibility Extra Extra Extra Large |
| --- | --- | --- |
| Actual helper error, then retry | [Readable result](screenshots/large-real-permission-retry.png) | [Complete explanation, error and retry](screenshots/max-real-permission-retry.png) |
| Multiline permission error plus separate session error | [Both messages](screenshots/large-long-permission-0.png) | [Explanation](screenshots/max-long-permission-0.png), [error beginning](screenshots/max-long-permission-1.png), [continuation](screenshots/max-long-permission-2.png), [end and retry](screenshots/max-long-permission-3.png) |
| Session error survives opening/closing Start focus | [Return to Today](screenshots/large-session-return-to-today.png) | [Beginning](screenshots/max-long-session-1.png), [continuation](screenshots/max-long-session-2.png), [end and Start focus](screenshots/max-session-return-to-today.png) |

[Large UI log](logs/ui-large.log.gz) · [maximum-size UI log](logs/ui-max.log.gz) · [build identity and cleanup](logs/sequence.txt) · [checksummed manifest](manifest.json).

All heavyweight builds and device operations used `/tmp/flint-fixes-20261024/run_serialized.py --owner F5`. The UI sequence installed this worktree's just-built app, recorded its binary hashes, and restored Large text size plus an ordinary launch in an exit trap. Preview errors are process-local and do not persist. The cleanup log's harmless "found nothing to terminate" comes from the harness already terminating the app in teardown; the subsequent ordinary launch succeeded and `simctl ui content_size` returned `large`.

The standalone [native test harness](harness/Tests/PermissionTests.swift) and [XcodeGen spec](harness/project.yml) are saved for repetition. Copy `harness/` to a temporary directory before generating it. Under the shared lock, freshly build/install the intended Flint Debug app, generate the harness, and run its `FlintF5` scheme with `xcodebuild test -parallel-testing-enabled NO -collect-test-diagnostics never` against the same Simulator. Run once per `simctl ui <device> content_size large` / `accessibility-extra-extra-extra-large`, and restore `large` with an ordinary app launch afterward. The harness targets bundle `com.flint.peakfocus` and never grants permissions.

## Scope and integration

- Real iPhone authorization, denial/revocation sheets, app tokens, and shielding are **not verified**. The Simulator helper failure is retained honestly.
- `-FlintUIPreviewAuthorizationError <message>` is a DEBUG-only failure fixture. It never grants authorization or changes the system status. `-FlintUIPreviewSessionError <message>` supplies a DEBUG-only session error without starting a session. The session-layout check uses the existing `-FlintUIPreview` approved-UI flag and does not establish real approval.
- F4 owns preset, tab, and health layouts. When combining fixes, keep F4's adaptive presets and RootView safe-area inset. Remove the legacy 104pt outer Today clearance once RootView reserves tab space; retain the clipped scroll viewport, inline error surfaces, and authorization action. StartFocusSheet remains unchanged here. The pre-existing tab/preset truncation at maximum text size belongs to F4.
- F6 keeps the existing authorization method/property contract; its test-source additions in `project.yml` are additive with this PR's model source.
- No account, analytics, network, entitlement or security-permission changes. No generated `.xcodeproj` edits.
