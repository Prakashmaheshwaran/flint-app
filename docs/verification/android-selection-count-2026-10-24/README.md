# F7 — Android live app selection count

The Today picker now derives its header, footer and Add label from its pending selection. The timer draft changes only when the picker confirms. Back/Cancel discards pending edits.

The full current app list resolves the selected packages into the confirmation payload. Search filters rows without reducing the count. Apps absent from the current list do not inflate the count or enable a meaningless confirmation; their package choices remain available if the list refreshes again. Rule/sleep callers retain responsibility for preserving unavailable saved entries. App limits keeps its contextual heading and immediate single-app selection.

Implementation source commit: `3b68362e3cc08f8a2c10d1b66b3270ffbd54546b`.
Source gate worktree: `/Users/prakash/.codex/worktrees/30ef/flint`.
Recovered managed worktree: `/Users/prakash/.codex/worktrees/f7-selection-recovery/flint`.

## Regression coverage

- Initial selection, including unavailable saved packages.
- `0 → 1 → 2 → 1 → 0` pending count and unchanged original selection.
- Filtered Select all/Clear preserves selected packages outside the filter.
- App-list removal/reappearance and refreshed app metadata.
- Only editable changes enable confirmation; removing the last initial app confirms an empty set.
- Timer draft remains unchanged until confirmation; reopening after cancellation uses the saved draft.

## Automated verification

- Targeted `:feature:feature-blocklist:testDebugUnitTest` for `AppPickerSelectionTest` and `TimerViewModelTest`: passed (6 selection tests + 26 timer tests).
- `make verify-android`: passed (debug build, release build, local-first APK check, all JVM test tasks). Reports contain 1,182 executions / 689 unique class+test pairs, zero failures, errors or skips. Gradle reused cached/up-to-date work; these totals do not imply every test reran fresh.
- [Android CI](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40157714083): passed on the implementation commit above.
- `git diff --check`: passed.

Full local logs: `/tmp/flint-fixes-20261024/F7/logs/selection-tests.log`, `verify-android.log`, and `test-summary.json`.

## Emulator verification — 25 October 2026

Fresh APK installation succeeded on `emulator-5554` (Android 15 / API35, 1080 × 2400, font scale 1.0). APK SHA-256: `5b8dc2077ed7a640a3d0cf7e61bff68c296c33767c5b2e671c3c92a7f51055ba`.

The initial standalone harness reached the empty picker, then stopped because F1 hides the Add text from the accessibility tree. No hidden Add-label check is claimed as passing.

The orchestrator subsequently assigned the full F7 interaction matrix to F1's combined APK, which contains the exact F7 source commit. The final harness requires visible/accessibly labeled Add buttons and normal center taps throughout; it has no exposed-edge workaround.

Combined artifact used for the passing run:

- F1 commit: `b04ce63df748e0e754d94456a87c52d66479d050`.
- F7 ancestor: `3b68362e3cc08f8a2c10d1b66b3270ffbd54546b` (verified with `git merge-base --is-ancestor`).
- APK SHA-256: `f9d3b84ab8182b6fc64eedb1369658b18b90b3a89ba787cd4ffcb527ca87e958`.
- Preserved binary and metadata: `/tmp/flint-fixes-20261024/F7/app-debug.apk` and `artifact.json`.

The complete run passed from 00:14:03–00:19:34 America/New_York (04:14:03–04:19:34 UTC), using normal control-center taps. All count assertions checked the visible header, the accessible `Add · N` label, and confirmation's enabled state.

| Scenario | Result / evidence |
| --- | --- |
| Select/deselect `0 → 1 → 2 → 1 → 0` | PASS: [0](screenshots/01-zero.png), [1](screenshots/02-one.png), [2](screenshots/03-two.png), [1 after deselection](screenshots/04-one-after-deselect.png), [0 after deselection](screenshots/05-zero-after-deselect.png) |
| Search hides a selected app; filtered Select all/Clear; empty results; clear search | PASS: Chrome remains counted when only Camera is shown. Bulk actions change only the filtered subset. [Filtered view](screenshots/06-search-hides-selected.png) |
| Cancel an empty draft; cancel edits to an existing draft; reopen | PASS: Cancel leaves the original empty draft unchanged; Back after adding Camera preserves the confirmed Chrome selection. [Reopened original selection](screenshots/19-reopen-after-second-cancel.png) |
| Confirm and reopen an initial selection | PASS: Add confirms Chrome, the draft shows Block 1 app, and reopening has Chrome checked with unchanged Add disabled. [Confirmed draft](screenshots/15-confirm-updates-draft.png) |
| Deselect the last initial app and confirm zero | PASS: `Add · 0` is enabled for the edit and returns the draft to Choose what rests. [Empty confirmed draft](screenshots/21-confirm-empty-draft.png) |
| Single-select App limits | PASS: contextual APP LIMITS heading, no multi-select checkboxes/Add button, and tapping Chrome immediately opens its editor. [Picker](screenshots/22-single-select-app-limits.png), [Editor](screenshots/23-single-select-immediate-confirm.png) |

[Full interaction trace](ui-interactions.txt) includes assertions, tap coordinates, source/APK provenance and cleanup. Each committed screenshot has a matching XML accessibility hierarchy beside it. [Unit-test summary](test-summary.json) records the source gate totals.

Cleanup passed: the persisted Flint preferences file was byte-identical before and after the run, font scale remained 1.0, and the app was force-stopped to discard transient drafts. No session or limit was saved and no permission grant was changed.

App-list removal/reappearance and metadata refresh are covered by the JVM regressions. Emulator assertions inspect accessibility labels/checkbox state; no spoken TalkBack or physical-device test is claimed.

## Overlap

F1 ([PR #66](https://github.com/Prakashmaheshwaran/flint-app/pull/66)) owns picker/navigation insets and stacks on this branch. Standalone F7 retains the audited F1 overlap; normal-path UI acceptance is evaluated on the combined F1+F7 build. F7 remains based on main and contains no inset changes.

The AppPicker shared-file merge must preserve the live `selectedCount`/`selectedApps` bindings, optional `eyebrow` default, pending-selection edits, and F1's layout changes. TimerScreen only removes its stale explicit eyebrow here; F1 owns its padding.
