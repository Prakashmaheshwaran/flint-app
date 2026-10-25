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

`make verify-android` passed under the shared F1 wrapper, including debug/release
builds, the release local-first check and JVM tests. The wrapper has released the
shared slot. The local log is
`/tmp/flint-fixes-20261024/F1/verify-header-reflow.log`. The parent will
perform the focused normal-size and 320 dp / 2× picker acceptance on the combined
all-fixes APK after integrating this change. Those fresh visual results are
pending; the full F1 matrix and iOS gates are not repeated for this bounded repair.
