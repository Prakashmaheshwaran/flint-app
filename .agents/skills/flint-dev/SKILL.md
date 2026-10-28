---
name: flint-dev
description: Use when implementing or verifying changes in Flint's native iOS and Android monorepo, including its build, device, and local gate loop.
---

# Flint development loop

Read `AGENTS.md`, then orient with `make help` and `make env`. Use `make help`
for current targets and override options rather than duplicating their mechanics.

1. Inspect the branch and preserve unrelated work. Build the affected platform with
   `make ios-build` or `make android`. Generate iOS projects through `make ios-gen`;
   never hand-edit `ios-app/Flint.xcodeproj`.
2. When device use is authorized and available, run `make ios-run IOS_SIM="<name or UDID>"`
   or `make android-run AVD="<name or serial>"`. These attach the selected device to
   this worktree and install/launch Flint. Load the project `orca-emulator` skill
   for iOS interaction or `orca-emulator-android` for Android interaction. Use
   `make logs-ios` / `make logs-android` with the same selector for app logs.
   Drive one platform per worktree at a time: attaching Android in the same
   worktree shut down the active iOS simulator during verification. If
   `orca emulator ax` times out with `emulator_helper_failed`, run
   `orca emulator kill --device <id>`, then attach that device again.
   Android AVDs need their configured system image installed; the Android 35
   Google APIs ARM64 image is currently missing on this host.
3. Review `make gates DRY_RUN=1`, then run `make gates` for the AGENTS.md path-based
   checks against the local `origin/main` merge-base, including uncommitted work.
   `make ancestry` refreshes main and reports divergence; `make pr-status` reads
   the current branch's PR and CI state. Neither writes to the remote.
4. `orca emulator list --worktree` is not worktree-scoped. Claim devices through
   `scripts/flint-devices.sh` or the run targets above; the ledger inherits the
   same session identity as cleanup. Use `--booted` only for a cold start,
   `--avd NAME` for Android, and `--created` only for a simulator you created
   using the prefix from `scripts/flint-devices.sh prefix`.
   Run `make clean-devices` before finishing, following its ownership safeguards.
   Report what changed, what actually passed, and any unavailable verification.

Keep Flint local-first, account-free, and analytics-free. Treat Screen Time,
accessibility, device-owner, and anti-removal behavior as security-sensitive;
do not grant sensitive permissions or claim enforcement from build/tests alone.
Simulator evidence does not prove physical-device Screen Time behavior.

Do not send external messages, push, create/review/merge/close PRs, publish
releases, or change account/security state without explicit authorization.
Respect device reservations and task-specific limits; if Xcode, Gradle, a simulator,
or hardware verification is unavailable, name the missing check honestly.
