# Flint contribution rules

## Repository
- Flint is a native iOS and Android monorepo.
- Never hand-edit `ios-app/Flint.xcodeproj`; edit `project.yml` and run `make ios-gen`.
- Preserve existing user changes and inspect the PR/branch graph before consolidation.

## Workspace
- `scripts/flint-env.sh` resolves tool paths for Make/setup; source it for direct shell commands. Existing environment wins; Android then uses `local.properties`, then `~/Library/Android/sdk`.
- Run `make env` (or `make doctor`) for paths, sources, and missing tools. Worktree setup comes from the committed `orca.yaml`.
- Drive devices through the project `orca-emulator` / `orca-emulator-android` skills, not raw `adb` / `simctl` UI input.
- Drive one platform per worktree at a time; attaching Android in the same worktree shut down the active iOS simulator during verification.
- If `orca emulator ax` times out with `emulator_helper_failed`, recover the authorized helper using `orca emulator kill --device <UDID>`, then attach that exact UDID again and verify the returned `deviceUdid`.
- Android AVDs require their system image; the configured Android 35 Google APIs ARM64 image is currently missing on this host.
- Run targets validate exact returned device identity. An existing iOS helper may omit its backend only when its exact, valid UDID is independently verified in the simulator inventory. iOS names resolve exactly to a UDID; prefer exact UDIDs for direct Orca commands.
- iOS runs wait for SpringBoard after bootstatus and retry launch once only for FBS code 5. A timed-out boot grants no shutdown rights; review the exact UDID in the "possibly booted, unclaimed" warning manually.
- Android preflights the AVD image before boot/attach and resolves the sdkmanager executable for its remedy, otherwise gives generic guidance. It rechecks the ledger/live boot incarnation after building and before installation and launch.
- Before iOS input, require two fresh AX samples about one second apart showing Flint and an enabled target with a nonzero frame, plus an agreeing screenshot (bound readiness to eight polls). Tap once, then verify pixels and AX; a transition-time AX timeout is not a failed tap. Helper restart recovers AX availability only; never repeat taps blindly.
- Orca iOS `tap` can return `ok` without observable input; prefer iPhone 17 (`1BD4732B-EC1A-4074-8C70-6AC369607185`, reliable in most runs) for UI verification. The latest iPhone 17e failure persisted with a fresh helper, and boot origin does not explain it. Device Hub running is a suspected cause per [Orca #23997](https://github.com/stablyai/orca/issues/23997), unconfirmed because the simulator flag was not captured. Verify every postcondition with screenshot + AX, never repeat taps blindly, and report unresolved failures using the [upstream investigation](https://github.com/stablyai/orca/issues/23997#issuecomment-6461183992).

## Verification
- iOS changes: run `make verify-ios` when the toolchain is available.
- Android changes: run `make verify-android`.
- `scripts/` or `Makefile` changes: run `make selftest`.
- Selftest deadlines stop registered command trees before fixture removal; Apple Make launchers are pinned to native gnumake before fake toolchain isolation. If CLT-first PATH selects older Python, selftest selects a compatible python3 on PATH without weakening fd-safe cleanup.
- Cross-platform changes: run the relevant gates for both platforms.
- Report unavailable simulator, Xcode, Gradle, or physical-device verification honestly.

## Agent commands

Start with `make help` and the project `flint-dev` skill (`.agents/skills/flint-dev`).

| Target | When to use |
| --- | --- |
| `make env` | Inspect the resolved local build environment. |
| `make gates DRY_RUN=1` / `make gates` | Plan/run the gates for changed paths vs the local `origin/main` merge-base, including uncommitted work. |
| `make ios-run IOS_SIM="<name or UDID>"` | Build, attach, install and launch Flint on the selected simulator. |
| `make android-run AVD="<name or serial>"` | Attach the selected Android emulator, build debug, install and launch Flint. |
| `make logs-ios` / `make logs-android` | Inspect Flint logs using the same `IOS_SIM` / `AVD` selector as the run command. |
| `make pr-status` | Read the current branch's PR, checks and recent CI runs. |
| `make ancestry` | Fetch main and inspect ahead/behind counts and ancestry without remote writes. |
| `make clean-devices` | Release worktree-owned device resources before finishing. |

## GitHub
- PR creation, review submission, merge, close, admin bypass, branch deletion, and release publication are external actions requiring explicit authorization.
- Verify CI and branch ancestry before merging.
- Never use admin bypass merely to avoid a failing or missing check.

## Product
- Preserve Flint’s local-first, no-account, no-analytics product posture.
- Treat Screen Time, accessibility, device-owner, and anti-removal behavior as security-sensitive.

## Cleanup
- Claim devices used by this worktree with `scripts/flint-devices.sh claim`; `--booted` requires proof this invocation booted it and a matching `--incarnation` token. Run targets manage this proof. Use `--created` only for a simulator you created with the prefix from `scripts/flint-devices.sh prefix`, and `--avd NAME` for Android. Stale or unverifiable incarnations never authorize shutdown.
- `orca emulator list --worktree` is not worktree-scoped ([Orca #34749](https://github.com/stablyai/orca/issues/34749)). Use the claim ledger (or `make ios-run` / `make android-run`), which inherits the session identity used by cleanup; list output is not ownership evidence.
- Run `make clean-devices` before `worker_done` or ending an agent session; unclaimed/shared devices are preserved. Project `SessionEnd` hooks provide best-effort cleanup when enabled/trusted; they do not run after every turn.
- Keep build outputs while a human may continue work. `make clean` removes worktree artifacts; the Orca archive hook releases devices and artifacts before removal. Cleanup rejects noncanonical paths and symlink ancestors. `make clean-orphans ORPHAN_PLAN=plan.json` previews; deletion requires the reviewed plan and its digest (`CONFIRM=1 ORPHAN_PLAN=plan.json ORPHAN_DIGEST=...`). Names alone never authorize simulator deletion; legacy exceptions require explicitly reviewed exact UDIDs in the plan.
- Interrupted artifact deletion restores the verified remaining directory without replacing a new build. If restoration is unsafe, cleanup prints the retained `.flint-clean-*` directory and its `recovery.json` provenance; later artifact/orphan cleanup defers for manual review while a quarantine remains.
