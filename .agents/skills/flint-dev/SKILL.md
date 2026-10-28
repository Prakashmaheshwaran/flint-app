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
   this worktree and install/launch Flint. Names resolve exactly; direct iOS
   commands should use exact UDIDs. Both run targets validate the attach identity
   before installation. Existing iOS helper replies with a missing/null backend
   still require an exact, valid UDID in fresh simulator inventory. Runs wait for
   SpringBoard after bootstatus; only FBS code 5 earns one delayed launch retry.
   An ambiguous boot timeout prints the exact possibly booted, unclaimed UDID
   for manual review and grants no shutdown rights. Load the project
   `orca-emulator` skill for iOS interaction or `orca-emulator-android` for Android
   interaction. Use
   `make logs-ios` / `make logs-android` with the same selector for app logs.
   Drive one platform per worktree at a time: attaching Android in the same
   worktree shut down the active iOS simulator during verification. If
   `orca emulator ax` times out with `emulator_helper_failed`, run
   `orca emulator kill --device <UDID>`, then attach that exact UDID again and
   recheck `deviceUdid`. This recovers AX availability, not tap delivery.
   Before iOS input, require two fresh AX samples about one second apart showing
   Flint and an enabled target with a nonzero frame; bound readiness to eight
   polls and confirm the screenshot agrees. Tap once at the current frame center,
   then inspect a screenshot and AX for the intended destination. AX may time out
   after a successful transition; never blindly repeat a tap. Persistent late
   tap failures remain unresolved; retain evidence using the
   [Task H upstream issue guidance](/Users/prakash/flint-planning/2026-10-27/workspace-setup/ios-tap-investigation.md#upstream-issue-wording-if-the-persistent-failure-recurs).
   Android AVDs need their configured image; the run target fails before attach
   with the exact missing package and installation guidance. The Android 35 Google
   APIs ARM64 image is currently missing on this host. The remedy uses the
   executable sdkmanager on PATH, or generic SDK Manager guidance if unavailable.
   Android runs also compare the recorded/live boot incarnation after building
   and before installation and launch; a restart requires an explicit rerun.
3. Review `make gates DRY_RUN=1`, then run `make gates` for the AGENTS.md path-based
   checks against the local `origin/main` merge-base, including uncommitted work.
   Selftest selects a Python on PATH supporting fd-safe cleanup if a CLT launcher
   shadows it, and pins Apple Make launchers before fake toolchain isolation.
   `make ancestry` refreshes main and reports divergence; `make pr-status` reads
   the current branch's PR and CI state. Neither writes to the remote.
4. `orca emulator list --worktree` is not worktree-scoped. Claim devices through
   `scripts/flint-devices.sh` or the run targets above; the ledger inherits the
   same session identity as cleanup. Run targets explicitly boot cold devices;
   only a proven own boot with a matching `--incarnation` token earns `--booted`.
   Helper cleanup also requires matching PID/start-time provenance. Use
   `--avd NAME` for Android, and `--created` only for a simulator you created
   using the prefix from `scripts/flint-devices.sh prefix`.
   Run `make clean-devices` before finishing, following its ownership safeguards.
   Orphan cleanup requires a saved reviewed plan plus its digest; `CONFIRM=1`
   alone never authorizes deletion, and a simulator name is only a preview hint.
   Report what changed, what actually passed, and any unavailable verification.
   Interrupted artifact removal restores the verified remaining tree without
   replacing new output. If a `.flint-clean-*` quarantine remains, review its
   printed location and `recovery.json` provenance manually; later artifact and
   orphan cleanup defers while quarantines remain.

Keep Flint local-first, account-free, and analytics-free. Treat Screen Time,
accessibility, device-owner, and anti-removal behavior as security-sensitive;
do not grant sensitive permissions or claim enforcement from build/tests alone.
Simulator evidence does not prove physical-device Screen Time behavior.

Do not send external messages, push, create/review/merge/close PRs, publish
releases, or change account/security state without explicit authorization.
Respect device reservations and task-specific limits; if Xcode, Gradle, a simulator,
or hardware verification is unavailable, name the missing check honestly.
