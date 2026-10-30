# Android Sleep presentation — 2026-10-29

Presentation evidence only. No Accessibility grant, saved protection rule or enforcement
claim is part of this pass. Physical TalkBack/Switch Access remains unverified.

## Source, checks and independent review

- PR #172 targets `integration/v1`. The independent reviewer checked every new Sleep
  sentence against merged `SleepRules`, `DataStoreEditorCommitments`, `CommitmentPolicy`,
  the live pass authority and ADR-012. See `independent-review.txt`.
- That review corrected the migrated-active off-rights caveat and removed inherited rolling
  pass wording that was not yet live on Android. Sleep defaults, tier mapping, validation
  and editor save gates are unchanged. `SleepRules.kt` has no diff.
- Source `2a8fe242de07ecef261c4f42cde906fea97835a8` passed Android CI run `40798165960`.
- The initial 320dp/2x pass found colliding day-strip labels. Source `f12bc2f` replaces the
  strip with stacked labels when narrow or using large text. The debug APK builds and
  190 feature JVM tests pass locally, including `SleepLargeTextTest`: a 320dp/2x composition
  checks full weekday checkbox semantics, a 48dp target and non-overlapping Bed/Wake labels.
  The final emulator pass below shows that correction on a device.

## Initial emulator capture

All files under `initial/` are from the pre-correction source `2a8fe24` and are retained to
show what was actually observed. They are not final verification of the later layout fix.

- AVD `flint-test`, serial `emulator-5556`, Android API35 Google APIs ARM64.
- Physical display 1080x2400; baseline density420 (about 411dp wide), font1.0.
- Narrow pass: density540 (320dp wide), font2.0. Animator duration scale was set to0 to
  exercise reduced motion; its original value was absent.
- Debug APK SHA256: `65ad72ca95b43f15ce46258040cd4cf65d2f82ac37a109a37bf81a5d026d5652`.
- AX files expose full weekday `contentDesc` values. Orca taps/swipes were used for input;
  screenshots and configuration reads used the SDK with the verified explicit serial.
- Accessibility was disabled (`enabled_accessibility_services=null`). Usage-access/overlay
  grants already existed on the AVD; this session granted no permission and saved no rule.

| Capture | Observation |
| --- | --- |
| `editor-font-1` | Editor header, wrapped checked day choices and stacked time fields. |
| `editor-font-1-tier` | Time fields and radio assist choices after scrolling; tier choices are farther below. |
| `editor-320-font-2-top` / `days` | Large text wraps, days have visible checks and full names in AX. |
| `editor-320-font-2-window` | Bed/Wake fields fit; the strip labels collide. This prompted the correction above. |
| `editor-320-font-2-assist` | Pixel capture attempted; AX returned `runtime_timeout`. Do not treat it as a verified transition. |

## Recovery boundary (first session)

Orca calls began timing out; a subsequent status reported the runtime disconnected, then
unavailable. Settings restoration through Orca and the SDK timed out before cleanup, so
restoration was unverified at the time. Claim-ledger cleanup shut down that session's own boot
and preserved the unproven helper. The final session below verified the configuration first.

## Final emulator capture (`final/`)

- Source: PR head after rebasing onto `integration/v1` `b8959ea`; the APK was built from
  `4acec3b` (the same Sleep code; this commit only adds evidence). Debug APK SHA256
  `1269f3bcbc854a7a3bb6f9cedbb7ffff9f526d7fd3e655a0113a7ffa7400739a`.
- AVD `flint-test`, serial `emulator-5554`, API35 Google APIs ARM64, 1080x2400. The session was
  booted by `make android-run` and claimed in the worktree ledger.
- Before input, the configuration was read as font1.0, density420 with no override,
  `animator_duration_scale=null` and `enabled_accessibility_services=null`. Afterwards the
  density override was reset and font1.0 restored, and the same four values were read again
  with identical results. Reduced motion was not exercised in this pass.
- Orca taps/swipes for input; pixels via SDK `screencap` after checking `emu avd name`.

| Prefix | Configuration | What the captures show |
| --- | --- | --- |
| `f1-` | font1.0, density420 (~411dp) | `editor-strip-assist`: the strip renders with `Bed 22:30` and `Wake 07:00` at opposite ends; assist radios with the Easy/Harder starting-tier notes. `editor-tier-easy`, `-harder-draft`, `-hardcore-draft`: `Tier:` follows the selected draft tier and the off explanation switches to the matching sentence. `editor-footer`: Save/Cancel fully above the floating navigation bar. |
| `f2-` | font2.0, density420 | `editor-window-stacked`: the strip is replaced by stacked `Bed 22:30` / `Wake 07:00` lines with no overlap. `editor-assist`, `editor-tier-off`, `editor-footer`: assist notes, tier, off and migrated-rights text, and a reachable footer at the scroll end. |
| `n320-` | font2.0, density540 (320dp) | Same stacked summary, assist/tier, off text and footer at the narrow width. The footer frame was taken at the end of the scroll range, with Save/Cancel above the navigation bar. |

The `-draft` tier frames change only the unsaved editor draft. Every visit ended with Cancel
or Back, and the Rules Sleep card still read `Not set up` at the end.

**Input incident, recorded for completeness:** at 320dp, a scroll gesture that started on the
`Save sleep mode` button at the end of the scroll range was delivered as a press. It opened
lane C's `Save Sleep` confirmation dialog. The dialog's Cancel was tapped and Confirm never was.
The editor stayed open, the Sleep card still read `Not set up`, and no rule was saved. That
dialog frame is not part of the evidence set.

No Accessibility grant, permission change or saved protection rule was used. Physical
TalkBack/Switch Access and enforcement remain unverified.
