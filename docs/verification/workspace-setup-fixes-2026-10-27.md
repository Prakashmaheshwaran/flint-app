# Workspace setup safety fixes — 2026-10-27

Task G started in the clean child worktree `ws-fixes`, branch
`Prakashmaheshwaran/ws-fixes`, at the required
`4f0d2b7d1227cf22576ce660731a477c0ec22b28`. This report addresses every finding in
the independent safety review and the device-run findings in the E2E report.
The [command evidence](workspace-setup-fixes-evidence-2026-10-27.jsonl) retains
bounded results, exact gate commands, elapsed times, and baseline failures.

## Findings and regression coverage

Test names below are executable tests, not manual test recipes. `WorkspaceSafety`
lives in `scripts/test_workspace_safety.py`, `CleanupTests` in
`scripts/test_cleanup.py`, and `AgentCommands` in `scripts/agent/selftest.py`.
All adversarial device and deletion tests use disposable fixtures and fake tools.

| Finding | Resulting behavior | Regression tests |
| --- | --- | --- |
| H1: canonical containment | Validation and removal use one absolute, lexically canonical path; reject `..`, outside paths, every symlink ancestor, non-directory overrides, tracked descendants, source roots, and Git administrative paths before deleting any candidate. | `test_h1_dotdot_source_root_cannot_bypass_tracked_protection`; `test_h1_git_alias_and_nondirectory_config_are_preserved`; `test_h1_custom_directory_with_dot_spelling_is_removed` |
| H2: deletion race | Open and retain each directory component with `O_NOFOLLOW\|O_DIRECTORY`; recheck inode/device identities, quarantine the verified leaf relative to the held parent fd, and remove one basename with fd-safe `shutil.rmtree`. Orphan removal uses the same guard. | `test_h2_parent_symlink_swap_during_size_probe_preserves_other_checkout`; `test_h2_leaf_inode_replacement_is_preserved`; `test_h2_orphan_parent_swap_preserves_other_host_artifact`; `test_h2_rmtree_requires_local_symlink_safe_implementation` |
| H3: Make interpolation | The Make shell path is fixed and relative; settings are frozen as literal Make values and exported. Recipes consume quoted environment/argv data, including recursive Make. | `test_h3_checkout_metacharacters_are_data`; `test_h3_make_environment_and_cli_values_are_literal`; `test_h3_native_and_selector_argv_are_literal`; `test_h3_default_recursive_make_executable_is_resolved` |
| M1: cold-start ownership | Observe iOS state after building and explicitly boot the exact UDID. Android starts its own emulator process group on a selected port. Only a proven own boot with a matching incarnation grants `--booted`; existing, raced, or unverifiable boots remain use-only. | `test_m1_ios_other_actor_boots_during_build_gets_use_only_claim`; `test_m1_ios_failed_boot_race_gets_use_only_claim`; `test_m1_ios_cold_boot_requires_explicit_boot_and_incarnation`; `test_m1_ios_unknown_incarnation_never_grants_shutdown_rights`; `test_e2e2_cold_start_without_group_proof_never_claims` |
| M2: incarnation | Claims retain structured emulator PID/start/group/guest-boot evidence and a digest; iOS uses the boot epoch when available, otherwise the simulator's `launchd_sim` PID/start. Cleanup rechecks the live instance. Helpers separately require the validated attach PID, process start, current device match, and no peer claim. | `test_m2_same_avd_same_serial_restart_does_not_inherit_poweroff`; `test_m2_ios_reboot_does_not_inherit_poweroff`; `test_m2_ios_managerpid_fallback_and_restart_without_lastbootedat`; `test_m2_unknown_incarnation_and_pid_group_mismatch_fail_closed`; `test_m2_reclaim_after_restart_never_ors_old_boot_rights`; `test_m2_helper_exact_incarnation_is_released_without_poweroff`; `test_m2_helper_reused_pid_and_missing_helper_preserve_booted_device`; `test_m2_helper_peer_claim_blocks_release_then_transfers_proof` |
| M3: orphan approval | Legacy names are preview hints. Simulator deletion requires an explicitly selected exact UDID in a saved plan; confirmation must supply that plan's digest. Revalidation checks identities, claims, checkout liveness, and the complete approved set; newly discovered candidates are never added. | `test_m3_bare_confirm_never_authorizes_name_only_delete`; `test_m3_exact_plan_deletes_only_explicit_legacy_udid`; `test_m3_plan_digest_tamper_and_wrong_approval_preserve_everything`; `test_m3_changed_identity_or_new_claim_invalidates_plan_before_artifact_delete`; `test_m3_new_live_checkout_invalidates_saved_artifact_plan` |
| M4: selftest isolation | Fixture creation starts with temporary HOME/XDG, sanitized Git variables, `/dev/null` global configuration, system config disabled, and hooks disabled. Python suites inherit a process-wide 1 MiB file limit; subprocesses and the suite runner have deadlines and bounded logs. | `test_m4_setup_suite_ignores_caller_git_hooks`; `test_m4_cleanup_fixture_isolates_home_git_xdg_and_caps_subprocesses`; `test_m4_caller_home_git_config_and_hooks_are_not_inherited`; `test_m4_subprocess_deadline_terminates_descendants`; `test_m4_file_cap_applies_process_wide`; `test_m4_subprocess_output_is_capped` |
| M5: resolver termination | Normalize relative roots against PWD before walking ancestors, including nonexistent roots; every iteration reaches a shorter absolute parent. | `test_m5_relative_and_nonexistent_root_terminate` |
| L1: Java Properties | Decode supported ordinary Properties escapes; explicitly reject unsupported Unicode/control escapes and continuation syntax. Setup rejects control characters before its existing noclobber creation; existing files remain untouched. | `test_l1_unicode_escape_is_decoded_or_rejected`; `test_l1_setup_rejects_control_characters_without_writing`; existing environment/setup preservation and escaped-path checks |
| L2: global shell PATH | Independently and idempotently add emulator and platform-tools. The repository example and incoming-PATH drift diagnostics match the approved user-file edit. | `test_l2_zsh_shim_repairs_each_path_independently`; `test_l2_report_detects_each_missing_shell_path` |
| L3: selftest bytecode | Run the top-level runner with `-B` and propagate `PYTHONDONTWRITEBYTECODE=1` to suites and fixtures. | `test_l3_selftest_does_not_create_bytecode` |
| E2E-1: exact iOS attachment | Resolve an exact name to a unique UDID, attach by that UDID, and validate `attached`, backend, returned `deviceUdid`, and any helper PID before claiming/installing/launching. Failed attachment rolls back only a still-matching own boot under the peer-claim lock. | `test_e2e1_exact_name_attaches_resolved_udid`; `test_e2e1_mismatch_rolls_back_only_proven_own_boot`; `test_e2e1_mismatch_preserves_rebooted_or_shared_devices`; `test_e2e1_malformed_attach_never_claims_or_installs` |
| E2E-2: Android prerequisites and identity | Validate the exact AVD config and SDK-contained system image/package metadata before Orca discovery or attachment. Missing images fail immediately with the package ID and quoted sdkmanager command. Bound attachment, match returned serial/backend and AVD, and recheck the AVD before installation and launch. | `test_e2e2_missing_image_fails_under_five_seconds_before_device_calls`; `test_e2e2_missing_or_invalid_avd_config_prevents_attach`; `test_e2e2_image_package_metadata_must_match_resolved_sdk`; `test_e2e2_image_outside_resolved_sdk_is_rejected`; `test_e2e2_attach_timeout_is_bounded_and_never_installs`; `test_e2e2_attach_mismatch_rolls_back_only_own_serial`; `test_e2e2_avd_identity_is_rechecked_after_build_before_install`; `test_e2e2_avd_identity_is_rechecked_before_launch` |

The reviewed baseline was exercised by running the new regressions against a
disposable source copy of `4f0d2b7`. The Make/environment group produced 18
assertion failures plus the two expected resolver timeouts across ten tests:
the path-injection sentinels appeared, literal arguments changed, caller hooks
ran, unsupported Properties were silently misread/written, the shell omitted
emulator, and selftest wrote bytecode. These cases pass with the fixes. The
recursive Make test additionally caught and verified correction of Make 3.81's
default `MAKE=$(MAKE_COMMAND)` alias during this task.

The cleanup differential run reproduced seven semantic baseline failures: the
two H1 protection tests, parent-symlink and orphan-parent H2 swaps, both M2
same-identity restart tests, and bare-confirm M3 deletion. All passed in the
final 47-test cleanup suite. The agent differential run reproduced eight
semantic baseline failures: both M1 other-actor races, E2E-1 exact attachment
and mismatched-identity rollback, E2E-2 missing image, package mismatch and
post-build identity change, and M4 caller-hook isolation. All passed in the
final 44-test agent suite; missing-image diagnosis took 0.300 s. The additional
real-ledger tests `test_m1_ios_real_ledger_uses_launchd_fallback_without_boot_epoch`
and `test_m1_android_real_ledger_binds_child_group_boot_and_helper` exercise the
contracts between the new runner and cleanup without real devices.

Saved orphan plans are atomic, mode 0600, and noclobber; choose a fresh filename
for each preview. `test_m3_preview_plan_never_overwrites_existing_file_or_symlink`
protects existing source/configuration and prior approvals. Rollback tests
`test_m2_rollback_preserves_claimed_or_restarted_device`,
`test_m2_rollback_only_stops_exact_proven_boot`, and
`test_e2e1_mismatch_rollback_preserves_peer_claim` verify the shared lock and
peer-preservation contract. `test_m2_claim_records_auditable_process_evidence`
checks that the ledger retains PID/start evidence, not just its digest.

## Verification

| Command | Evidence |
| --- | --- |
| `PYTHONDONTWRITEBYTECODE=1 make selftest` | Passed in 79.987 s: 66 Python tests (including 47 cleanup and 11 workspace-safety tests), 44 agent tests, and 22 environment / 7 setup / 47 Android verifier / 41 release checks. Combined output: 12,374 bytes; no `__pycache__` remained. |
| `make verify-android` | Passed in 8.561 s: debug/release assembly, local-first APK checks, and Android/JVM test tasks; Gradle reused eligible cached outputs. |
| `make ios-build` | Passed in 24.592 s: app and extension simulator compilation. |
| `PYTHONDONTWRITEBYTECODE=1 make verify-ios` | Passed in 63.700 s, 113 tests and zero failures, after exact-command coordinator approval. |
| `make clean-devices` | Passed in 0.191 s; the worktree ledger was empty, so no device/helper was queried or changed. |
| `make clean` | Passed in 3.934 s; removed 801.5 MiB (840,421,376 bytes) of worktree build outputs. No ignored artifacts or bytecode remained. |

The approved iOS gate used only `645A2204-ED7C-4253-B298-2749820B1B7E`
(iPhone 17e), after the tap investigator released it. The preflight booted list
was empty; this target was Shutdown before and after, so no shutdown command
was needed. Task G did not attach Orca, tap, erase, or delete any live simulator,
and never booted an Android emulator.

Host Python 3.14.6 reports `shutil.rmtree.avoids_symlink_attacks=True` and supports
`dir_fd`; removal requires both capabilities. Apple's `/usr/bin/python3` 3.9.6
reports the former but lacks the latter, so artifact cleanup fails closed
before quarantine on that interpreter. GNU Make 3.81 is exercised explicitly by
the regression fixtures.

## Approved user-file change

The coordinator approved the exact two-line diff to `~/.zshenv` before it was
applied. An exclusive backup was saved at
`/Users/prakash/.zshenv.flint-task-g-2026-10-27.bak`; no other dotfile was changed.
The previous combined platform-tools membership test is now two independent
tests, as shown in `scripts/flint-zshenv.example`. Fresh `zsh -c` and `zsh -lc`
checks both resolved `adb` and `emulator` under the intended SDK, each PATH
directory occurring once. The global JDK/SDK defaults and no-project-sourcing
behavior remain unchanged.

## Preserved safeguards and limits

Default cleanup preserves `local.properties`, shared Gradle/Xcode caches, AVDs,
tracked files, and other checkouts. Claims still exclude physical Android
devices, require worktree-prefixed created simulators, and use atomic ledger
replacement plus the common Git lock. Malformed sibling ledgers and contention
block mutation; peer claims preserve devices. SessionEnd budgets, detached
bounded cleanup, archive deadlines, offline setup, noclobber properties, hook
trust switches, environment precedence, local-first policy, CI permissions,
and read-only remote command behavior remain intact.

The run/rollback/cleanup changes are validated with fakes, including real ledger
integration in disposable repositories. No live `ios-run` or Android cold-start
cycle was performed; the Android 35 Google APIs ARM64 image is absent on this
host. If incarnation evidence is unavailable, cleanup preserves the device.
If attach returns a different device/helper, rollback never kills that returned
helper; recovery requires reviewed exact-device action. Helpers without matching
PID/start provenance are preserved even when a proven own boot can be stopped.

At the coordinator's request, AGENTS and `flint-dev` include Task H's bounded
iOS readiness and post-tap verification procedure. That procedure distinguishes
AX availability from input delivery; it does not claim a remedy for historical
late-tap failures. No product permission, anti-removal behavior, global Orca
setting, hook activation, remote write, push, or PR action was performed.

Implementation commits are `9a2187e` (literal settings and test isolation),
`91c457c` (recursive Make default), `412b45f` (cleanup safety and ownership), and
`acdcfce` (exact device transactions). The following documentation commit retains
this report and the command evidence. Both successful helper terminals and the
superseded stopped attempt were released through Orca; their worktree was kept.
