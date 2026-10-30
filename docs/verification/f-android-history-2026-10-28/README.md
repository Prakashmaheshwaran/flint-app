# Android focus history verification — 2026-10-28

## Hosted real Timer evidence

[E2E run 40772730952](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40772730952)
passed **5 tests, 0 failures, 0 errors, 0 skips**. The history case took
340.193 seconds; the reported suite time was 414.442 seconds. This is emulator
evidence for actual Timer sessions and persisted history/UI, with the limits below.

- PR head: `75f04763fe320011ed5edb2f7bb6f25711b0edcf`.
- Checked-out PR merge: `bdb9e988067bfe85f73a5d84d055e2c02f172562`.
- Artifact: `android-e2e-api36`, id `12854586181` (original archive 8,061,445 bytes).
- GitHub-hosted Ubuntu/KVM emulator; workflow configuration was API 36,
  Google APIs, x86_64, Pixel 2 profile, 2048 MiB RAM, animations disabled.
- Exact serial: `emulator-5554`. Harness boot/run correlation IDs are
  intentionally omitted from committed evidence.

The workflow built the app/test APKs before boot, then ran
`FLINT_E2E_DISPOSABLE=1 AVD=emulator-5554 bash scripts/android-e2e.sh`.
The guarded fixture reset app data only on that disposable emulator and exact
boot. It ran the four existing accessibility smoke cases plus
`FocusHistoryTest.realCompletionAndEarlyEndSurviveRestartAndRenderHistory`.
The history case used these real UI steps:

1. Captured empty Today and Insights; selected the test target through the app picker.
2. Selected the shortest five-minute Timer, anchored monotonic elapsed time
   immediately before the single Begin action, and waited for natural completion.
   The test required at least 295 seconds elapsed and a durable completed record
   exactly at the five-minute planned deadline.
3. Verified Complete after force-stop/relaunch, pressed Done, and verified the
   persisted dismissal after another force-stop/relaunch.
4. Started another five-minute Timer through the UI and used one physical
   2.5-second hold on Hold to leave. Its first terminal is `hold_to_end`, 4,999 ms
   after start and before its planned end.
5. Relaunched again and verified Today plus Insights week/day totals and outcomes.

The retained read-only log contains 300,000 ms for the completed session and
4,999 ms for the early end: 304,999 ms total, displayed as **5m**. There are two
raw early-end observations for the second session: the explicit transaction
timestamp precedes the later observer's unknown-route timestamp by 64 ms.
Contract first-terminal ordering produces **1 completed · 1 ended early**.
No rules, history or clocks were injected/changed for this case.

Original, unedited PNGs and matching AX XML are retained here:

- `10-ci-empty-today`: empty Today with next-session content.
- `11-ci-empty-insights`: empty reporting; earlier dates have no bars.
- `12-ci-complete`: session name, 5m focused, Today 5m; absent optional counters omitted.
- `13-ci-complete-after-restart`: durable Complete after process restart.
- `14-ci-today-completed`: Today after Done.
- `15-ci-today-completed-and-early`: Today after both outcomes.
- `16-ci-insights-week`: 5m, one completion, one early end and one-day streak.
- `17-ci-insights-day`: the selected current-day totals after restart.

Pixels and AX were inspected for the empty, Complete, final Today and both
Insights states. `hosted-history.jsonl`, `hosted-junit.xml` and
`hosted-provenance.json` retain the source records, result and file hashes.
These captures belong to the exact run above; later ancestry/append-validation
rebases do not change their provenance.

The history case did not enable Accessibility or Usage Access; Today honestly
shows Blocking is off. The four separate smoke cases use the fixture's owned,
exact-boot accessibility grant/cleanup. This history case does not prove
intercepted-open/break measurements, history-clear UI, OEM enforcement,
Doze/alarm timing or real-hardware notification delivery. No local device was
reacquired for the hosted run; the hosted runner terminated its disposable emulator.

## Earlier manual run: source and local checks

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

## Earlier manual run: observed UI

- `01-empty-today.png`: Today reads "No focus sessions yet", "Focused today",
  and retains "Your next session · 30m". Pixels and the fresh Orca AX tree agreed.
- `02-empty-insights.png`: Focus sessions is separate from screen usage, shows
  the counting definition, empty state, true zero for today, and no bars for the
  six dates before history began. Pixels and the Orca AX tree agreed.

The Start focus sheet opened. A completed Timer, an ended-early Timer, Complete
screen, nonempty Today/Insights totals, and clear-history confirmation were
**not** verified. No session was started. Screen-usage values visible below the
new section are the emulator's existing usage data, never converted to focus.

## Earlier manual run: driver failure and bounded recovery

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
device semaphore holder and its sleep child were stopped. At that point the
real-session evidence was still pending. The hosted run above subsequently
verified it; OEM enforcement/Doze and real-hardware notification delivery remain
unverified.

The lead authorized the later instrumentation E2E using lane I's guarded
disposable-emulator harness. That follow-up is the successful hosted run above;
`01`/`02` remain historical empty-state evidence from the earlier APK only.
