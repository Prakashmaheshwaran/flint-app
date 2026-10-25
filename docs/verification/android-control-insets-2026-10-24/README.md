# Android control insets — F1

PR [#66](https://github.com/Prakashmaheshwaran/flint-app/pull/66) is stacked on
[#63](https://github.com/Prakashmaheshwaran/flint-app/pull/63). Merge order: **#63 → #66**.
The normal merge preserves F7's live selection/count/confirmation behavior.

The original Add center tap selected You because the floating tab bar covered the
picker footer. Rule/Sleep Save and Cancel were also covered, and editor headers
overlapped the status bar. `before/` contains unchanged issue-specific evidence
from the October 24 audit at `53bb4553` (same tree as `ebc0d24f`).

The shell now measures the tab bar with `Scaffold`, applies and consumes its
safe-drawing padding around every tab navigation branch, and applies the remaining
IME inset. The resulting viewport is clipped above the measured bar. Picker
header/search/list content scrolls together; footer actions wrap when needed.
`adjustResize` explicitly enables IME resizing. Selection, persistence, validation
and blocking/security rules are unchanged by F1.

## Verification status

The combined F1+F7 source at `702746ef8ca837fcc5adc37b1a44beda34b78874` passed
`make verify-android`: debug/release builds, release local-first verification and
1,182 JVM test executions with no failures/errors/skips. CI also passed.
Fresh-APK UI proof is being completed before this draft PR is marked ready.

The initial normal-text Add path returned Chrome to Start focus with a live
`1 SELECTED` count. Worktree checkouts disappeared during that sequence; the
source commits and external logs survived, and app data/font/display settings
were restored. The selected-tab assertion was also corrected to observe Today
content rather than requiring the already selected tab to expose a click action.
The final matrix will be rerun with evidence saved outside the worktree first.

## Native regression

[`control_insets.py`](../../../android-app/ui-tests/control_insets.py) uses fresh
UIAutomator geometry and physical center taps, verifies Add's returned Chrome
draft, and checks Cancel and Back preserve the confirmed selection. It covers
normal and 1.5× text, keyboard hidden/shown, and a 320×686 dp viewport on the audit
emulator (840×1800 px at 420 dpi). It starts no session and saves no rule; font,
display and keyboard settings are restored in `finally`.

Use a disposable emulator with Chrome, completed onboarding and no active focus
session. Build/install this branch and run the test in one exclusive device
sequence. For the shared audit emulator, wrap the sequence in
`python3 /tmp/flint-fixes-20261024/run_serialized.py --owner F1 --timeout 60 -- ...`.

```sh
make verify-android
adb -s emulator-5554 install -r android-app/app/build/outputs/apk/debug/app-debug.apk
FLINT_UI_EVIDENCE=/tmp/flint-control-insets-proof python3 android-app/ui-tests/control_insets.py
```

Set `ANDROID_HOME` to the installed SDK; set `ADB`/`ANDROID_SERIAL` if needed.
Additional API levels, OEM hardware, TalkBack and physical-device enforcement
remain outside this emulator UI check.
