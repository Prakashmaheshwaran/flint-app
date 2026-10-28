#!/usr/bin/env bash
set -euo pipefail
# shellcheck source=scripts/agent/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
branch=$(git branch --show-current)
[[ -n "$branch" ]] || { echo 'Detached HEAD: check out a branch to inspect its PR.' >&2; exit 2; }
status=0
echo "== PR for $branch =="
gh pr view "$branch" || status=1
echo "== PR checks (pending/failed checks return nonzero) =="
gh pr checks "$branch" || status=1
echo "== Recent CI runs for $branch =="
gh run list --branch "$branch" --limit 10 || status=1
exit "$status"
