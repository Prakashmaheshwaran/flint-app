# Android focus history verification — 2026-10-28

## Verified source and local checks

The initial UI APK was built from `a740bd7` on `v1/f/android-history-ui`, installed
and launched with `flint-heavy android -- make android-run AVD=flint-test` while
holding the shared `flint-heavy device` slot. Its SHA-256 was
`f653494ab1fcac20ffff91308477bd04426d5660ef1417061a75585fcf1ddb85`.
The exact device was `emulator-5554`, AVD `flint-test`, Android 35 Google APIs
ARM64. Both `adb emu avd name` and `sys.boot_completed=1` were checked. No
permission was granted and no app data or rules were injected for this run.

The store subsequently received independent-review fixes and the shared
FH-20/FH-21 contract amendments. At store head `6289340`, the complete local
Android gate passed under `flint-heavy`, including debug/release builds, release
APK/AAB local-first exclusions, and 42 history tests in each variant. These
later changes do not turn the earlier UI screenshots into final-head evidence.

## Observed UI

- `01-empty-today.png`: Today reads "No focus sessions yet", "Focused today",
  and retains "Your next session · 30m". Pixels and the fresh Orca AX tree agreed.
- `02-empty-insights.png`: Focus sessions is separate from screen usage, shows
  the counting definition, empty state, true zero for today, and no bars for the
  six dates before history began. Pixels and the Orca AX tree agreed.

The Start focus sheet opened. A completed Timer, an ended-early Timer, Complete
screen, nonempty Today/Insights totals, and clear-history confirmation were
**not** verified. No session was started. Screen-usage values visible below the
new section are the emulator's existing usage data, never converted to focus.

## Driver failure and bounded recovery

During approximately 19:29–19:43 UTC, Orca 1.4.243 intermittently stalled on:

```text
orca emulator ax --device emulator-5554 --json
orca emulator tap 0.116 0.324 --device emulator-5554 --json
```

The CLI returned:

```json
{"id":"local","ok":false,"error":{"code":"runtime_timeout","message":"Timed out waiting for the Orca runtime to respond."},"_meta":{"runtimeId":null}}
```

The runtime remained reachable and ready (`orca status`), and SDK read-only
`adb get-state` and `getprop sys.boot_completed` remained healthy. Some
`exec-out screencap -p` calls also stalled. Restarting only Flint did not resolve
the problem. No raw adb UI input was used and no sensitive permission prompt was
crossed.

The lane F lead authorized at most two recovery attempts. The first fresh
inventory plus `orca emulator attach emulator-5554 --json` returned the exact
serial; its AX read failed with `emulator_error: uiautomator dump failed: unknown
error` (runtime `8f38263a-4ff0-4ed2-9d46-3f93ba87f5d9`). The second authorized
`orca emulator kill --device emulator-5554` and exact reattach returned the same
serial and `backend: android`; read-only screen capture remained stalled. The
run was parked, rather than repeating unverified inputs. A final AX request was
still pending when ownership-safe cleanup started, so its result is not an
independent recovery verdict.

At 19:43 UTC, `make clean-devices` shut down the emulator booted by this
invocation, preserving the helper lacking PID/start provenance. The task's
device semaphore holder and its sleep child were stopped. The real-session
evidence remains a follow-up for a healthy Orca driver or lane I's emulator
integration workflow; OEM enforcement/Doze and real-hardware notification
delivery are also unverified.
