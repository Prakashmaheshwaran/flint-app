# iOS Sleep presentation — 2026-10-28

## Provenance

- App source: `07bfdb761f4d4ccc2fc41a61593518a2a8735039` on `v1/h/ios-sleep`.
- Simulator: iPhone 17, `1BD4732B-EC1A-4074-8C70-6AC369607185`.
- OS: iOS 27.0 (`24A434`); 402 × 874 points, 1206 × 2622 pixels.
- Captured locally around 16:11–16:12 EDT using `simctl io screenshot`, after building/installing with semaphore-wrapped `make ios-run`. JPEG quality 70 preserves full dimensions; every image is below 300 KB.
- Dynamic Type: `large` and `accessibility-extra-extra-extra-large` (AX5), set with `simctl ui content_size`. No motion changed; no Reduce Motion claim.

## Observations

| Evidence | Observed result |
| --- | --- |
| [large.jpg](large.jpg) | Duration inside the dial; both localized time cards visible. |
| [ax5.jpg](ax5.jpg) | Duration outside the dial without clipping. Time cards stack below and are reachable by scrolling. |
| [large-days.jpg](large-days.jpg) | Seven 44-point weekday targets, Monday selected with fill, contrasting outline and heavier text. |
| [ax5-days.jpg](ax5-days.jpg) | Weekdays reflow to two columns with approximately 124-point targets; remaining rows scroll. |
| [ax5-dial-ax.json](ax5-dial-ax.json) | One “Sleep window” value: bedtime 10:00 PM, wake 7:00 AM, nine hours of quiet. |
| [large-days-ax.json](large-days-ax.json), [ax5-days-ax.json](ax5-days-ax.json) | Full weekday names and expanded frames. Orca's AX export does not expose the selected trait; `.isSelected` is verified in source, not by a VoiceOver audio run. |

Changing Dynamic Type while the Form was open initially left stale small weekday cells in pixels despite expanded AX frames. Recreating the lazy grid with `.id(dynamicTypeSize)` fixed this; the final screenshots show the rebuilt layout.

The fixture kept Sleep and both assists **off**, with default 22:00–07:00 times. Monday was selected once to inspect its cue, then deselected; the every-night summary and `large` text size were verified before cleanup. Screen Time permission was skipped. Two fresh AX samples and agreeing pixels preceded each tap; postconditions were checked without blind retries. `make clean-devices` released the owned helper and booted simulator, then the device semaphore holder was stopped.

This is simulator presentation evidence, not shield enforcement, Emergency Pass recovery, Always Allowed/emergency-calling availability, or physical-device evidence. The native Form remains; its allowed-group menu's selected value can still truncate at AX5, although AX retains the full value. Global pass recovery and future Sleep tier/default/off-switch behavior remain B/G work.

## Verification

- Local `flint-heavy ios -- make ios-build`: passed before capture; final `make ios-run` rebuilt source `07bfdb7` successfully.
- Local `xcodebuild build-for-testing`: passed for the presentation XCTest target before the final view-only grid refresh.
- Seven `SleepPresentationTests` cover localized weekdays, summaries, overnight and zero-duration handling, dial values, current tier labels, and invalid saved data. Execution is recorded by the PR's latest-head iOS CI, not inferred from compilation.
- No hand edits to `Flint.xcodeproj`; XcodeGen discovers tests through the existing recursive `Tests` source entry.

## Image SHA-256

```text
e86c0a9ac630e7172f5abf7e3ae2fbf63b60acd972ce78598fb53a92d959e61c  large.jpg
08a4666f3054e7e9c5034733611b4b13bb8348d4dcb00e5d4f9a5d728c296832  ax5.jpg
e732ec4b43e0c5e3c69e9b8519360a429edeef0f70acbf51a0404f93fb643a9c  large-days.jpg
f540c2ccd62f9964cb6294e4c4b45aa4c0a1210f0ed772a5bd9acf7eae1c9522  ax5-days.jpg
```
