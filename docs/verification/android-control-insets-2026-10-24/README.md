# Android control insets — F1

PR [#66](https://github.com/Prakashmaheshwaran/flint-app/pull/66) is stacked on
[#63](https://github.com/Prakashmaheshwaran/flint-app/pull/63). Merge order: **#63 → #66**.
The normal merge preserves F7's live selection/count/confirmation behavior.

The original Add center tap selected You because the floating tab bar covered the
picker footer. Rule/Sleep Save and Cancel were also covered, and editor headers
overlapped the status bar. `before/` contains unchanged issue-specific evidence
from the October 24 audit at `53bb4553` (same tree as `ebc0d24f`). The additional
`before/keyboard-return-header.png` records the keyboard-return edge found on
`9dfc073`: the separate Start focus dialog expanded underneath the status bar.

The shell now measures the tab bar with `Scaffold`, applies and consumes its
safe-drawing padding around every tab navigation branch, and applies the remaining
IME inset. The resulting viewport is clipped above the measured bar. Picker
header/search/list content scrolls together; footer actions wrap when needed.
`adjustResize` explicitly enables IME resizing. The separate Start focus dialog
reserves its own top status-bar inset when fully expanded after search. Selection,
persistence, validation and blocking/security rules are unchanged by F1.

## Verification status

The combined F1+F7 implementation at `b04ce63df748e0e754d94456a87c52d66479d050` passed
`make verify-android`: debug/release builds, release local-first verification and
1,182 JVM test executions with no failures/errors/skips. CI also passed.
See [gate results](gate-summary.txt) and [build identity](build.json).

| Check | Result and evidence |
| --- | --- |
| Normal text, keyboard hidden | Center Add confirms Chrome; Cancel and Back preserve the confirmed draft; closing returns to Today. [Picker](after/normal-selected.png), [returned draft](after/normal-confirmed.png). Recorded on `9dfc073`, before the separate sheet's top-inset correction; picker/shell source is unchanged. |
| Normal text, keyboard shown | Center Add confirms Chrome, returning sheet clears the status bar, center Close returns to Today. [Picker](after/normal-ime-final-selected.png), [returned sheet](after/normal-ime-final-confirmed.png), [physical-tap log](keyboard-proof.txt). Recorded on fresh APK `b04ce63`. |
| Font scale 1.5 / narrow viewport | Pending next serialized device sequence. |
| Rule / Sleep / App-limit editors | Pending next serialized device sequence. |

The keyboard sequence restored app data with identical private-file content
hashes, font scale 1.0, native 1080×2400 resolution and the original keyboard
setting. Accessibility remained off; no security permissions were granted.
The shared slot was released for the higher-priority iOS lock checks.

The normal-text Add path shows a live `1 SELECTED` count. Worktree checkouts
disappeared during the first sequence; the
source commits and external logs survived, and app data/font/display settings
were restored. The selected-tab assertion was also corrected to observe Today
content rather than requiring the already selected tab to expose a click action.
Fresh evidence is saved under `/tmp/flint-fixes-20261024/F1/evidence/` before being
copied here. This PR remains a draft until the remaining matrix is complete.

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
Use `--only large --only narrow-large` to resume those viewport cases without
repeating normal-text checks.
Additional API levels, OEM hardware, TalkBack and physical-device enforcement
remain outside this emulator UI check.
