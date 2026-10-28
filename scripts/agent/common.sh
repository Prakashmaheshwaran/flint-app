#!/usr/bin/env bash
# Reuse the shared environment when available; do not maintain another env layer.
agent_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [[ -f "$agent_dir/../flint-env.sh" ]]; then
  # shellcheck source=/dev/null
  source "$agent_dir/../flint-env.sh"
fi
agent_orca() { "${ORCA_CLI_COMMAND:-orca}" "$@"; }
