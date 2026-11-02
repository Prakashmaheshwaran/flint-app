# Android Help website coverage (PR #181) — emulator evidence, 2026-10-29

Presentation evidence only. No Accessibility grant, permission change, or saved rule.
Physical TalkBack/Switch Access remains unverified.

- Source `df7f8a7` (the PR head at capture). Debug APK SHA256
  `3619fe1f6abc336e7d9c5ef4b8c9c06b2f86a82f53dbe55a5e9d640edd2fcee8`. Installed with
  `orca emulator install --reinstall` over the #179 build, then launched `.MainActivity`.
- AVD `flint-test`, serial `emulator-5554`, API35 Google APIs ARM64, 1080x2400.
- Before input, the configuration read font1.0, density420 with no override,
  `animator_duration_scale=null` and `enabled_accessibility_services=null`. It was restored and
  read again afterwards with the same values.
- With no website rule saved and Accessibility off, the shared projection's observation is
  `No website block is active now.` Other observations are covered by `HelpCopyTest` and
  `BlockingHealthUiTest`.

| Capture | Configuration | What it shows |
| --- | --- | --- |
| `f1-help-websites` | font1.0, density420 | The Websites section: heading `Website coverage`, the prerequisite sentence, then the projection (observation, known-browser list, limitations), followed by the next section. |
| `f2-help-websites-top`, `-projection`, `-end` | font2.0, density420 | The same card wrapping at 2x, from heading to the end of the projection. Help stayed open across the font change. |
| `n320-help-websites-projection`, `-end` | font2.0, density540 (320dp) | The projection and the end of the card at the narrow width. At this width the heading and prerequisite were confirmed in AX only; their screenshot frame is the ANR frame below. |

**ANR during the 320dp pass.** About 30 seconds after switching to density540, Android showed
"Flint isn't responding". `n320-anr-dialog-over-help` records it; **Wait** was chosen and Help
resumed normally. The system log reason was `executing service
com.flint.peakfocus/.blocking.usagestats.UsageStatsForegroundService, waited 38077ms`. That is the
usage-stats fallback service, not the Help screen. Host memory was 34% free at the time, with
other lanes' builds and simulators running. No cause is claimed. The ANR was reported to the
coordinator for the service owner.
