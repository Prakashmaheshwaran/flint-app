# Workspace setup integration — 2026-10-27

Task D integrates the workspace environment, cleanup, and agent command branches
on `Prakashmaheshwaran/workspace-setup-automation`, starting at `468abeb`.
Measurements below are UTC on October 28 (October 27 in America/New_York).
All required gates passed, with one successful iOS retry after a stalled first
test-host launch. The final tree has no build artifacts or booted simulators.
The [command ledger](workspace-setup-integration-evidence-2026-10-27.jsonl)
retains exact starts, durations, exits, recovery commands, and cleanup output.

## History and conflict resolution

- A: `a9525da` merges `da72705` and `39da125` with `--no-ff`.
- B: `48b3e68` merges `f1e0793` and `449ebc9` with `--no-ff`.
- C: `bfa78e9` merges `845b3ed` and `17076a4` with `--no-ff`.
- `607cd30` reconciles Android serial selectors and records emulator findings.
  All six lead commits are ancestors; all three source branches retain their tips.

Only `Makefile` conflicted. B's merge combined A's Make 3.81-compatible `SHELL`
wrapper, exports, aliases, and prefix-free recipes with B's single
`IOS_DERIVED_DATA` definition and explicit build/test `-derivedDataPath`.
C's merge retained those definitions, added its selectors and commands, and
combined every target into one `.PHONY` list. All 26 targets have `##` help text.
AGENTS.md and selftest additions merged automatically without dropped content.

The guarded `orca.yaml` archive command now finds B's archive script. Git ignores
agent scratch while tracking `.claude/skills/`, `.claude/settings.json`, and
`.codex/hooks.json`. C's scripts source A's resolver; its Android command resolves
serial selectors to the actual AVD name for B's final ledger interface. Claims
inherit the ledger's Orca/Codex/Claude identity, attest `--booted` only on cold
starts, and never assert `--created` for existing devices. New tests exercise
the real ledger with fake tools, including session precedence and serial/AVD
identity. No lead design changed and no product code changed.

The concise `flint-dev` skill and AGENTS.md now carry the live emulator findings:
one platform per worktree (Android attachment shut down active iOS during the
earlier verification); targeted `kill --device <id>` then reattach for AX
`emulator_helper_failed`; explicit claims because `emulator list --worktree` is
not scoped; and the missing Android 35 Google APIs ARM64 system-image prerequisite.
The image's absence was rechecked during integration. No image was installed.

## Gates

Commands ran in this checkout without command-scoped SDK/JDK overrides. Each
entry records start time, wall time, exit status, and the observed result.

- `make help` — 00:55:31.473, 0.042 s, exit 0; all 26 targets listed.
- `make env` — 00:55:31.515, 0.079 s, exit 0; Xcode, XcodeGen, JDK, SDK, adb,
  emulator, SDK managers, and Orca resolved.
- `make selftest` — 00:55:31.595, 20.486 s, exit 0; environment 22, setup 7,
  Android verifier 47, release verifier 41, Python 28 (including cleanup's 20),
  and agent commands 20 all passed. Each suite ran once under this target.
- `make gates DRY_RUN=1` — 00:55:52.081, 0.122 s, exit 0; planned selftest only
  against `origin/main` merge-base `d519ff6`. Native source paths did not change;
  both native gates were nevertheless run explicitly for integration.
- `make verify-android` — 00:56:10.480, 7.661 s, exit 0; debug and release APKs,
  local-first release validation, and Gradle unit-test tasks passed. Gradle reused
  cached compilation and test results; no Android emulator was started.
- `make ios-build` — 00:56:18.142, 23.723 s, exit 0; `BUILD SUCCEEDED`, simulator
  app and extensions, with outputs in `ios-app/DerivedData`.
- `make verify-ios` — first attempt 00:56:57.255, 233.514 s, exit 2 after targeted
  interruption: Xcode built successfully, then reported
  `NSPOSIXErrorDomain Code=3` / `Simulator device failed to launch
  com.flint.peakfocus`; no tests completed. After three minutes without progress,
  its task-owned Xcode PID was interrupted and ultimately terminated.
- `make verify-ios` — retry 01:01:04.244, 60.049 s, exit 0; same selected simulator,
  `TEST SUCCEEDED`, all 113 tests passed with zero failures. The retry had a
  180-second timeout guard, which was not reached. Root cause of the initial
  launch stall is not established; no source or configuration changed to retry.
- `make clean-devices` — 01:03:05.111, 0.117 s, exit 0; no claimed devices.
- `make clean` — 01:03:05.229, 2.030 s, exit 0; removed 1,007,583,232 allocated
  bytes (960.9 MiB), including local DerivedData, generated project, Android
  outputs/caches, and Python bytecode. Shared caches were preserved.

Additional checks: `git diff --check`; all six ancestry checks; complete, unique
`.PHONY`; single DerivedData definition; no recipe SDK/JDK assignment prefixes;
Claude skill symlink; and skill `quick_validate.py` passed. The skill validator
used `/usr/bin/python3` because the default Python lacked PyYAML. The two new
real-ledger fixtures initially lacked Flint checkout marker files; those fixtures
were corrected before the passing full selftest.

## Simulator and cleanup evidence

After sourcing `scripts/flint-env.sh`, `xcrun simctl list devices booted` ran at
00:56:56.839 (0.288 s, exit 0): no booted devices. The JSON baseline at
00:56:57.127 (0.087 s, exit 0) also had empty iOS/watchOS device lists.
The iOS gate selected iPhone 17e, UDID `645A2204-ED7C-4253-B298-2749820B1B7E`.
During the first run it was the sole booted simulator; retry app log paths
confirm the same UDID. Recovery used `kill -INT 47368` (01:00:07.794, exit 0),
`xcrun simctl terminate 645A2204-ED7C-4253-B298-2749820B1B7E com.flint.peakfocus`
(01:00:37.578, 0.569 s, exit 0), and `kill -TERM 47368`
(01:00:47.656, exit 0), targeting only this task's test host/Xcode process.

The post-gate `xcrun simctl list devices booted --json` at 01:02:32.435
(0.304 s, exit 0) already contained zero booted devices. The full inventory at
01:02:54.935 (0.222 s, exit 0) confirmed that exact UDID was `Shutdown` and no
clones existed. No shutdown was issued because the selected device was already
stopped; the actor that stopped it is not established. No other simulator,
`flint-test`, or `flint-opal-reference` was controlled. The claim ledger was empty.

After `make clean`, only this task's report/evidence scratch remained. After
retaining the compact ledger, removing scratch, and committing the report,
`git status --porcelain --ignored` produced no output. No `local.properties`
was needed or created: the resolver supplied the SDK environment directly.

## Remaining user steps

- In Orca **Settings → Repository → Worktree Hooks**, switch the current
  `local-only` source to committed `orca.yaml`, remove the obsolete `pnpm install`
  local setup override, and review/trust the shared hook scripts. See the
  [bootstrap report](workspace-bootstrap-2026-10-27.md) for the migration steps.
- In Codex `/hooks`, review/trust the project `.codex/hooks.json` SessionEnd hook.
  Automatic cleanup is best effort until enabled/trusted; hard kills can bypass it.
- Install the AVD's configured `system-images;android-35;google_apis;arm64-v8a`
  image, then repeat Android Orca attachment/UI verification. Android builds and
  JVM tests passed, but live Android interaction remains unverified.

No push, PR action, release, global settings change, AVD wipe, or sensitive device
permission grant was performed. Physical-device Screen Time behavior is unverified.
