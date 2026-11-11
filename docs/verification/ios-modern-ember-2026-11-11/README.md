# iOS · v3 Modern Ember — simulator pass (2026-11-11)

Debug build of `design/modern-ember-ios` after merging `origin/main` (through #254, including
the #240 re-key), on the iPhone 17 simulator, iOS 27.0, driven through T3's device tools.
Screens were reached with the app's DEBUG fixtures: `-FlintUIPreview`,
`-FlintUIPreviewHistory sample|complete`, `-FlintUIPreviewSession harder`, and
`-flint.onboarded NO -FlintUIPreviewAuthorizationScenario transient` for onboarding.

**Not proven here:** the simulator has no Screen Time authority. Shields, Apple's app picker,
the system shield, the Harder break wait and populated rule/limit cards need a
Screen Time–authorized device. The unsigned simulator build also has no app-group container,
so the red "unavailable … existing protection was preserved" notices on Rules, Sleep and You
are the expected degraded state, not regressions.

## What was checked

At the default text size (Large) and the largest Dynamic Type size (AX5), with the system set to
light and to dark: the app is dark-only by design (`preferredColorScheme(.dark)`), and both
settings rendered identically, with no light chrome leaking into sheets, forms or nav bars.

- Onboarding: Welcome; Permissions, including the transient authorization failure and retry.
- Today, and the Start focus sheet: stepper, duration presets, templates, break levels, and
  Begin disabled with its reason.
- Rules; schedule editor; App limits and the limit editor; Open Limits; Sleep.
- Insights, Today and 7 days.
- You; How Flint works; About Flint, which opens the GitHub repo and its MIT license in Safari
  (unchanged from main).
- Running session (Harder), the Emergency Pass sheet, the honest refusal of End early when
  protection state is unreadable, and Session complete.
- Each tab's last row scrolls clear of the floating tab bar. Accessibility snapshots were read
  for labels, traits and selected state on every screen above.

## Defects found and fixed

| Evidence | Defect | Fix |
| --- | --- | --- |
| `05` | AX5: Welcome tiles and permission cards broke words mid-word ("featu/re", "Bl/oc/k"); "Allow notifications" overflowed its pill | Tiles and cards stack at accessibility sizes; notification actions stack; ghost buttons gain vertical padding |
| `06` | AX5: editor header ("Cance/l", "SCHE/DULE"), time cards ("09:0/0") and three-way segmented tracks ("Harde/r") broke mid-word; the limit picker row did the same | Shared `FlintEditorHeader` puts the title on its own line; time cards and full-width segmented tracks stack; the redundant "Change" label is hidden at AX sizes |
| `07` | AX5: the last Today/Rules rows stayed under the two-row tab bar (fixed 168 pt inset); the status pill truncated ("Needs atten…") | Inset uses the bar's measured height; the pill takes its own line |
| `07`, `08` | The session orb sat behind the "ends at" caption (AX5 always; default size around mid-session) | Arc apex lowered so the caption clears the orb at t = 0.5; the arc is left out at AX sizes, as M02 does |
| — | Start focus break levels and the Sleep retry row crushed their text beside a badge/button at AX5 | Badge moves under the copy; Retry stacks under the message |
| — | Voice Control: "Done" (spoken "Keep focusing"), "View" ("View in Rules"), and both "Not now" buttons had no input label matching their visible text; the editor Name fields had no label | `accessibilityInputLabels` with the visible text; `accessibilityLabel("Name")` |
| — | Open Limits' text-only state painted the ground only behind the text | The state fills the screen |

`08` was captured with the DEBUG session fixture temporarily moved to 50% progress. That
change was not committed.

## Files

`01`–`04` show the default size after the fixes. In `05`–`07`, each before/after pair has the
before on the left. `08` is the default-size midpoint, before and after.
