# Android kit adoption (PR #179) — emulator evidence, 2026-10-29

Presentation evidence only. No Accessibility grant, permission change, or saved rule.
Physical TalkBack/Switch Access remains unverified.

- Source `f451ee9` (the rebased PR head). Debug APK SHA256
  `db3b8f6db72cc2c8389c896b29e52256a653360e359b3cecde3d67c1d8a91eeb`.
- AVD `flint-test`, serial `emulator-5554`, API35 Google APIs ARM64, 1080x2400. Booted by
  `make android-run` and claimed in the worktree ledger.
- Before input, the configuration read font1.0, density420 with no override,
  `animator_duration_scale=null` and `enabled_accessibility_services=null`. Afterwards it was
  restored to the same four values and read again. Reduced motion was not exercised.
- All taps were deliberate and limited to the You tab, the troubleshooting card and (for the Help
  evidence of #181) the Help link and an ANR "Wait" button. Scrolling used the side gutter so no
  gesture could start on a button.

| Prefix | Configuration | What the captures show |
| --- | --- | --- |
| `f1-` | font1.0, density420 | `you-accessibility-row` and `you-rows-oem-collapsed`: permission rows with the visual ON/OFF pill. In AX the title and role appear, and no `ON`/`OFF` text node exists because the pill is cleared from semantics. The OEM card shows `Show`. `you-oem-expanded`: after one tap on the card, `Hide` and the numbered steps appear. A second tap restored `Show` and removed the steps (verified in AX). |
| `f2-` | font2.0, density420 | `you-rows`: rows wrap without clipping. `you-oem-expanded`: the expanded card at 2x. Collapsing again was verified in AX. |
| `n320-` | font2.0, density540 (320dp) | `you-rows`, `you-oem-expanded`: the same at the narrow width. Collapsing again was verified in AX. |

Not observable here:
- `uiautomator` dumps do not include Compose `stateDescription` or merged-node boundaries. The
  merged permission header, its On/Off state and the Expanded/Collapsed state are covered by
  `SettingsSemanticsTest` (Robolectric), not by these dumps.
- Rules "Active now" and the loading announcement need an active saved rule or a transient
  loading state. This session saved no rule and did not attempt to catch the transient state.
