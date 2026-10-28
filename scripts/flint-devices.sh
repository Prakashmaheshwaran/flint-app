#!/usr/bin/env bash
# claim <ios|android> <udid|serial> [--booted] [--created] [--avd <name>]
# --booted means THIS worktree started a previously stopped device, not merely
# attached to an already running one. --created is required for simulator deletion.
# Android claims require --avd; no command here ever deletes or wipes AVD data.
# release <platform> <id> only forgets a claim. list --json and prefix are read-only.
FLINT_CLEAN_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)" || exit 1
source "$FLINT_CLEAN_ROOT/scripts/clean-env.sh"
exec python3 "$FLINT_CLEAN_ROOT/scripts/clean-worktree.py" --root "$FLINT_CLEAN_ROOT" "$@"
