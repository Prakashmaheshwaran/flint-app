# Flint contribution rules

## Repository
- Flint is a native iOS and Android monorepo.
- Never hand-edit `ios-app/Flint.xcodeproj`; edit `project.yml` and run `make ios-gen`.
- Preserve existing user changes and inspect the PR/branch graph before consolidation.

## Verification
- iOS changes: run `make verify-ios` when the toolchain is available.
- Android changes: run `make verify-android`.
- `scripts/` or `Makefile` changes: run `make selftest`.
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
