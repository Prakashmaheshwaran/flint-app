#!/usr/bin/env bash
# claim <ios|android> <udid|serial> [--booted --incarnation TOKEN] [--created] [--avd NAME]
# --booted means THIS worktree started a previously stopped device, not merely
# attached to an already running one. --created is required for simulator deletion.
# Android claims require --avd; no command here ever deletes or wipes AVD data.
# incarnation <ios|android> ID [--avd NAME] [--pid PID|--process-group PGID]
# prints a verified boot token; inability to prove the exact instance fails closed.
# A token proves identity, not ownership: --booted additionally asserts this
# invocation successfully booted it. Never infer that from before/after snapshots.
# release <platform> <id> only forgets a claim. list --json and prefix are read-only.
FLINT_CLEAN_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)" || exit 1
source "$FLINT_CLEAN_ROOT/scripts/clean-env.sh"
exec python3 "$FLINT_CLEAN_ROOT/scripts/clean-worktree.py" --root "$FLINT_CLEAN_ROOT" "$@"
