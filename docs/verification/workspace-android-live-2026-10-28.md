# Android live control verification — 2026-10-28

Task A, branch `Prakashmaheshwaran/ws-android`, based on `19baf90`.
Android 35 Google APIs ARM64 revision 9 is installed. A complete cold
`make android-run AVD=flint-test` passed in **50.039 s**. Flint foreground,
one Today → Rules tap, and one Back → Today transition were verified with real
Orca accessibility replies and agreeing screenshots. Cleanup shut down the
owned emulator; SDK adb is empty and the ledger has no claims.

## Evidence and authorization

All links below are relative to
[the evidence directory](workspace-android-live-2026-10-28-evidence/).
[commands.jsonl](workspace-android-live-2026-10-28-evidence/commands.jsonl)
records exact argv, cwd, UTC start, wall time, exit status and raw output paths.
Nested Orca calls were transparently recorded using `ORCA_CLI_COMMAND`, delegating
to `/Applications/Orca.app/Contents/Resources/bin/orca`. Individual logs were
capped at 2 MiB; no capture was truncated. Four PNGs were captured, each below
4 MiB. No credentials or account data were captured.

The task authorized bootstrap, exactly one image package, and only `flint-test`
device control. The first install stopped without acceptance at the new
`android-sdk-arm-dbt-license` prompt: sdkmanager misleadingly returned exit 0
after skipping the package. The coordinator then relayed explicit user approval
to accept exactly that license. One `y` was sent to that prompt during the retry;
no other license was accepted, and neither `--licenses` nor `yes` piping was used.
The coordinator also approved Android parser edits in `logs-android.py` and a
read-only SDK screenshot fallback because this Orca CLI lacks a screenshot verb.

## SDK and fresh-shell setup

Exact setup/install commands:

```sh
bash scripts/orca/setup.sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 /opt/homebrew/bin/sdkmanager --sdk_root="$HOME/Library/Android/sdk" --list
JAVA_HOME=/opt/homebrew/opt/openjdk@21 /opt/homebrew/bin/sdkmanager --sdk_root="$HOME/Library/Android/sdk" --install 'system-images;android-35;google_apis;arm64-v8a'
JAVA_HOME=/opt/homebrew/opt/openjdk@21 /opt/homebrew/bin/sdkmanager --sdk_root="$HOME/Library/Android/sdk" --list_installed
/bin/zsh -lc 'make env'
/bin/zsh -lc 'make android-run AVD=flint-test'
```

The package resolved as revision 9 before installation. Free space was
**35.959 GiB** before the install, **34.473 GiB** immediately afterwards, and
**33.120 GiB** after native gates and retained build outputs. The approved install
took **42.902 s**. `system.img` and `package.xml` exist beneath
`~/Library/Android/sdk/system-images/android-35/google_apis/arm64-v8a/`, and final
`--list_installed` lists exactly that image package. No Play Store image,
cmdline-tools, platform update or emulator update was installed.

See [filtered before listing](workspace-android-live-2026-10-28-evidence/sdk-list-before.txt),
[install result](workspace-android-live-2026-10-28-evidence/sdk-install-approved-result.json),
[install log](workspace-android-live-2026-10-28-evidence/sdk-install-approved.log),
[disk before](workspace-android-live-2026-10-28-evidence/disk-sdk-before.json),
[disk after install](workspace-android-live-2026-10-28-evidence/disk-sdk-after-install.json),
and [final disk](workspace-android-live-2026-10-28-evidence/disk-final.json).
The command ledger identifies complete installed-package listings before the
first device run and after the final native gate. sdkmanager emitted an SDK XML
version warning; filesystem and installed-package verification still passed.

## Real Orca JSON findings and fixes

The provisional Android fixtures in `scripts/agent/validation-report.md` are
superseded by these captures and the normalized
[real-response fixtures](../../scripts/agent/fixtures/orca-android-1.4.241/README.md).

- Device inventory is `result: [...]`, with `backend: "android"`, `id`, `name`,
  `state`, `detail`, and `isAvailable`. It is not `result.devices` with `platform`.
  Shutdown `flint-test` has id `flint-test`; booted identity is `emulator-5554`,
  name `flint-test`. The captured-fixture regression failed before the parser fix.
- Fresh attach returns `result.attached=true` and `info.deviceUdid`, `streamUrl`,
  `wsUrl`, `streamCodec="h264"`, and `backend="android"`. It has no `helperPid`.
  A reused stream omits backend as well. That variant is accepted only after
  exact serial matching, fresh Android backend inventory and SDK AVD/boot checks.
  The existing before/after build/install boot-incarnation checks remain in force.
- adb is available before Android finishes booting. The first cold attempt
  reached attach too early and returned `runtime_error: scrcpy server exited
  before the video stream started`. Its own verified boot was rolled back.
  `verify_android` now requires `sys.boot_completed=1`; two subsequent cold
  attach operations succeeded. Early framework/display readiness is the inferred
  cause, supported by the successful readiness-gated runs.
- Orca's activity-less launch failed through monkey with `SYS_KEYS has no
  physical keys but with factor 2.0%`. `android-run.sh` now launches Flint's
  declared `.MainActivity` explicitly; both reused and final cold runs passed.
- `exec` puts the pidof output directly in a string, e.g. `result: "4660\n"`.
  The provisional object lookup crashed with `AttributeError`. It now validates
  the actual type and preserves SDK fallback for unrecognized/failing responses.
- `logcat` returns an array of `timestamp`, `level`, `tag`, and `message` records,
  plus message-only buffer headers. No PID or raw threadtime is present. The
  script refuses to label these global entries as Flint logs and falls back to
  SDK adb with an explicit serial and `--pid`. Live `make logs-android` passed
  in **1.959 s**, showing Flint PID 4660 logs. Its regression proves the PID-less
  fixture is rejected and the scoped SDK command is used.

The ledger/cleanup parsers needed no change: Android provides no helper PID,
so `helper=null` correctly grants no helper ownership. Cleanup retained its
boot-token, host PID/start/group, exact AVD and peer-claim safeguards. The only
shared runner change is surfacing stdout diagnostics when a failing CLI writes
no stderr; iOS boot/attach/launch behavior was not changed.

## UI postconditions

The AVD retained an existing Flint installation/setup, so the safe test used the
Rules tab rather than an onboarding button. The app displayed its existing
“backup path” state; this is not proof of enforcement or a permission grant by
this run. No system permission prompt was entered and no permission command was
issued. No app data or snapshots were erased or edited.

All screenshots are 1080 × 2400. The pre-tap AX tree showed Flint and an enabled,
clickable Rules control at `{left:301, top:2127, right:539, bottom:2295}`. Its
center normalizes to `(0.388889, 0.92125)`. Exactly one tap was sent:

```sh
orca emulator tap 0.388889 0.92125 --device emulator-5554 --worktree "path:$PWD" --json
orca emulator button back --device emulator-5554 --worktree "path:$PWD" --json
orca emulator ax --device emulator-5554 --worktree "path:$PWD" --json
make logs-android AVD=flint-test LOG_LINES=500
```

The tap completed in **1.721 s**; the following AX completed in **3.822 s** and
showed Rules / Quick block list / Nothing blocked yet. Back completed in
**0.659 s**; following AX completed in **6.652 s**, showing Today again.
The final cold run independently showed Flint foreground in AX and pixels.

Images: [before](workspace-android-live-2026-10-28-evidence/ui-before.png),
[after one tap](workspace-android-live-2026-10-28-evidence/ui-after-tap.png),
[after Back](workspace-android-live-2026-10-28-evidence/ui-after-back.png),
[final cold launch](workspace-android-live-2026-10-28-evidence/ui-final-cold.png).
[Verified postconditions](workspace-android-live-2026-10-28-evidence/verified-postconditions.json)
links each full AX reply, records target bounds and verifies the expected text,
Flint package identity, image dimensions and restored final device inventory.

Because Orca 1.4.241 has no emulator screenshot command, each PNG used the
coordinator-approved read-only command below, after verifying the serial's AVD
name is exactly `flint-test`. No raw adb UI input was used.

```sh
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 emu avd name
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 exec-out screencap -p > <evidence>.png
```

## Cleanup and gates

The final owned boot had host PID/group **84120**, start
`Wed Oct 28 01:12:01 2026`, and a recorded guest boot ID. The ledger recorded
`booted=true`, `created=false`, exact AVD `flint-test`, and `helper=null`.
`make clean-devices` completed in **1.728 s** and issued exactly the verified
serial's `adb ... emu kill`; it did not claim helper ownership or kill a helper.
Shutdown acknowledgement can precede adb disappearance: the first cleanup's
immediate inventory briefly still listed the serial, then the settled check was
empty. Final SDK adb is empty and the ledger is `[]`; fresh global inventory
equals the initial inventory, including untouched `flint-opal-reference` and all
iOS simulators remaining shut down. The AVD occupies **3,049,088 KiB** on disk
(approximately **2.908 GiB**); it was preserved.

See [final adb proof](workspace-android-live-2026-10-28-evidence/adb-final.json)
and the command-ledger entries for `cleanup-final`, `cleanup-final-claims`,
the last Orca devices call, and `final-avd-size`.

- `make verify-android`: **PASS, 6.265 s**; debug/release builds, local-first
  release APK verification and all Gradle unit-test tasks completed successfully
  (with normal build/test cache reuse).
- `PYTHONDONTWRITEBYTECODE=1 make selftest`: **PASS, 262.674 s**, including
  49 agent command integration tests and the existing iOS safety regressions.
- Focused regressions passed for actual backend inventory, unknown inventory,
  incomplete boot, captured attach failure diagnostics, reused backend omission,
  real-ledger Android claims and PID-less Orca log fallback. Existing iOS fixture
  behavior remains covered by the full device-free gate.

## Orca findings for upstream and remaining limits

1. **Activity-less Android launch can fail through monkey.** On the attached
   booted AVD with Flint installed, `orca emulator launch com.flint.peakfocus
   --device emulator-5554 --json` returned `emulator_error` with the SYS_KEYS
   message above. Adding `--activity .MainActivity` succeeds. Preserve the
   captured `launch-monkey-error.json` fixture and raw command response.
2. **Android logcat JSON drops process identity.** `orca emulator logcat
   --lines 500 --device emulator-5554 --json` returned a PID-less array (541
   parsed records, including headers). Request PID/TID or raw threadtime fields
   upstream before relying on Orca for process-scoped logs. SDK `--pid` is the
   verified fallback.
3. **CLI capability gaps:** `orca emulator screenshot --help` and `orca emulator
   key --help` return unknown command. `button back` works; the approved SDK
   screenshot fallback supplies pixels. No native Orca screenshot JSON exists
   to capture. `emulator list` returned `running=false` during a valid Android
   stream, so it is not proof of Android activity or helper ownership.
4. **Early attach:** the scrcpy startup failure is captured; waiting for Android
   boot completion resolved the observed path. Orca's internal readiness behavior
   was not modified or diagnosed beyond those observations.

No upstream issue was posted. Physical-device behavior, permission grants,
blocking enforcement, a fresh onboarding flow, Play Store images and iOS live
verification remain unproven and outside this task. Build outputs are retained.
