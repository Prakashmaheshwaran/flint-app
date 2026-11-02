# Android How Flint works (Help) — emulator evidence, 2026-10-28

UI evidence only; no enforcement claim. AVD `flint-test` (`emulator-5554`, Android API 35
Google APIs ARM64), 1080 × 2400 at default density; installed with `make android-run` (debug)
from branch `v1/h/android-help` at `6f9ccad`. Navigation by Orca taps/swipes,
each verified by a fresh AX read. Images downscaled to 540 px wide JPEG.

| File | Observation |
| --- | --- |
| `you-help-entry-font-1.jpg` | You, scrolled: the HELP section and "How Flint works" entry above Legal. |
| `help-font-1.jpg` | Font 1.0: Back control, heading, intro, "What Flint can't prevent" card (access, calls/Home/Settings, uninstall, clock). |
| `help-font-2.jpg` | Font 2.0: text wraps; the Help route survived the activity recreation caused by the font change. |
| `help-font-2-scrolled.jpg` | Font 2.0 after one upward swipe: content scrolls under the fixed Back control. |

Font scale was restored to 1.0 and the emulator shut down afterwards. Not covered: TalkBack
heading navigation on hardware, 320 dp width.
