# Android Path B emulator verification — 2026-07-08

Environment:
- AVD: `flint-test`
- Device reported by adb: `sdk_gphone64_arm64`, API 35
- Detection path under test: Path B fallback (`UsageStatsForegroundService` + overlay), with
  `FlintAccessibilityService` forced off.

Commands run from the repo root:

```bash
make android-test
make android
OUT_DIR=docs/verification/android-pathb-2026-07-08 scripts/android-pathb-verify.sh
```

Result: all three commands passed (the two make gates ran clean locally; only the verify
script's output is captured in `run.txt`).

Covered:
- Usage access allowed + AccessibilityService off starts `UsageStatsForegroundService` as a
  foreground service.
- A debug-seeded legacy blocklist entry for Clock is blocked by the Path B overlay.
- The block screen's `Open Flint` action stands the overlay down on Flint's own UI.
- Relaunching the blocked Clock app re-shields it.
- The Easy-tier `Take a break` action grants an exemption and drops the shield.
- A debug-seeded `0` minute daily Time Limit for Contacts blocks through Path B and renders
  the `TIME LIMIT` block screen.

Evidence:
- `run.txt` — verify-script transcript.
- `01-pathb-service.txt` — service dump proving foreground-service state.
- `02-clock-blocked.png` / `02-clock-blocked-windows.txt` — blocklist overlay.
- `03-open-flint-standdown.png` / `03-open-flint-standdown-windows.txt` — self stand-down.
- `04-clock-reshielded.png` / `04-clock-reshielded-windows.txt` — re-shield.
- `05-break-granted.png` / `05-break-granted-windows.txt` — Easy break exemption.
- `05-break-granted-at.txt` — UTC timestamp of the break grant.
- `06-contacts-time-limit.png` / `06-contacts-time-limit-windows.txt` — daily Time Limit block.
- `ui.xml` — final uiautomator dump backing the `TIME LIMIT` block-screen assertion.

Not covered by this run:
- Path A-only uninstall-guard shielding, because Path B cannot read Settings/uninstaller window
  text and the run deliberately kept AccessibilityService off.
- Open Limits, sleep windows, boot re-arm, the time-change-guard broadcast path, OEM kill
  behavior, and real-device resilience.
