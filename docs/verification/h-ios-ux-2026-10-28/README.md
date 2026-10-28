# Lane H iOS UX evidence — 2026-10-28

Simulator screenshots for lane H's iOS presentation PRs. **UI evidence only:** the Simulator
cannot show Screen Time shields, real app tokens or enforcement, and no claim here is about
protection behavior.

- **Device:** iPhone 17 Simulator `1BD4732B-EC1A-4074-8C70-6AC369607185`, iOS 27.0 (24A434),
  installed with `make ios-run` (Debug) and driven with Orca emulator taps (each tap preceded by
  two AX samples and confirmed by screenshot).
- **Build:** local merge `b9cf7e6` = `integration/v1` `31ac386` + #111 `0496418` + #115 `2a7ca86`
  + #116 `3d4604a`. Later review-fix commits on #115 (`SelectionSummaryRow` VoiceOver label,
  optional category note, plural inflection) and #116 (deferred announcement, hidden scroll bars)
  do not change these empty-state/visible layouts.
- **Text size:** "Large" = `content_size large` (default); "AX5" = `accessibility-extra-extra-extra-large`.
  Screens with `-flint.onboarded NO` show onboarding; `-FlintUIPreview` is the DEBUG-only
  authorization fixture used to reach the tabs. Images are downscaled to 590 px wide JPEG.

| File | Screen | Observation |
| --- | --- | --- |
| `welcome-large.jpg` | E01 Welcome (#107) | Matches the E01 composition; promises on one line. |
| `welcome-ax5.jpg` | E01 Welcome (#107) | Promises wrap one per line instead of shrinking; Begin and footer stay on screen. Nit: "No account" overlaps the orb glow at AX5 (follow-up). |
| `permissions-large.jpg` | E02 Permissions (#107) | Designed sky, "guard / your focus" break, one permission row, Allow / Not now. |
| `permissions-ax5.jpg` | E02 Permissions (#107) | Sky spacer collapsed, orb omitted, title wraps naturally; content scrolls to the controls. |
| `you-large.jpg` | E16 You (#111) | "How Flint works · Strictness, Emergency Pass and limits" row replaces "Stronger protection". |
| `help-large.jpg` | Help (#111) | Strictness section with the reviewed, current-behavior copy. |
| `help-ax5.jpg` | Help (#111) | Title and text scale; content scrolls behind the two-row tab bar. |
| `startfocus-large.jpg` | E04/E05 Start focus (#115) | `SelectionSummaryRow` empty state: "Choose what rests / Apps, categories and sites". |
| `startfocus-ax5.jpg` | E04/E05 Start focus (#115) | Row wraps inside its card; duration chips reflow to two rows (existing). |

Sleep (E11, #110) evidence is in `docs/verification/h-ios-sleep-2026-10-28/`; Android Welcome and
picker (#109) in `docs/verification/h-android-a11y-2026-10-28/`.

**Not captured / still human-owned:** selected-app icons in `SelectionSummaryRow` (needs real
Screen Time tokens on a signed device); the app-lock PIN gate and Set PIN form at AX5 (#116 —
lane I's fixture XCUITest exercises Set PIN at AX5 and should be rerun on top of #116);
VoiceOver rotor/traversal and announcements on a physical iPhone.
