# Workspace setup final patch — 2026-10-27

Task M uses child worktree `ws-final-patch`, branch
`Prakashmaheshwaran/ws-final-patch`, based on
`Prakashmaheshwaran/workspace-setup-automation`. HEAD and the base branch were
both verified at `24a410f661119719b8193f93b9fc4c28bf8ac741` before editing;
Orca's current-worktree response confirmed the child lineage. Palolo was not edited.

## Change

`scripts/agent/device-run.py` gives implicit `xcrun simctl` calls the single
`SIMCTL_QUERY_TIMEOUT = 30` second budget, instead of three seconds. This covers
inventory and nonblocking bootstatus queries. SpringBoard's `launchctl print`
query and total readiness phase use the same budget, capped by the caller's
shorter device deadline. Explicit boot, blocking bootstatus, launch, ledger and
ADB budgets remain unchanged; other implicit commands retain three seconds.
Timeout errors include the full shell-quoted command and effective bound.
Existing process-group termination, output caps and device ownership guards
remain in place.

The project flint-dev skill and one AGENTS.md line record that an Orca iOS tap
acknowledgement does not prove delivery or navigation. Prefer iPhone 17, exact
UDID `1BD4732B-EC1A-4074-8C70-6AC369607185`, which worked in most recorded runs.
The latest iPhone 17e run persistently retained Welcome despite app recovery and
a fresh helper. Historical successes and failures span both helper-driven and
explicit simctl boots; boot origin alone does not explain them. Verify every
postcondition with screenshot plus AX, never blindly repeat input, and retain
the investigation's upstream issue wording. This patch does not fix or identify
the cause of the tap failure.

These live observations are inherited from
[Task L's validation and boot-origin comparison](/Users/prakash/flint-planning/2026-10-27/workspace-setup/e2e-validation-3.md)
and [Task H's investigation/upstream wording](/Users/prakash/flint-planning/2026-10-27/workspace-setup/ios-tap-investigation.md#upstream-issue-wording-if-the-persistent-failure-recurs);
they were not rerun for this device-free patch. The observed three-second
inventory expiry is verified; CoreSimulator contention after peer boot remains
an inference about its cause.

## Regression evidence

`scripts/test_device_timeouts.py` uses disposable checkouts, isolated HOME/SDK
and fake executables through the existing AgentCommands harness. The focused
command was:

```sh
source scripts/flint-env.sh
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover -s scripts -p test_device_timeouts.py -v
```

At the original implementation, six tests ran in **28.879 s**, with five
failures: five-second inventory and nonblocking bootstatus queries hit three
seconds; the five-second SpringBoard query exhausted its 15-second readiness
phase after repeated three-second timeouts; the hung inventory stopped too
early; and the command diagnostic lacked shell quoting. The existing shorter
readiness deadline passed. [Before log](workspace-setup-final-patch-evidence-2026-10-27/timeouts-before.txt)

After the patch, all **6/6 passed in 50.535 s**. A fake inventory sleeping
60 seconds is stopped at the 30-second budget (the test permits 29–34 seconds
including process/harness overhead), names the full command and bound, and
creates no claim or attachment. Five-second inventory, nonblocking bootstatus
and SpringBoard queries succeed. A hanging SpringBoard probe still respects a
0.3-second configured readiness deadline, and a cheap command retains its
three-second default. [After log](workspace-setup-final-patch-evidence-2026-10-27/timeouts-after.txt)

## Final verification

- `PYTHONDONTWRITEBYTECODE=1 make selftest`: passed, **115 discovered Python
  tests, 44 agent-command tests and 117 shell checks**, zero failures or skips.
  This includes all 22 existing device followups and the six new timeout
  regressions. [Full log](workspace-setup-final-patch-evidence-2026-10-27/selftest.txt)
- The gate's `/usr/bin/make` reports **GNU Make 3.81**. `make clean-devices`,
  `make clean` and `git diff --check` all exited zero. No device claims or
  generated artifacts remained; final post-commit `git status --porcelain --ignored`
  was empty, including no bootstrap leftovers.
  [Cleanup/version log](workspace-setup-final-patch-evidence-2026-10-27/cleanup.txt)

No simulator/emulator boot, attachment, UI input, permission grant, SDK install,
native platform gate, external publication, push, PR or global setting change
was performed. Live input reliability and physical-device enforcement remain
outside this patch's verification scope.
