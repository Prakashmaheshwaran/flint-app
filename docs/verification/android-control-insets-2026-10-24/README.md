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
| Font scale 1.5 | Center Add and Close pass with keyboard hidden and shown; Cancel/Back preserve the confirmed draft. [Picker](after/large-selected.png), [keyboard picker](after/large-ime-selected.png), [tap log](picker-large-narrow.txt). |
| 320 dp viewport, font scale 1.5 | Footer wraps above tabs/IME; Add and Close pass with keyboard hidden/shown; Cancel/Back preserve the confirmed draft. The filtered Chrome row scrolls fully into view above the footer. [Wrapped footer](after/narrow-large-selected.png), [scrolled keyboard picker](after/narrow-large-ime-scrolled-selected.png), [IME-edge/tap log](picker-narrow-scrolled.txt). |
| Normal Rule / Sleep / App-limit editors | Headers clear the 136 px status bar; scrolled Save/Cancel targets clear tabs and the visible keyboard. Rule Save passes with IME hidden/shown; Sleep Save/Cancel pass. App-limit Save with a valid 45-minute value passes with the numeric keyboard shown; reopening confirms 45 and Cancel returns to the overview. [Rule IME](after/normal-editor-rule-bottom-ime.png), [Sleep footer](after/normal-editor-sleep-bottom.png), [Limit IME](after/normal-editor-limit-bottom-ime.png), [saved value](after/normal-editor-limit-saved-value.png), [tap log](normal-editor-proof.txt). |
| Editors at 320 dp / font scale 1.5 | All three headers clear the 102 px status bar. Rule Save passes with keyboard hidden/shown, and Cancel returns to Rules. Sleep Save/Cancel pass. A valid 45-minute Contacts limit saves above the numeric keyboard, reopens as 45, and Cancel returns to the Rules list. [Rule header](after/narrow-editor-rule-header.png), [Rule IME footer](after/narrow-editor-rule-bottom-ime.png), [Sleep Save](after/narrow-editor-sleep-bottom-save.png), [Limit IME](after/narrow-editor-limit-bottom-ime.png), [stored 45](after/narrow-editor-limit-saved-value.png), [action trace](narrow-editor-proof.txt). |

The final narrow-editor sequence restored app data with identical private-file
content hashes, font scale 1.0, native 1080×2400 resolution and the original keyboard
setting (1). Accessibility remained off; no security permissions were granted.
The shared slot was released with exit 0 and no remaining F1 waiter.

The normal-text Add path shows a live `1 SELECTED` count. Accepted editor actions
return to distinct Rules-list controls; recovery assertions also require editor
Save controls to be absent.
The harness uses controlled drags and stops when the viewport no longer moves.

Android System UI became unresponsive during the second narrow Sleep pass;
[the diagnostic capture](diagnostics/system-ui-anr.png) identifies System UI.
Wait did not clear the dialog; Close app recovered the underlying editor. The
saved state was then restored before releasing the slot. Only the remaining
Sleep Save and App-limit cases resumed after cold-booting the same AVD without
wiping data, and both passed. See [recovery identity](recovery.json).

Existing narrow option-card text wrapping is separate F4 work; this verification
establishes header and action clearance, scrolling and action destinations.

## Native regression

[`control_insets.py`](../../../android-app/ui-tests/control_insets.py) uses fresh
UIAutomator geometry and physical center taps, verifies Add's returned Chrome
draft, and checks Cancel and Back preserve the confirmed selection. It covers
normal and 1.5× text, keyboard hidden/shown, and a 320×686 dp viewport on the audit
emulator (840×1800 px at 420 dpi). Keyboard cases scroll the filtered app fully
into view; the current helper checks the visible IME frame from Android's display
insets state. Launch waits for activity startup; each input gets a fresh resulting
hierarchy. It starts no session and saves no rule; font,
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
