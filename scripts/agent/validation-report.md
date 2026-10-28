# Task C: agent command surface

Implemented `gates`, `ios-run`, `android-run`, `logs-ios`, `logs-android`,
`pr-status`, and `ancestry`, with help entries and thin Make recipes. Added the
project `flint-dev` skill, its relative Claude symlink, and the AGENTS.md command
table. Existing build/clean recipes and product code are unchanged.

## Verification

- `make selftest`: 47 Android helper checks, 41 release-version checks, 8 existing
  Python tests, and 18 new agent integration tests pass without native tools.
- Gate coverage includes iOS-only, Android-only, scripts-only, Makefile-only,
  both platforms, both platforms plus scripts, and docs-only; committed, staged,
  unstaged, untracked, renamed and deleted paths; failure continuation and dry-run.
- Fake-tool integration covers build/attach/install/launch composition, exact
  device targeting, before-attach boot state and ledger claims, PID-filtered
  Android logs and SDK fallback, iOS process logs, and read-only gh/git commands.
  Each fake invocation records its resolved binary path and is checked against
  the self-contained fake implementation.
- `make help` lists all seven new targets. On this branch, `make gates DRY_RUN=1`
  plans only `selftest`, with reasons for skipping both platform gates.
- ShellCheck and `git diff --check` pass. Skill frontmatter parses as YAML and
  `.claude/skills/flint-dev` resolves to `.agents/skills/flint-dev`. The bundled
  skill validator could not run because its Python environment lacks PyYAML;
  Ruby's YAML parser verified the required fields instead.

## Integration limits

No real device, simulator, native build, live PR query, or remote write was used.
Android Orca JSON fixtures are provisional: the authorized verifier's attach
timed out before it captured samples. The isolated device/log parsers and their
fixtures need confirmation against the installed Orca responses in wave 2;
unknown device state fails before attach, and Android log parsing has an SDK
adb fallback. Real iOS/Android run and logging behavior remains unverified.

The shared environment wrapper and `clean-devices` target are provided by other
leads. Direct shell entrypoints source `scripts/flint-env.sh` when present; run
commands call `scripts/flint-devices.sh` when present and record `--booted` only
after a previously shut-down device was attached. No device creation/deletion
or cleanup implementation is added here.

Local evidence is retained under ignored `ios-app/DerivedData/` directories:
`agent-command-evidence-verified/selftest.log` and per-test `events.jsonl` /
`binary-paths.jsonl` are the final run; `agent-command-evidence/` contains the
incident command audit and an earlier evidence run. The latter exposed an
existing release-test assumption about non-Git fixtures when nested inside a
checkout; final evidence runs set `GIT_CEILING_DIRECTORIES` to `ios-app/DerivedData`
so fixtures do not discover the parent repository. A plain `make selftest` also
passed before the evidence-preserving runs.
