# Managed backend verification — 2026-10-11

## Delivered boundary

Local Firebase Auth/Firestore emulator backend only. Owner-managed sessions/recurring schedules, explicit group/member targeting and IANA zones, optimistic revisions, deletion tombstones, regenerated membership IDs, and authenticated complete member snapshots are implemented. [Frozen contract](../../../connected-services/MANAGED_CONTRACT.md).

No managed Android enforcement, recurrence/DST evaluator, bypass grants, quota, billing, rewards, push delivery, deployment, or Opal API call is claimed.

## Checks

- `npm run check`: passed for all backend, launcher and integration test source files.
- Real Auth + Firestore emulator integration run: **24 passed, 0 failed**, no skipped tests; duration 11,574.840459 ms. Node 24.21.0, pinned Firebase tools 15.33.0/Admin SDK 14.5.0; project `demo-flint`.
- Existing 12 account/group/invite/auth tests remained passing.
- New 12 managed tests covered actor-bound complete snapshots; owner/member/foreign authorization; session/recurrence fields; scope/date/zone/target validation; concurrent optimistic update conflicts; scoped tombstones and delete replay; member retargeting; leave/rejoin identity; legacy migration; capacity; tombstone retention; direct Firestore denial; revoked/disabled sessions; and API-process recreation.
- `make selftest`: passed, 47 Android harness checks and 39 release-checker checks. Required because the local launcher changed.
- The test runner used fresh ephemeral emulator processes without import/export. It did not load or erase the persistent development snapshot.

Sanitized results are recorded in [backend-managed-tests.txt](backend-managed-tests.txt). Private emulator request/debug logs and account fixture files are excluded.

## Shutdown failure and explicit recovery

An initial Ctrl-C through the old npm/runtime wrapper ended the launcher before it completed export. Its detached Firebase processes still inherited the terminal output stream. A subsequent export attempt failed as Auth/hub exited; the on-disk Auth export was still empty. This was a real persistence failure, not a successful durable restart.

Recovery preserved the still-running Firestore data through its local emulator export endpoint before stopping the orphaned Firestore process. With the parent agent's explicit authorization, exactly the two synthetic Android test Auth users were recreated from the existing private fixture with their original UIDs/passwords. No credential values were printed or committed. Original profile/group/member records were read back: the existing `Parity Family` group retained the owner and member.

This recovery is distinct from the subsequent persistence verification. It does not retroactively prove the failed shutdown preserved Auth state.

## Launcher correction and durable restart proof

The launcher now gives child processes private ignored log files instead of inherited terminal streams, and handles repeated SIGINT/SIGTERM/SIGHUP without interrupting an in-progress export. API shutdown precedes emulator export. `.emulator-data/` and `logs/` are mode 0700; new export/log files use the private umask.

After recovery:

1. Created an expired, member-scoped managed test session. Saved its server revision and membership identity privately.
2. Sent Ctrl-C to the running launcher. It exited 0; export completion and release of API/Auth/Firestore/hub ports were observed.
3. Started fresh emulator/API processes from the saved snapshot.
4. Both synthetic users signed in again. Original UIDs, profiles, group/member count, managed rule, authority revision, and membership ID matched the pre-shutdown values.
5. Deleted the expired test rule using its expected revision; the member snapshot omitted it and included its tombstone.
6. Sent SIGINT and SIGTERM consecutively to the next launcher. It still exited cleanly and exported.
7. Repeated the original `npm exec --yes --package=node@24.21.0 -- node scripts/local.mjs` wrapper launch and Ctrl-C path. It exited 0, logged completed export, and released all owned service ports.
8. Started the persistent service again. Final readback confirmed both sign-ins, the original two-member group, no live managed rules, and the retained test deletion tombstone. Service remains local and running.

The two synthetic identities were preserved after recovery and all subsequent clean restarts. The test rule is no longer active or live. Hard kills/crashes can still lose changes since the last successful export; no continuous durability guarantee is claimed.
