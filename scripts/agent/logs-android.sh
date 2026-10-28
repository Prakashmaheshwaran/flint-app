#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=scripts/agent/common.sh
source "$here/common.sh"
exec python3 "$here/logs-android.py"
