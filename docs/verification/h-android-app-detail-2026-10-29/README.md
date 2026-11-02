# Android Apps and sites + App/Site detail (PR #176) — emulator evidence, 2026-10-29

Presentation evidence only. No Accessibility grant, permission change or saved rule. Physical
TalkBack/Switch Access and enforcement remain unverified.

- Source `a16ea22` (PR head with review fixes, on integration/v1 after lane C's #177). Debug APK
  SHA256 `b06b8b9451afb20746e3463b4da59645acbbb46089439768c330a2b5d471d79d`.
- AVD `flint-test`, serial `emulator-5554`, API35 Google APIs ARM64, 1080x2400. Booted by
  `make android-run` and claimed in the worktree ledger. The session ran about 03:12–03:21Z.
- Before input, the configuration read font1.0, density420 with no override,
  `animator_duration_scale=null` and `enabled_accessibility_services=null`. Afterwards it was
  restored and read again with the same values. `make clean-devices` shut down this session's boot.
- Scrolling used the side gutter, so no gesture could start on a control.

| Capture | Configuration | What it shows |
| --- | --- | --- |
| `f1-rules-apps-and-sites-entry` | font1.0, density420 | The new "Apps and sites" entry, appended after daily limits on Rules (MainActivity supplies lane C's reader). |
| `f1-apps-and-sites-empty` | font1.0 | The list's title, intro and empty state. Hardware Back returned to the Rules overview (verified in AX). |
| `f2-rules-apps-and-sites-entry`, `f2-apps-and-sites-empty` | font2.0, density420 | The same at 2x without clipping. |
| `n320-rules-apps-and-sites-entry`, `n320-apps-and-sites-empty` | font2.0, density540 (320dp) | The same at the narrow width. A density change recreates the activity and Rules returns to its overview, as for the editors (destination state is deliberately not saved). |

**Not shown on a device:** the detail screen itself. The AVD has no saved picks, and this session did not
save a rule just to reach a screen, so the list is empty. Detail rendering at 320dp / 2x is covered by the
Robolectric `AppDetailLargeTextTest` with reader-shaped fixtures. Mapping from lane C's real
`TargetProtection` is covered by `AppDetailAdapterTest`, and the floor registry by
`TargetFloorIdentitiesTest`.
