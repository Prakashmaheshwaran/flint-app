#!/usr/bin/env bash
# Only claims in this checkout's Git admin directory authorize device actions.
FLINT_CLEAN_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)" || exit 1
source "$FLINT_CLEAN_ROOT/scripts/clean-env.sh"
flint_cleanup_mode=devices
if [[ "${1:-}" == --orphans ]]; then
  flint_cleanup_mode=orphans
  shift
fi
exec python3 "$FLINT_CLEAN_ROOT/scripts/clean-worktree.py" --root "$FLINT_CLEAN_ROOT" "$flint_cleanup_mode" "$@"
