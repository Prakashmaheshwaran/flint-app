#!/usr/bin/env bash
set -euo pipefail
# shellcheck source=scripts/agent/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
git fetch --no-tags origin main
counts=$(git rev-list --left-right --count origin/main...HEAD)
read -r behind ahead <<< "$counts"
echo "Compared with origin/main: ahead=$ahead behind=$behind"
if git merge-base --is-ancestor origin/main HEAD; then
  echo 'origin/main is an ancestor of HEAD: yes'
else
  rc=$?
  if [[ $rc != 1 ]]; then exit "$rc"; fi
  echo 'origin/main is an ancestor of HEAD: no'
fi
