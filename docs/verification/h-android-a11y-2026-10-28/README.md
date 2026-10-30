# Android Welcome and picker accessibility evidence

Captured 2026-10-28 on AVD `flint-test`, serial `emulator-5554`, Android API 35
(Google APIs ARM64), portrait 1080 × 2400. Density was temporarily set to 540 dpi,
giving a **320 dp wide viewport**. PNGs are proportionally downsampled to 540 × 1200;
each is below 300 KB. No content was cropped or retouched.

The installed debug APK was built at `2d2803d7c9bcc952b1c423c96e90378c9a5dbb88`
before the screen branch was rebased onto the merged kit. `MainActivity.kt`,
`AppPickerScreen.kt`, picker strings, and the entire shared kit are byte-identical
at rebased commit `2a9326fc64c5f31a4d69918fc0ff7fc82af18527` (verified with `git diff`).
Both `make android-run` invocations used the Android heavy semaphore and the device
session held `flint-heavy device -- sleep 2700`.

The final picker string changes the captured clause “Flint never blocks them” to
“Rules never block them.” This scopes the note to the verified rule safety floor
while lane C's separate Settings/uninstall-guard removal (#105) is still pending.
The lane H lead requested this copy correction without recapturing the screenshots.

## Captures and observations

- `welcome-font-1.png`: font scale 1.0; mark/title/tagline, claims, Begin and footer
  retain their tall-screen composition. The Flint title has heading semantics in source.
- `welcome-font-2.png`: font scale 2.0; tagline, claims and footer wrap, and Begin
  remains visible. This tall portrait viewport fits the content; shorter viewports
  can scroll through the viewport-minimum column.
- `picker-font-1.png`: font scale 1.0, Rules → unsaved New block rule → Choose apps;
  header/exclusion note, search, app rows and footer are visible. No rule was saved.
- `picker-font-2-top.png`: font scale 2.0; full-size header/note occupy the scrollable
  list region, with Cancel and Add still reachable in the separate wrapping footer.
- `picker-font-2-scrolled.png`: font scale 2.0 after an Orca upward swipe; the header
  moves and Search apps / the count come into view while footer controls remain fixed.
  App rows farther down were not captured at 2.0; single-select/TalkBack interaction
  was not exercised on this emulator.

Fresh Orca AX samples agreed with the captured text and bounds. Android activity
recreation on font changes discards the unsaved picker route, so the route was
reopened after switching to 2.0 rather than treating the Rules screen as picker evidence.
The first AX request timed out; a later fresh request succeeded after ambient motion
was stopped with animator scale 0. No tap was repeated to infer success.

## Scope and remaining checks

No Accessibility permission was granted. No block rule was saved and no block-screen
action, break, wait or pass was invoked. Block-screen screenshots are unavailable:
this base exposes Compose `@Preview` functions but no launchable debug preview route,
and lane C owns pending BlockScreen changes. Its layout/motion/hold semantics are
reserved for a separate PR after C3 phase 2, per the lane H lead.

TalkBack on hardware, physical-device accessibility, landscape and shorter viewport
screenshots remain unverified. The picker ViewModel still has Loading/Ready only;
lane C owns a distinct enumeration-error state. Existing safety exclusions were
verified at base `9218391`, `InstalledApps.kt:44`, before adding the specific
Phone/emergency calling/Home/Settings explanation.

## Restoration and cleanup

The single onboarding XML was backed up to reach Welcome and restored exactly.
The original font scale (1.0), density (420 dpi, no override) and absent animator-scale
setting were restored and read back. `make clean-devices` shut down this invocation's
proven boot of `emulator-5554`, preserved the helper without PID/start provenance,
and the device semaphore was released. No other lane's devices were shut down.
