# Workspace setup follow-up fixes — 2026-10-27

Task K uses child worktree `ws-fixes2`, branch `Prakashmaheshwaran/ws-fixes2`.
The required base was verified as
`0b168eb0c698e4f59ec8ea17fb8733f257639664` before implementation. The stopped
attempt's partial work was reviewed and regression-tested before retention.
All device scenarios below use disposable repositories, isolated HOME/SDK,
fake executables and bounded logs. No live device diagnostic was needed.

## Finding to regression mapping

### N1 — interrupted deletion and quarantine discovery

Cleanup durably writes mode-0600 `recovery.json` before moving the selected
artifact. The record binds its original absolute path, directory device/inode,
ancestor identities and quarantine identity. On failure, including the cleanup
deadline, it revalidates the remaining inode and its ancestors and restores
using an atomic exclusive rename. An existing replacement build, including an
empty directory, is preserved. Platforms without an exclusive rename retain
the quarantine instead of attempting a check-then-overwrite rename.

When restoration is unsafe, the error prints the full quarantine and provenance
paths. Subsequent artifact cleanup scans the whole checkout, including custom
output parents; orphan preview and approved cleanup check their host artifact
directory. Any retained, legacy or unverified quarantine defers cleanup for
manual review before deleting replacement output or reporting success. The
manifest itself is not deletion authorization.

Regressions in `scripts/test_cleanup.py`:

- `test_n1_real_immutable_denial_restores_and_retry_removes`: real macOS
  `UF_IMMUTABLE` denial; clears only the fixture flag in teardown and retries.
- `test_n1_mid_delete_restores_exact_remaining_tree_and_retry` and
  `test_n1_deadline_restores_remaining_tree_and_retry`: remove one fixture file,
  interrupt deletion, verify the exact remaining inode, then retry successfully.
- `test_n1_new_build_is_never_clobbered_and_quarantine_retry_defers` and
  `test_n1_empty_replacement_build_is_never_clobbered`: preserve newly created
  output and its inode; report and rediscover retained provenance.
- `test_n1_hard_stop_keeps_durable_provenance_and_retry_defers`: abrupt process
  exit after partial removal still leaves a discoverable, recorded remainder.
- `test_n1_legacy_or_unverified_quarantine_is_discovered_and_deferred` and
  `test_n1_orphan_quarantine_defers_preview_and_approved_cleanup`: neither local
  cleanup nor a saved orphan approval can silently ignore quarantines.

### N2 and M4 — outer deadline owns nested process cleanup

The outer watchdog and `AgentCommands.run_cmd` share a locked process registry.
Commands retain their separate sessions. Each launcher waits behind a pipe
until its PID/start identity and random inherited provenance token are recorded.
Cancellation seals registration, freezes and discovers process trees across
groups/sessions, kills verified processes, and confirms they cannot execute
before fixture removal. Reparented descendants remain discoverable by their
fixture token. This is cooperative test-process provenance, not containment
for hostile programs that deliberately scrub their environment.

An unverifiable cancellation retains fixtures and fails supervision. Each
`test_*.py` file gets its own 120-second suite deadline, including automatically
discovered follow-up suites; per-command limits and 1 MiB log/file caps remain.
Process environment contents are not logged.

Regressions in `scripts/test_selftest_followups.py` include
`test_n2_outer_deadline_stops_actual_run_cmd_tree_before_teardown`: the actual
run_cmd child, grandchild and separate-session grandchild are stopped before
the outer fixture directory disappears, and none can write its release sentinel.
Additional regressions cover concurrent registered commands
(`test_n2_outer_deadline_stops_every_registered_command`), exited leaders
(`test_n2_outer_deadline_recovers_orphans_after_session_leader_exits`), fixture
retention after denied cancellation
(`test_n2_failed_termination_retains_fixture_and_reports_failure`), replacement
PID preservation (`test_n2_stale_pid_identity_never_signals_replacement`), bounded
locking (`test_n2_busy_registry_lock_is_bounded`) and sealed registration
(`test_n2_sealed_registry_never_starts_another_command`).

### N3 — ambiguous iOS boot timeout

A timed-out `simctl boot` prints the exact UDID with an explicit "possibly
booted, unclaimed; review manually" warning. It grants no shutdown claim and
does not infer ownership from a later Booted observation.

Regression: `DeviceFollowups.test_n3_ambiguous_boot_timeout_warns_exact_udid_without_claim_or_shutdown`.

### N4 — Apple Make tool discovery

Before fake DEVELOPER_DIR isolation, tool discovery resolves both Apple launcher
locations (`/usr/bin/make` and the CommandLineTools `make`), including symlink
spellings, to installed native `gnumake`. Caller-selected third-party Make
binaries and their symlink spellings remain intact.

The full CLT-first gate also exposed that this PATH selects Apple's Python
3.9.6 ahead of Python 3.14.6. The older interpreter lacks `rmtree(dir_fd)`, so
cleanup correctly refused deletion and the initial CLT gate failed 20 cleanup
assertions. The entrypoint now probes subsequent python3 executables on PATH
and re-execs a compatible interpreter before starting suites. If none supports
symlink-safe fd-relative cleanup, it exits 2 with a clear requirement. The
cleanup guard and caller-selected capable interpreters are preserved.

Regressions in `SelftestFollowups`:
`test_n4_apple_make_launchers_and_symlinks_pin_gnumake`,
`test_n4_pinned_make_runs_with_fake_developer_dir`,
`test_n4_third_party_make_and_symlink_are_preserved`, and
`test_n4_missing_native_make_keeps_discovered_tool`,
`test_n4_clt_python_shadowing_selects_compatible_python`, and
`test_n4_no_compatible_python_fails_clearly_before_suites`. The two interpreter
selection tests both failed before this additional fix and pass afterward.

### E2E-J1 — reused iOS helper identity

An omitted/null backend is accepted only for an exact, syntactically valid iOS
UDID positively verified in fresh simctl inventory. Explicitly wrong backends,
mismatched identities and malformed values still stop claim/install/launch.
The live retry log exposed `backend=None`, not the raw attach payload; tests
cover both missing and JSON-null forms instead of inventing which was returned.

Regressions in `DeviceFollowups` cover
`test_j1_missing_and_null_backend_accept_only_exact_verified_simulator`,
`test_j1_missing_backend_still_rejects_wrong_or_malformed_identity`,
`test_j1_missing_backend_requires_fresh_simctl_membership`, and
`test_j1_rejects_non_uuid_even_if_inventory_and_backend_match`, and
`test_j1_malformed_fresh_inventory_fails_without_claim_or_traceback`.

### E2E-J2 — bounded launch readiness and single retry

Bootstatus was already present. Runs now additionally poll SpringBoard's running
state and positive PID through bounded `launchctl print` before installation.
An app launch that fails with FBSOpenApplicationServiceErrorDomain code 5 gets
one delayed retry after another readiness check. A second failure, another
error domain/code, or readiness expiry fails clearly without further launch
attempts. This mitigates a readiness/transient-failure window; the earlier live
failure's root cause remains unproven.

Regressions: `test_j2_springboard_readiness_follows_bootstatus_and_precedes_install`,
`test_j2_unready_springboard_is_bounded_and_never_installs`,
`test_j2_springboard_requires_positive_pid`,
`test_j2_code5_then_success_retries_once`,
`test_j2_code5_twice_fails_after_exactly_two_attempts`, and
`test_j2_other_domain_code5_is_not_retried`,
`test_j2_same_domain_other_code_is_not_retried`, and
`test_j2_readiness_loss_after_code5_aborts_retry_within_deadline`.

### E2E-J3 — actionable missing-image guidance

The remedy uses the executable sdkmanager resolved on PATH and the resolved
`--sdk_root`, quoting paths as argv data. If no executable is found, generic
SDK Manager guidance replaces the formerly fabricated path.

Regressions: `test_j3_missing_image_hint_uses_executable_path_and_resolved_sdk`
and `test_j3_absent_sdkmanager_prints_generic_guidance`.

### Residual Android boundary — recheck the boot instance

The run transaction pins an incarnation before attach/build and compares it
with both the ledger and live process evidence after building, before install,
and again before launch. PID reuse with a different start time, a changed PID,
or missing evidence aborts. Use-only runs retain use-only shutdown rights.

Regressions: `test_android_restart_during_attach_cannot_replace_selected_incarnation`,
`test_android_same_avd_new_pid_after_build_aborts_install`,
`test_android_same_pid_new_start_after_build_aborts_install`,
`test_android_same_avd_new_incarnation_after_install_aborts_launch`,
`test_android_missing_process_evidence_aborts_install`, and
`test_android_same_incarnation_runs_with_use_only_rights`.

## Differential evidence and verification

The eight N1 methods were run with the fixture cleanup implementation replaced
by `git show 0b168eb:scripts/clean-worktree.py`. All eight failed on the missing
behavior: eight assertion failures (including orphan preview/confirmation
subtests) and one missing-manifest error. The fixed full cleanup suite passed
55 tests in 38.282 seconds.

The device differential ran 22 methods against the base: 18 methods failed,
with 20 failure records including subtests and zero errors. The fixed device
suite passed 22/22 in 62.075 seconds. Initial N2/N4 differential probes reproduced
five failure records, including the child and both grandchildren escaping past
outer teardown and Apple launcher/symlink failures. The eleven initial N2/N4
follow-ups passed in 3.149 seconds; with interpreter selection coverage, all
thirteen passed in 3.431 seconds. The existing 44-test agent suite passed;
four targeted checks passed after final teardown hardening. A separate static
N1 review found no concrete safety regression; its scope and evidence are in
[device-followups.txt](workspace-setup-fixes-2-evidence-2026-10-27/device-followups.txt).

Before the interpreter-selection addition, normal-PATH selftest passed in
171.126 seconds: 107
discovered Python tests, 44 agent tests and 117 shell checks (22 environment,
7 setup, 47 Android verifier and 41 release checks). The initial CLT-first run
failed in 61.046 seconds for the Python compatibility reason above; its
[failed log](workspace-setup-fixes-2-evidence-2026-10-27/selftest-clt-initial.txt)
is retained. Both full variants passed after that fix; final cleanup results are
recorded below.

Final acceptance passed on the committed implementation:

- `PYTHONDONTWRITEBYTECODE=1 make selftest`: **168.435 seconds**, 109 discovered
  Python tests, 44 agent tests, and 117 shell checks; zero failures or skips.
- `PATH="/Library/Developer/CommandLineTools/usr/bin:$PATH" PYTHONDONTWRITEBYTECODE=1 make selftest`:
  **164.692 seconds**, the same passing tests/checks. Both Apple Make launchers
  report GNU Make 3.81; the bootstrap selected compatible Python 3.14.6.
- `make verify-android`: **8.024 seconds**, debug/release builds, local-first APK
  verification and Android/JVM test tasks passed.
- `make ios-build`: **22.400 seconds**, app/extension simulator compilation passed.
- `make clean-devices`: **0.129 seconds**, no claims; no live device/helper queried
  or changed.
- `make clean`: **2.923 seconds**, removed 801.2 MiB (840,105,984 bytes) of worktree
  artifacts, including the verification harness. No ignored artifacts, bytecode,
  quarantines or bootstrap leftovers remained. Final `git status --porcelain --ignored`
  was empty after committing guidance/evidence.

Exact commands, exits and durations are in
[commands.jsonl](workspace-setup-fixes-2-evidence-2026-10-27/commands.jsonl).
Native build logs retain at most 512 KiB of first/last output, explicitly marking
omitted middles; the iOS log preserves `BUILD SUCCEEDED`. Their text uses LF and
strips trailing whitespace for repository checks; `output_bytes` records the
raw captured byte count.

## Limits

Device behavior is fake-verified. No simulator boot, live attachment, live
launch, Android image installation or physical-device enforcement was exercised.
The SpringBoard probe and FBS retry still need a separately authorized live
use-only E2E run; this report does not claim that Task J's live failure is proven
fixed or that its root cause was established. The task's device-free scope used
`ios-build` rather than simulator-running `verify-ios`.

No remote write, push, PR, publication, global configuration change, or shared
cache deletion was performed. Developer guidance changed only for the behavior
above in AGENTS.md and the project flint-dev skill.

Implementation commits are `79db421` (quarantine recovery), `96021d3` (device
transactions), `844a119` (selftest supervision and Apple Make) and `62f26e4`
(compatible interpreter selection). Each includes
the requested Codex co-author trailer. Both current helper Dispatches completed
successfully and were released through Orca; the worktree is retained.
