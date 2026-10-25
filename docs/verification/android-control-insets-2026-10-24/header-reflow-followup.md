# Picker result-count reflow follow-up

The parent combined build `8373016` exposed a remaining header defect at 320 dp
and font scale 2: the result count rendered as `19 AP PS` beside Select all and
Clear. See the [unaltered reproduction](before/result-count-320dp-2x.png).

`AppPickerScreen.kt` now measures the count and bulk-action group at their natural
widths in a `FlowRow`. When they fit, the count stays left and the actions stay
right. When they do not fit, the action group moves to another line; its buttons
can also wrap within that group. Text size, font scaling and complete labels are
preserved. The filtered Select all/Clear handlers, selection/count derivation,
confirmation and footer are unchanged.

This follow-up starts from F1 `27e7054852b02c4d8b645824bf5606d1dded6c8b` and changes
only that source file plus this scoped evidence. The earlier native matrix in
the main README records implementation `b04ce63`; it is not a screenshot proof of
this subsequent reflow repair.

The source repair is committed as
`68270197dce91b013dbbdd251bd8207109f4ef2b`. `make verify-android` passed under the
shared F1 wrapper, including debug/release builds, the release local-first check
and 1,182 JVM test executions with zero failures, errors or skips. The local log
is `/tmp/flint-fixes-20261024/F1/verify-header-reflow.log`.

[Android CI](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40174873218)
and GitGuardian both passed on that exact source commit. Check IDs are
`121113289371` and `121113283258`, respectively; the
[check results](header-reflow/source-ci.json) are retained.

## Focused combined acceptance

The parent integrated the frozen source repair into combined commit
`7da70c0cc1b1280a1bfdda747b9408508c6f7343`. The final Android gate passed:
1,182 report executions / 689 unique test cases, zero failures, errors or skips.
The [build manifest](header-reflow/manifest.json) records that commit and all
included issue heads. The tested APK SHA256 is
`0e87f655c4b83cef46a7df492d81a6f3b0642b873661069e575a55d9a389c9b0`.

Both focused cases passed on that same APK. The result count is one complete
line; Select all/Clear retain full touch targets. Selecting Chrome immediately
shows `1 SELECTED` and `Add · 1`. A physical center tap on Add confirms Chrome in
the returned sheet; Close returns to Today. The parent and F1 visually reviewed
the count captures. The [final manifest](header-reflow/picker-header-ui-manifest.json)
records both passes and verified restoration of private-file contents, font scale,
display size and unchanged accessibility state.

| Case | Result count | Live selection and footer |
| --- | --- | --- |
| Normal 1× | [PNG](header-reflow/after/header-normal-1x-result-count-readable.png) / [hierarchy](header-reflow/after/header-normal-1x-result-count-readable.xml) | [PNG](header-reflow/after/header-normal-1x-live-count-and-footer.png) / [hierarchy](header-reflow/after/header-normal-1x-live-count-and-footer.xml) |
| 320 dp / 2× | [PNG](header-reflow/after/header-320dp-2x-result-count-readable.png) / [hierarchy](header-reflow/after/header-320dp-2x-result-count-readable.xml) | [PNG](header-reflow/after/header-320dp-2x-live-count-and-footer.png) / [hierarchy](header-reflow/after/header-320dp-2x-live-count-and-footer.xml) |

The before image is byte-identical to the parent's initial combined `8373016`
capture. The normal count bounds are `[63,568][200,606]`; the narrow count bounds
are `[63,601][338,676]`. At 320 dp / 2×, Select all and Clear move below the count
with full bounds `[63,687][432,825]` and `[432,687][679,825]`. Add has full bounds
`[417,1248][777,1386]`, above the tab-bar ceiling at 1512 px.

## Run chronology and retained diagnostics

The [first run](header-reflow/attempts/header-snapshot-read/android-header-ui.log)
passed the normal case, then failed a malformed tar snapshot read while preparing
the narrow case. Its [manifest](header-reflow/attempts/header-snapshot-read/picker-header-ui-manifest.json)
retains the normal pass and successful cleanup, with overall `passed: false`.
The [first narrow retry](header-reflow/attempts/header-partial-start/android-header-ui-retry.log)
stopped before entering the picker because Start focus was only partly scrolled
into view; its [manifest](header-reflow/attempts/header-partial-start/picker-header-ui-manifest.json)
also records overall failure and successful cleanup. These are harness/transport
diagnostics, not passed runs or product failures.

The harness now retries only malformed snapshot reads and scrolls until the
unchanged full-target assertion passes. The [final narrow retry log](header-reflow/android-header-ui-retry2.log)
records the successful narrow case and retains the already-passed normal case
after checking the same source, APK and restored baseline. The parent wrapper
exited 0 and released the shared slot. No product source changed during retries.

The final [picker harness](header-reflow/picker-header-ui.py),
[UI helper](header-reflow/native_ui.py), and earlier attempt harnesses are frozen
copies retaining their original host paths and shared-lock requirement.
[SHA256 records](header-reflow/artifact-sha256.json) identify every copied artifact.
Private backups, malformed snapshot payloads, APKs and build directories are not
included. This is a docs-only follow-up to source `6827019`; the full F1 matrix
and iOS gates were not repeated for this bounded Android repair.
