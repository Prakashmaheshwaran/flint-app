# Android Help: Strictness, Emergency Pass and clock (PR #203) — emulator evidence, 2026-10-29

Presentation evidence only. No Accessibility grant, permission change, saved rule, break, credit or
pass was used; the pass and break behavior described in Help is source- and JVM-verified by lane C,
not demonstrated here. Physical TalkBack/Switch Access remains unverified.

- Source `15c163c` (PR head rebased onto integration/v1 `4f52f12`, after #178). Debug APK SHA256
  `13ee64e203e6dd242f973910d623bfddd469f2662fc44d7756910b47fda55865`.
- AVD `flint-test`, serial `emulator-5554`, API35 Google APIs ARM64, 1080x2400. Booted by
  `make android-run` and claimed in the worktree ledger. The session ran 02:18–02:27Z.
- Before input, the configuration read font1.0, density420 with no override,
  `animator_duration_scale=null` and `enabled_accessibility_services=null`. Afterwards it was
  restored and read again with the same values. `make clean-devices` shut down this session's boot.
- Help was opened from You → How Flint works. Help has no controls in its scrolling content, so
  scroll gestures could not trigger actions.

| Capture | Configuration | What it shows |
| --- | --- | --- |
| `f1-help-strictness` | font1.0, density420 | Strictness heading with the Easy, Harder and Hardcore facts. |
| `f1-help-hardcore-timers-legacy` | font1.0 | Hardcore, Timers, and "Protection from before an update". |
| `f1-help-emergency-pass` | font1.0 | Emergency Pass: "One pass, seven days apart", During recovery, Where to find it. |
| `f1-help-clock` | font1.0 | The revised "Changing the clock" fact. |
| `f2-help-harder`, `f2-help-recovery`, `f2-help-clock` | font2.0, density420 | The longest facts wrap without clipping at 2x; Help stayed open across the font change. |
| `n320-help-harder`, `n320-help-timers-legacy`, `n320-help-pass` | font2.0, density540 (320dp) | The same facts at the narrow width. |

No ANR or other system dialog appeared during this session.
