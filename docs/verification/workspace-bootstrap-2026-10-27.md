# Flint workspace bootstrap and shell paths

Task A implementation and host evidence, 2026-10-27 (America/New_York).

## Path contract

`scripts/flint-env.sh` is the repository's only tool-path resolver. It is silent,
sourceable in Bash 3.2 and zsh, and changes only exported environment variables.
It does not install tools, write files, change directory, or change shell options.
From a checkout or its subdirectories, use `. ./scripts/flint-env.sh` (adjust the
relative path as needed); callers outside the checkout can set `FLINT_ENV_ROOT`.

| Value | First nonempty choice wins |
| --- | --- |
| Android SDK | `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `android-app/local.properties` `sdk.dir`, `$HOME/Library/Android/sdk` |
| JDK | `JAVA_HOME`, executable `/opt/homebrew/opt/openjdk@21`, `/usr/libexec/java_home -v 21` |
| Xcode | `DEVELOPER_DIR`, legacy `XCODE_DEV`, lexically last existing `/Applications/Xcode*.app/Contents/Developer` |

Both Android environment variables are exported with the selected SDK. Explicit
environment values win even when the directory is missing, so CI overrides and
intentional alternate installations are preserved. A missing JDK keeps the
Homebrew default visible in diagnostics; missing Xcode produces an empty path.
`sdk.dir` is read as single-line Java properties data, including Android Studio's
escaped spaces, backslashes and punctuation; it is never executed as shell code.
Unsupported Unicode/control escapes and continuation lines now fail explicitly;
setup rejects SDK paths containing control characters before creating the file.
A relative `sdk.dir` is relative to `android-app`.

The SDK platform-tools, emulator and cmdline-tools/latest/bin directories and the
JDK bin directory are prepended once; existing duplicate PATH entries are
removed. Homebrew's existing PATH provides XcodeGen and, on this host,
`sdkmanager`/`avdmanager` from `/opt/homebrew/bin`.

Orca itself resolves the SDK as `ANDROID_HOME` → `ANDROID_SDK_ROOT` → macOS
default. The added `local.properties` fallback applies only when neither SDK
environment variable exists. Source the resolver before directly launching
Orca/tools with a nondefault local SDK, or export `ANDROID_HOME` explicitly in
the environment that starts Orca. An already-running GUI process cannot inherit
exports from a later setup shell.

Gradle independently prefers `sdk.dir`, so an existing conflicting properties
file would otherwise defeat environment precedence. Android Make targets stop
with a specific diagnostic before invoking Gradle when those paths disagree;
they never overwrite the file. Reconcile an intentional SDK change explicitly.

## Make and setup

The Makefile uses `scripts/flint-shell.sh` for recipes and parse-time `$(shell)`
calls. That wrapper sources the resolver and execs `/bin/bash` with the original
arguments. It works with Apple's GNU Make 3.81, without `.SHELLFLAGS`, and
preserves exit status and `set -e`. Historical `$(JAVA_HOME)`/`$(XCODE_DEV)` aliases
remain available to archived harnesses. `make JAVA_HOME=... XCODE_DEV=...` still
works; the legacy command-line Xcode override maps to `DEVELOPER_DIR`.

`make env` prints resolved paths, source labels, directory existence, PATH and
command locations. `make doctor` is the same target. Both warn when inherited
Android/JDK paths differ from the checkout's fallback, making drift from a
generic shell configuration visible while retaining environment precedence.
Hosted CI workflows are unchanged and continue to invoke Gradle/Xcode directly.

`orca.yaml` invokes `bash scripts/orca/setup.sh`. The hook resolves paths, writes
`android-app/local.properties` only when absent and the resolved SDK exists,
runs `make ios-gen`, then prints `make env`. The write uses noclobber to protect
a concurrently created file. Reruns preserve every existing properties byte.
It performs no installation, network request, Gradle build, Xcode build or device
operation. It requires Make and XcodeGen because it promises a generated iOS
project; an unavailable Make invocation, XcodeGen or generation failure is
fatal. Missing SDK/JDK/Xcode and Android utility commands produce warnings so
an iOS-only checkout is not blocked by Android prerequisites.

The archive entry calls `scripts/orca/archive.sh` when it exists and prints a
successful skip otherwise. Task B owns that script. `issueCommand` directs
agents to `{{artifact_url}}`, AGENTS.md, the touched-platform gates and honest
verification reporting, without authorizing external actions.

## Shell evidence and decision

Before the change, both a real Orca terminal and the active Codex tool shell
could find XcodeGen but could not find `adb` or `emulator`. `JAVA_HOME` and both
Android environment variables were unset; `java` resolved to Apple's launcher.

Observed process/startup evidence:

- A newly created Orca human terminal reported `login=on interactive=on` and
  process `-/bin/zsh -l`.
- This Codex app-server's command subprocess reported `/bin/zsh -c`, with login
  and interactive modes off. `.zprofile` and `.zshrc` alone cannot cover it.
- Orca's generated wrapper at
  `~/Library/Application Support/orca/shell-wrappers/160553a7b6094dc8/shell-ready/zsh/.zshenv`
  restores the user's ZDOTDIR and explicitly sources `${ZDOTDIR-$HOME}/.zshenv`
  (lines 276–277 in the inspected build). No wrapper was edited.
- `~/.zprofile` adds Homebrew/local tools; `~/.zshrc` adds interactive tools.
  `/etc/zprofile` runs `path_helper`, which may put `/usr/bin/java` before a JDK
  bin path. With `JAVA_HOME` set, that launcher was verified to run JDK 21.
- `claude-multi` copies the inherited environment and prepends its CCR shim to
  PATH. A no-launch `os.execve` interception of its normal DeepSeek launch branch
  confirmed that it preserves `ANDROID_HOME`, `JAVA_HOME` and both Android PATH
  entries. This verifies the wrapper's handoff, not a live model/tool round trip.

A setup hook runs in a child process and cannot fix later terminals. The
coordinator declined a cwd-discovery `.zshenv` that would auto-source arbitrary
checkout code and approved a static, generic block instead. `~/.zshenv` was
confirmed absent before creation, so no previous file needed a backup. Only the
following approved block was added; no other dotfile, Orca setting or global
agent configuration was changed:

```sh
# >>> Flint toolchain paths (generic; the per-checkout source of truth is scripts/flint-env.sh) >>>
if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then ANDROID_HOME=$ANDROID_SDK_ROOT
  elif [ -d "$HOME/Library/Android/sdk" ]; then ANDROID_HOME=$HOME/Library/Android/sdk
  fi
fi
if [ -n "${ANDROID_HOME:-}" ]; then
  export ANDROID_HOME
  case ":$PATH:" in *":$ANDROID_HOME/emulator:"*) ;; *) PATH="$ANDROID_HOME/emulator:$PATH" ;; esac
  case ":$PATH:" in *":$ANDROID_HOME/platform-tools:"*) ;; *) PATH="$ANDROID_HOME/platform-tools:$PATH" ;; esac
  export PATH
fi
if [ -z "${JAVA_HOME:-}" ] && [ -x /opt/homebrew/opt/openjdk@21/bin/java ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21
fi
# <<< Flint toolchain paths <<<
```

The coordinator subsequently approved Task G to apply the independent PATH checks shown
above, with an exclusive backup of the previous block. The maintained copy is
`scripts/flint-zshenv.example`; `make env` reports either missing incoming PATH
entry independently. See the fixes report for the approval and fresh-shell proof.

Fresh `zsh -c`, `-lc`, `-ic` and `-lic` probes from a minimal environment all
resolved `adb`, `emulator`, `java` and `xcodegen`; `java -version` reported
OpenJDK 21.0.11 in every mode. Each mode's `bash -c` child inherited the correct
SDK/JDK. A new real Orca terminal reproduced those results. Existing long-lived
processes retain their old environment until restarted; repo Make commands
already work independently of that restart. The static block does not pin any
checkout or automatically execute repository code.

## User instructions: switch Orca to the committed hooks

1. Integrate these commits into Flint's main checkout and the branch used to
   create new worktrees. Setup reads each **new worktree's** `orca.yaml`; archive
   reads the **main checkout's** file.
2. In Orca, select Flint and open **Settings → Repository → Worktree Hooks**.
3. Change the command source from **local-only** (`commandSourcePolicy:
   local-only`) to the setting that uses the repository's committed `orca.yaml`.
   Remove the obsolete local setup override `pnpm install`; do not copy that
   command into the shared configuration. Confirm setup resolves to
   `bash scripts/orca/setup.sh` and archive to the guarded script in `orca.yaml`.
4. Save the repository settings, then create a fresh worktree with setup enabled
   (or explicitly rerun setup for an existing checkout).
5. When Orca asks to trust the shared scripts, review this checkout's committed
   `orca.yaml` and referenced scripts and approve them. Trust is keyed to the
   shared script content hash; later changes may prompt again. A refused or
   unanswered trust prompt means the hook has not run.
6. Confirm setup's final `make env` output, the generated
   `ios-app/Flint.xcodeproj`, and `android-app/local.properties`. Open a new
   terminal to pick up the generic shell paths; agents use the same environment.

This Orca version has no CLI for changing hook source policy. No GUI setting was
automated. The configuration switch and trust decision remain user actions.

## Verification record

- Resolver/Make selftest: 22 checks passed, including CI-style environment
  precedence, properties parsing, exact PATH de-duplication, idempotency,
  Bash/sh/zsh sourcing, GNU Make 3.81 aliases/overrides, status/errexit, missing
  tools, drift warnings and no build/device command execution.
- Setup selftest: seven cases passed in a temporary Git worktree with fake tools:
  repeatability, preserving existing properties, missing Android SDK, and a
  missing-XcodeGen failure, the fake-tool call-log assertion, bounded diagnostic
  dumping and a process-local fixture file-size limit. It never invokes a device
  command.
- Full `make selftest` passed: the above checks, 47 Android harness checks, 41
  release checks and eight Python unit tests.
- `make verify-android` passed: debug APK, development-signed release APK with
  local-first verification, and unit tests.
- Fresh-checkout acceptance passed at implementation commit `da72705` in
  `/tmp/flint-wsetup-a-20261027`: setup twice with both Android SDK variables
  unset, `make env` with both unset, `make android`, and `make ios-build`.
  Setup took 0.14 seconds and 0.11 seconds. The first run reported the macOS
  default SDK; the second and standalone diagnostic reported `local.properties`.
  All eight diagnostic commands resolved. Android reported `BUILD SUCCESSFUL`
  and Xcode reported `BUILD SUCCEEDED`.
- Both setup runs produced identical SHA-256 values:
  `local.properties` =
  `e230b977a7117ab7eb3e7291b494e5ef88ea6800d4cdaf3cb3f75ec44577bf9d`,
  generated `project.pbxproj` =
  `10b1a7f0fe220d362d96d8a05adc6a72f17be76926881eaf45d3a00207ec97da`.
- After inspecting its `info.plist`, only
  `~/Library/Developer/Xcode/DerivedData/Flint-fizvdllcktjbqcgpemimvamldbej`
  (176 MB, `WorkspacePath` beneath the scratch checkout) was removed. The owned
  scratch worktree was removed and its Git metadata pruned. Both paths were
  verified absent; the disk had 40 GiB free afterwards.
- ShellCheck passed for all six new shell scripts; Ruby's YAML parser validated
  `orca.yaml`, the setup command, URL token and absence of environment recipes.
- No simulator, emulator or physical-device behavior was tested by this worker.
  `verify-ios`/`ios-test` were not run because they boot a simulator; the approved
  iOS acceptance was the generic simulator build.

Successful acceptance logs are retained locally (Git-ignored) in
`docs/verification/workspace-bootstrap-logs/`: `selftest-guarded.log`,
`verify-android.log`, `scratch-setup-first.log`, `scratch-setup-second.log`,
`scratch-env.log`, `scratch-android.log`, `scratch-ios-build.log`, and
`scratch-cleanup.log`. The repository hook source/trust switch is the only
remaining user action; it was deliberately not automated.

During an early setup-test attempt, Apple's `/usr/bin/make` launcher rejected a
deliberately missing `DEVELOPER_DIR` via `xcrun` before generation. The setup test
now stubs Make as well as XcodeGen; real Make behavior is tested separately.
No test in this change invokes `simctl`, device shutdown/deletion or emulator
input. The coordinator received the command audit when it froze device work.

An early diagnostic-only EXIT trap also mistakenly read its own redirected
failure log. Its orphaned `cat` process was identified by exact command/PID and
stopped; the abandoned fixture log had grown to about 39 GB. That temporary trap
was replaced before any passing test run, and is absent from the committed
implementation. After the coordinator independently verified ownership and
approved cleanup, the exact fixture was removed and free space recovered from
130 MiB to 39 GiB. The 8192-byte evidence excerpt is retained outside the repo at
`~/flint-planning/2026-10-27/workspace-setup/evidence/lead-a-runaway-selftest-log-excerpt.log`.
The committed selftests cap fixture file sizes and dump at most 8192 bytes to a
diagnostic descriptor saved before redirection; regression tests verify that a
dump cannot append to its own input and that the file-size cap stops a writer.
No device operation was involved.
