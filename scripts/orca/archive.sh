#!/usr/bin/env bash
# Orca runs archive in the departing checkout, before removal, with
# ORCA_WORKTREE_PATH set. The config can come from the main checkout, so prefer
# the explicit target and never accidentally clean ORCA_ROOT_PATH.
# This script must tolerate Orca's `set -e` runner and missing optional tools.
FLINT_CLEAN_ROOT="${ORCA_WORKTREE_PATH:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)}"
if [[ ! -d "$FLINT_CLEAN_ROOT" ]]; then
  printf 'Archive cleanup: worktree already absent: %s\n' "$FLINT_CLEAN_ROOT"
  exit 0
fi
if [[ ! -f "$FLINT_CLEAN_ROOT/scripts/clean-worktree.py" ]]; then
  printf 'Archive cleanup: cleanup helper absent in %s; skipped.\n' "$FLINT_CLEAN_ROOT"
  exit 0
fi
if [[ -f "$FLINT_CLEAN_ROOT/scripts/clean-env.sh" ]]; then
  source "$FLINT_CLEAN_ROOT/scripts/clean-env.sh" || true
fi
if ! command -v python3 >/dev/null 2>&1; then
  printf '%s\n' 'Archive cleanup: python3 unavailable; skipped.'
  exit 0
fi
printf 'Archive cleanup: target=%s cwd=%s\n' "$FLINT_CLEAN_ROOT" "$PWD"
# Same operations as make clean-devices + make clean, without needing make,
# Gradle, Xcode, or a generated project. One 100-second budget stays below 2 min.
python3 "$FLINT_CLEAN_ROOT/scripts/clean-worktree.py" --root "$FLINT_CLEAN_ROOT" --deadline 100 archive \
  --derived-data "${IOS_DERIVED_DATA:-ios-app/DerivedData}" \
  || printf '%s\n' 'Archive cleanup: some resources deferred; removal may continue.'
exit 0
