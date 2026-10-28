# Worktree resource cleanup

Task B, 2026-10-27; branch `Prakashmaheshwaran/ws-cleanup`.
Implementation commit: `f1e0793`.

Implemented and verified cleanup for worktree artifacts, explicit device claims,
archive, and project session-end hooks. Native builds passed; actual cleanup
removed 838,475,776 bytes of worktree artifacts and, with separate coordinator
approval, 2,656,501,760 bytes of orphan host DerivedData. No live device was
mutated. Exact commands, timestamps, status snapshots, and disk measurements are
in [the execution evidence](workspace-cleanup-evidence-2026-10-27.txt).

## Policy and boundaries

`make clean` removes native build products and project caches directly, without
running Gradle. This includes generated Xcode projects/workspaces, worktree
DerivedData, nested SwiftPM `.build`/`.swiftpm`, Android module `build`, `.gradle`,
`.kotlin`, `.cxx`, `.externalNativeBuild`, result bundles, and script bytecode.
It preserves `local.properties`, tracked source, shared caches, and device claims.
Symlinked or outside-worktree artifact paths are refused. `IOS_DERIVED_DATA`
defaults to `ios-app/DerivedData`; both Makefile build/test invocations and both
iOS CI invocations now set `-derivedDataPath` to maintain parity.

`make clean-devices` acts only on explicit claims. Inside an agent it selects that
agent's claimant ID; without an agent ID it releases this worktree's claims.
`DRY_RUN=1` previews commands. The archive hook releases all sessions' claims and
then calls the same artifact cleanup routine, within one 100-second budget.
Its target is `ORCA_WORKTREE_PATH` when provided, never `ORCA_ROOT_PATH`; absent
tools are logged and do not prevent removal. No AVD data is deleted or wiped.

Claim API: `scripts/flint-devices.sh claim <ios|android> <id> [--session ID]
[--booted] [--created] [--avd NAME]`; `release <platform> <id> [--session ID]` only
forgets a claim. `list --json` inspects the ledger, and `prefix` prints the required
name prefix for created iOS simulators. `--booted` attests that the worktree
started a previously stopped device; merely attaching does not grant shutdown
rights. `--created` authorizes deletion only if the actual simulator name matches
this checkout's prefix. Android serials are checked against the recorded AVD
name before acting, preventing serial-reuse mistakes and physical-device kills.

Claims are stored at `git rev-parse --git-path flint-cleanup/devices.json`, with
the canonical worktree path and claimant ID. A Python `fcntl.flock` lock in the
Git common directory serializes all sibling claim and cleanup operations; it
needs no external `flock` command and is automatically released on process exit.
Contention and invalid sibling ledgers fail closed. Other worktrees' claims and
other sessions in the same worktree prevent device actions. Local remaining
claimants inherit worktree boot/creation provenance; cross-worktree sharing
releases only this worktree's claim and preserves the shared device.

`make clean-orphans` defaults to a dry run. `CONFIRM=1` authorizes only the printed
classes: `Flint-*` shared DerivedData whose recorded checkout no longer exists,
and stopped, unclaimed legacy simulators matching exactly `flint-review-[0-9]+`.
An absent generated `.xcodeproj` does not make a live checkout an orphan. Existing
checkout roots and Git/Orca worktree inventories protect live checkouts. There is
no blanket `simctl delete unavailable`, `shutdown all`, `erase`, AVD deletion,
shared Gradle-cache eviction, or process-name kill.

## Orca ownership limitation and deliberate fallback

Read-only inspection of Orca 1.4.241's shipped `out/main/index.js` established:
`emulatorList` ignores its worktree parameter and delegates to the iOS backend's
global `serve-sim --list`. The documented CLI `kill`/`shutdown` path resolves an
explicit device without checking worktree ownership and clears all registry
references for that device. Therefore `emulator list --worktree active` cannot
establish ownership, and its output is diagnostic only in this implementation.

The runtime contains an internal `emulator.shutdown` `managedOnly: true` path
that retains devices referenced by another worktree, but the shipped CLI does
not expose that flag. The coordinator explicitly rejected an adapter to this
private interface and authorized the explicit ledger fallback and C's run
commands to claim devices after attach. No Orca internals/configs were modified.
This is a candidate upstream CLI issue; nothing was filed externally.

Shipped archive code uses `cwd: worktreePath` and exports `ORCA_WORKTREE_PATH` to
that same path; archive configuration can be read from the main checkout before
removal. This corroborates using the explicit target in `scripts/orca/archive.sh`.
The live repository hook-source setting still needs the user's setup change
described by Lead A; this task does not modify global Orca settings.

## Agent session end

Installed versions inspected: Codex CLI 0.162.1 and Claude Code 2.1.312.
Codex reports `hooks stable true`; a read-only configuration-parser probe accepted
`SessionEnd` and rejected a command handler missing `command`. Both agents support
project `SessionEnd` hooks. Codex's limit is three seconds, so the hook starts a
detached child with closed inherited pipes and a 55-second cleanup budget.
The child releases only the ending session's claims and logs timestamps and exact
commands under that worktree's Git admin directory. `Stop` is not used; `notify`
is not a substitute for session termination. See the
[official Codex hooks documentation](https://learn.chatgpt.com/docs/hooks) and
[Claude hooks reference](https://code.claude.com/docs/en/hooks#sessionend).

Claude's complementary SessionStart hook only appends its session ID to the
documented `CLAUDE_ENV_FILE` as `FLINT_DEVICE_SESSION_ID`; it performs no cleanup
and preserves other hooks' exports. Missing env-file input is a no-op. This lets
later commands claim devices for the same identity delivered in SessionEnd stdin.
Orca handle/pane IDs and Codex's thread ID take precedence when present. Unknown
session identities do not authorize automatic device release. The detached log
is capped at 1 MiB, and fake selftest call logs at 256 KiB.

The project hook declarations coexist with Orca's existing handlers; global
`~/.codex`, `~/.claude`, Orca hook scripts, and trust state were not changed.
**One-time user step:** in a Codex CLI session opened in this trusted project,
run `/hooks`, inspect the project `.codex/hooks.json` SessionEnd command, and trust
that definition. Changed hook definitions require review again. Until trusted,
Codex skips the hook; the explicit AGENTS.md cleanup rule and archive hook remain
the fallbacks. Hard process kills may also bypass SessionEnd.

Build products are retained at session end so a human continuing the worktree
does not need a full rebuild. Device resources are released at agent end, and
all worktree build space is reclaimed when the worktree is archived/removed.

## Verification record

Initial free space: 28,244,724 KiB, measured before any B builds or cleanup.
No existing device was booted, shut down, killed, or deleted by Task B.
The initial device inventory/help calls were read-only (00:12 UTC); the only
helper was a read-only hook researcher, completed/released at 00:19:37 UTC.

The first fake-only suite ran for 24 seconds. Two filesystem cleanup tests passed;
six device cases reached a teardown assertion that needed canonicalizing macOS
`/var` to `/private/var` (fixed). The remaining ten fixtures failed as the host ran
out of disk space. That run used a temporary repo, HOME, SDK, and an isolated PATH
containing only fake device executables and explicitly linked non-device tools;
it could not fall through to real devices. Its approximately 2.1 MiB of fixtures
and command logs were retained during the incident. At the coordinator's request,
new tests/builds stopped; B had no native build outputs, downloads, or scratch
worktrees. Host disk growth was external to this task.

After the coordinator lifted the disk freeze, the following gates passed:

- Cleanup suite: 20 tests, including fake ownership, dry runs, idempotency,
  missing tools, archive target selection, and detached session scoping.
- `make selftest`: 47 Android-verifier checks, 41 release-version checks,
  and 28 Python tests (including those 20 cleanup tests).
- `make ios-build`: `BUILD SUCCEEDED`, generic iOS Simulator destination,
  all build products/intermediates under this worktree's `ios-app/DerivedData`.
- `ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" make verify-android`:
  debug APK, release APK/local-first check, and all Gradle unit-test tasks passed.
  Some tasks reused the existing shared Gradle build cache; no emulator was used.
- `git diff --check`: clean.

Before the native builds, free disk space was 41,283,048 KiB. After both builds it
was 40,711,404 KiB. The worktree contained 838,475,776 allocated bytes of artifacts
(558,524 KiB DerivedData, 76 KiB generated Xcode project, 20,776 KiB Android project
cache, and 239,448 KiB Android module/root builds). Disk free space is a host-wide
measurement, so other agents' activity can change it independently of this task.

`git status --porcelain --ignored` after both builds showed the implementation
edits/untracked new source files and these ignored build paths:

```text
!! android-app/.gradle/
!! android-app/app/build/
!! android-app/blocking/blocking-accessibility/build/
!! android-app/blocking/blocking-engine/build/
!! android-app/blocking/blocking-overlay/build/
!! android-app/blocking/blocking-resilience/build/
!! android-app/blocking/blocking-usagestats/build/
!! android-app/build/
!! android-app/core/core-common/build/
!! android-app/core/core-data/build/
!! android-app/core/core-datastore/build/
!! android-app/core/core-model/build/
!! android-app/feature/feature-blocklist/build/
!! android-app/feature/feature-blockscreen/build/
!! android-app/feature/feature-connected/build/
!! android-app/feature/feature-onboarding/build/
!! android-app/feature/feature-settings/build/
!! android-app/feature/feature-stats/build/
!! android-app/permissions/permissions-special/build/
!! ios-app/DerivedData/
!! ios-app/Flint.xcodeproj/
```

`env -u CONFIRM -u DRY_RUN make clean-devices DRY_RUN=1` printed:
`No claimed devices; unclaimed devices/helpers are left alone.`

With explicit coordinator approval, `make clean` then removed 838,475,776 bytes
in 5.115 seconds. The second run removed zero bytes in 0.098 seconds. The subsequent
`git status --porcelain --ignored` showed only the implementation/report edits;
every ignored build path above was gone. Free space immediately before/after
these runs was 40,452,908 / 41,113,328 KiB. `local.properties` was absent in this
checkout (the builds used the SDK environment); its preservation is fake-tested.

Approved `make clean-devices` exited zero with no claimed devices. The archive
smoke check, with `ORCA_WORKTREE_PATH` explicitly set to this checkout, reported
the correct target/cwd, no claims, zero remaining artifact bytes, and exit zero
in 0.145 seconds. Fake tests also cover archive from a different cwd, all session
claims, missing tools, an already removed checkout, and the execution deadline.

No live hook lifecycle or simulator/emulator ownership mutation test is claimed
verified; those paths were exercised with fake tools only. `make ios-test` /
`verify-ios` were not run because they would boot a shared simulator. Project hook
trust and the repository's Orca hook-source setting require the user's integration
step. If a required helper stop cannot be verified, device cleanup retains its
claim and defers shutdown/deletion for a later retry.

## Host orphan preview and approved deletion

The approved `env -u CONFIRM -u DRY_RUN make clean-orphans` dry run listed 15 stale
DerivedData directories totaling 2,656,501,760 bytes (2.47 GiB). The live main
checkout was retained; `palolo`, `manatee`,
`emu-skill-verify`, `ws-agent-commands`, `ws-cleanup`, `ws-env-bootstrap`, and the
current Flint dependency-audit worktree were present in Git/Orca's live inventory
and had no matching orphan candidates. `flint-review-61` was absent from the
current simulator inventory, so there was no simulator candidate to size or
delete. The strict name rule is covered by fake tests with that exact name.

Full output:

```text
DRY RUN (use make clean-orphans CONFIRM=1 to delete listed candidates)
WOULD REMOVE /Users/prakash/Library/Developer/Xcode/DerivedData/Flint-amntpsdwnkbrdhfbrsppktamtzvo 157.2 MiB (164880384 bytes) workspace=/Users/prakash/.local/share/opencode/worktree/501dae/review-pr-62/ios-app/Flint.xcodeproj
WOULD REMOVE /Users/prakash/Library/Developer/Xcode/DerivedData/Flint-auklkoyyokpfgaafcxdddawhryuc 163.1 MiB (171044864 bytes) workspace=/Users/prakash/.codex/worktrees/a4a2/flint/ios-app/Flint.xcodeproj
WOULD REMOVE /Users/prakash/Library/Developer/Xcode/DerivedData/Flint-bgytuxhlhrvbbtbpgxyitwpbxymo 160.1 MiB (167858176 bytes) workspace=/tmp/flint-fix/pr-67/ios-app/Flint.xcodeproj
WOULD REMOVE /Users/prakash/Library/Developer/Xcode/DerivedData/Flint-bkknmgokvnvdkyfvmmnbnseohfiw 154.2 MiB (161738752 bytes) workspace=/Users/prakash/.codex/worktrees/b3b6/flint/ios-app/Flint.xcodeproj
WOULD REMOVE /Users/prakash/Library/Developer/Xcode/DerivedData/Flint-boeunbaleqpaxtbesjkdpnbcnpzr 168.2 MiB (176418816 bytes) workspace=/Users/prakash/.codex/worktrees/b2f6/flint/ios-app/Flint.xcodeproj
WOULD REMOVE /Users/prakash/Library/Developer/Xcode/DerivedData/Flint-btzdwezpmfffzwcgjjxbkusuutin 158.0 MiB (165715968 bytes) workspace=/tmp/flint-fix/pr-65/ios-app/Flint.xcodeproj
WOULD REMOVE /Users/prakash/Library/Developer/Xcode/DerivedData/Flint-cntarviknofepxezullqsrrpadpk 179.8 MiB (188506112 bytes) workspace=/Users/prakash/.codex/worktrees/f5-permission-errors-recovery/flint/ios-app/Flint.xcodeproj
KEEP live/unknown checkout Flint-cvbfthlglgmasrewlpycelpedxwo: /Users/prakash/Desktop/open-source/flint
WOULD REMOVE /Users/prakash/Library/Developer/Xcode/DerivedData/Flint-cxhcgtiaoyydggexgubwstlcfkkb 90.5 MiB (94867456 bytes) workspace=/Users/prakash/.local/share/opencode/worktree/501dae/review-pr-61/ios-app/Flint.xcodeproj
WOULD REMOVE /Users/prakash/Library/Developer/Xcode/DerivedData/Flint-dlftjpwczqiifqftizdcmysrwafp 321.7 MiB (337350656 bytes) workspace=/tmp/flint-fix/pr-61/ios-app/Flint.xcodeproj
WOULD REMOVE /Users/prakash/Library/Developer/Xcode/DerivedData/Flint-eadfnfrqofwjeyawvgwrbjwwrdvo 125.8 MiB (131911680 bytes) workspace=/tmp/flint-branch-audit-2026-10-23/main/ios-app/Flint.xcodeproj
WOULD REMOVE /Users/prakash/Library/Developer/Xcode/DerivedData/Flint-eepljttjkdvkprfeoqplddtjgxqd 167.2 MiB (175271936 bytes) workspace=/Users/prakash/orca/workspaces/flint/.fix/pr-67/ios-app/Flint.xcodeproj
WOULD REMOVE /Users/prakash/Library/Developer/Xcode/DerivedData/Flint-fggdlgegqjbjefbfkflrgdeztfpm 164.9 MiB (172941312 bytes) workspace=/Users/prakash/.codex/worktrees/6b27/flint/ios-app/Flint.xcodeproj
WOULD REMOVE /Users/prakash/Library/Developer/Xcode/DerivedData/Flint-fofnntawxbsmewexzbhfyzkilhen 169.8 MiB (178032640 bytes) workspace=/Users/prakash/.codex/worktrees/f3-pin-validation/flint/ios-app/Flint.xcodeproj
WOULD REMOVE /Users/prakash/Library/Developer/Xcode/DerivedData/Flint-gqiyptjhetymvmeghvgicwqcbcrk 165.8 MiB (173875200 bytes) workspace=/Users/prakash/.local/share/opencode/worktree/501dae/review-pr-67/ios-app/Flint.xcodeproj
WOULD REMOVE /Users/prakash/Library/Developer/Xcode/DerivedData/Flint-gzilznchzdewhiarzmllmrocytev 187.0 MiB (196087808 bytes) workspace=/Users/prakash/.codex/worktrees/f4-accessible-text-layouts-recovery/flint/ios-app/Flint.xcodeproj
```

After independently checking these workspaces, the coordinator explicitly
approved `env -u DRY_RUN make clean-orphans CONFIRM=1`, conditional on an immediate
repeat preview matching exactly those 15 folder names and zero simulator
candidates. The check matched, and deletion completed in 28.844 seconds. Only
`Flint-cvbfthlglgmasrewlpycelpedxwo` remained (170,496 KiB, main checkout). Free disk
space before/after this host cleanup was 41,460,308 / 43,993,852 KiB (41.96 GiB
free afterward). The full approved commands and outputs are in the evidence file.

Implementation changes are confined to Task B's cleanup surface; Lead A's
`orca.yaml`/setup/environment work and C's agent workflow commands are integrated
by the coordinator. No global hook settings, global agent config, remote Git
state, AVD data, or live simulator/emulator was changed by this task.
