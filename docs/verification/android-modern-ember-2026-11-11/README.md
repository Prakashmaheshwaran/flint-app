# Android · v3 Modern Ember: pre-merge verification (2026-11-11)

Branch `design/modern-ember-android` (PR #246) after merging `origin/main` (#247–#254).
AVD `flint-test` (Android 15, `android-35;google_apis;arm64-v8a`), Debug build, driven
through T3's device tools (agent-device). Screenshots are downscaled to 540×1200 and
palette-reduced.

## Gates

| Check | Result |
| --- | --- |
| `make verify-android` (debug, release, bundle, unit tests) | Passed, both after the main merge and after the fixes below |
| `make android-e2e` at font scale 1.0, post-fix | 12/12 passed: `test-results.xml` |
| `make android-e2e` at font scale 2.0, post-fix | 12/12 passed: `test-results-font-2x.xml` (extra run, not a CI configuration) |

The e2e suite enables Flint's real AccessibilityService with shell authority, so the shield
captures (`12`–`15`) come from the real service. Its exit trap removed the grant afterwards.
The manual walkthrough ran without the service, because agent-device's UiAutomation helper
suppresses accessibility services. There, blocking reads "Setup needed" and Begin correctly
stays disabled ("Not armed").

## Walkthrough coverage

At 1.0 and 2.0 font scale: onboarding welcome, accessibility consent (step 1, decline path),
Today, the Start focus sheet (presets, app picker, break levels, Begin gating), Rules (empty
state, templates), the rule editor (apps, sites, groups, schedule days/times, break level),
the limit picker and editor, the Sleep editor, Insights Today/7 days and focus sessions,
You (health card, grants, notification card, permissions, troubleshooting, clear-history
dialog, How Flint works, privacy policy, licenses), and the block shield, time-limit shield
and running session from the e2e runs. The v3 theme is intentionally dark-only (`FlintTheme`
ignores `darkTheme`). Under the light and dark system settings it rendered the same, and the
system bars and dialogs stayed legible.

## Defects found and fixed (commit "v3 legibility and large-text defects")

| Defect | Before | After |
| --- | --- | --- |
| Rule name field drew black text on the dark editor (`BasicTextField` has no theme color) | `before/rule-name-black-on-dark.png` | `03`, `09` |
| Limit allowance field also drew black digits; the ring showed "0m" for an empty field that Save rejects | `before/allowance-black-on-dark.png` | `04` |
| Allowance steppers had duplicate "Less"/"More" labels | — | "Fewer/More minutes", "Fewer/More credits" |
| Today FOCUSED stat clipped "No focus sessions yet" to one numeral line | `before/today-focused-clipped.png` | `01`, `07` |
| You health banner ellipsized its body at 3 lines, hiding recovery guidance and the website-coverage disclosure that main shows in full | — | `05` |
| Notification card said "Heads-up before rules start?" though Android only notifies when a session ends | — | `05` |
| At 2.0×, the tab bar clipped its labels (fixed 56dp row) | `before/tabbar-labels-clipped-2x.png` | `07`–`11` |
| At 2.0×, day pills and break-level segments split words ("Mo/n", "Hardcor/e") | `before/days-and-segments-broken-2x.png` | `08`, `09` |
| At 2.0×, the health grant stats clipped "Accessibility" | — | `10` |

## Observed, not changed

- The privacy policy screen renders raw Markdown (`#`, `##`, `[text](url)`) with hard line
  breaks. `LegalScreen.kt` predates and is untouched by this PR (#102 on main).
- At 2.0× the consent screen's scroll area is short above its footer, but the disclosure
  scrolls fully to "Accept & continue" (`11`).
- The emulator proves layout and the shield paths exercised by e2e, not OEM, Play or
  physical-device enforcement.
