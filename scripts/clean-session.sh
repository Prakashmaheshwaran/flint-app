#!/usr/bin/env bash
# Genuine SessionEnd only. Queue bounded device release; preserve build outputs
# so a human continuing this checkout does not pay for a full rebuild.
FLINT_CLEAN_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)" || exit 0
source "$FLINT_CLEAN_ROOT/scripts/clean-env.sh" || true
flint_session_event=session-end
if [[ "${1:-}" == --start ]]; then
  flint_session_event=session-start
fi
if command -v python3 >/dev/null 2>&1; then
  python3 "$FLINT_CLEAN_ROOT/scripts/clean-worktree.py" --root "$FLINT_CLEAN_ROOT" "$flint_session_event" \
    || printf '%s\n' 'Session cleanup deferred; run make clean-devices before leaving the worktree.' >&2
else
  printf '%s\n' 'Session cleanup skipped: python3 unavailable.' >&2
fi
exit 0
