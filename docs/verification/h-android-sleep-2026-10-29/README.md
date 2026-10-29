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
  This is JVM layout evidence; final emulator screenshots of that correction remain pending.

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

## Recovery boundary

Orca calls began timing out; a subsequent status reported the runtime disconnected, then
unavailable. Settings restoration through Orca and the SDK timed out before cleanup, so
restoration is unverified. Claim-ledger cleanup verified and shut down this session's own
boot, released its claim and left an empty ledger. It preserved the unproven helper rather
than inferring helper ownership. The shared device slot was released to K-I.

Before another Android evidence session, verify and restore font1.0, density420 (no override)
and an absent animator duration scale, then record the final configuration again.
