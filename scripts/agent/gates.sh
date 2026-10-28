#!/usr/bin/env bash
# Select exactly the path-based gates in AGENTS.md; never fetch or touch devices in dry-run.
set -euo pipefail
# shellcheck source=scripts/agent/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

base=$(git merge-base origin/main HEAD) || {
  echo "Cannot find the origin/main merge-base; fetch origin/main and retry." >&2
  exit 2
}
paths=$(mktemp "${TMPDIR:-/tmp}/flint-gates.XXXXXX")
trap 'rm -f "$paths"' EXIT
# Keep each source separate: staged edits can be reversed by unstaged edits. Disable
# rename detection so moving a file out of a platform still selects that platform.
{
  git diff --name-only --no-renames -z "$base" HEAD --
  git diff --name-only --no-renames -z --cached --
  git diff --name-only --no-renames -z --
  git ls-files --others --exclude-standard -z
} > "$paths"

ios=0 android=0 scripts=0
while IFS= read -r -d '' path; do
  case "$path" in
    ios-app/*) ios=1 ;;
    android-app/*) android=1 ;;
    scripts/*|Makefile) scripts=1 ;;
  esac
done < "$paths"

targets=(verify-ios verify-android selftest)
selected=("$ios" "$android" "$scripts")
reasons=('no ios-app/ changes' 'no android-app/ changes' 'no scripts/ or Makefile changes')
results=(skipped skipped skipped)
ran=0 passed=0 failed=0 skipped=0 planned=0
echo "AGENTS.md gates vs origin/main merge-base $base (including uncommitted paths)"
for i in 0 1 2; do
  if [[ ${selected[$i]} == 0 ]]; then
    skipped=$((skipped + 1))
  elif [[ ${DRY_RUN:-0} == 1 ]]; then
    echo "PLAN: make ${targets[$i]}"
    results[i]=planned
    planned=$((planned + 1))
  else
    echo "RUN: make ${targets[$i]}"
    ran=$((ran + 1))
    if "${FLINT_MAKE:-make}" "${targets[$i]}"; then
      results[i]=passed
      passed=$((passed + 1))
    else
      rc=$?
      results[i]="failed (exit $rc)"
      failed=$((failed + 1))
    fi
  fi
done

echo "Gate summary: ran=$ran passed=$passed failed=$failed skipped=$skipped planned=$planned"
for i in 0 1 2; do
  if [[ ${selected[$i]} == 0 ]]; then
    echo "  ${targets[$i]}: skipped (${reasons[$i]})"
  else
    echo "  ${targets[$i]}: ${results[$i]}"
  fi
done
[[ $failed == 0 ]]
