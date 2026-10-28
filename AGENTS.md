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

## GitHub
- PR creation, review submission, merge, close, admin bypass, branch deletion, and release publication are external actions requiring explicit authorization.
- Verify CI and branch ancestry before merging.
- Never use admin bypass merely to avoid a failing or missing check.

## Product
- Preserve Flint’s local-first, no-account, no-analytics product posture.
- Treat Screen Time, accessibility, device-owner, and anti-removal behavior as security-sensitive.

## Cleanup
- Claim devices used by this worktree with `scripts/flint-devices.sh claim`; use `--booted` only after starting a previously stopped device, `--created` only for a simulator you created with the prefix from `scripts/flint-devices.sh prefix`, and `--avd NAME` for Android.
- Run `make clean-devices` before `worker_done` or ending an agent session; unclaimed/shared devices are preserved. Project `SessionEnd` hooks provide best-effort cleanup when enabled/trusted; they do not run after every turn.
- Keep build outputs while a human may continue work. `make clean` removes worktree artifacts; the Orca archive hook releases devices and artifacts before removal. `make clean-orphans` is a host-wide preview unless explicitly confirmed with `CONFIRM=1`.
