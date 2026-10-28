#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=scripts/agent/common.sh
source "$here/common.sh"
android_dir=$(cd "${ANDROID_DIR:-android-app}" && pwd)
avd=${AVD:-flint-test}
bundle_id=com.flint.peakfocus

device_info=$(agent_device android "$avd")
IFS=$'\t' read -r serial avd_name incarnation <<< "$device_info"
# common.sh and Make share scripts/flint-env.sh; the ledger selects the same
# inherited Orca/Codex/Claude session identity as session-end cleanup.
"${FLINT_MAKE:-make}" android
apk="$android_dir/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$apk" ]] || { echo "Built APK not found: $apk" >&2; exit 1; }
agent_device verify-android "$serial" "$avd_name" "$incarnation"
agent_orca emulator install "$apk" --reinstall --device "$serial" --worktree "path:$PWD" --json
agent_device verify-android "$serial" "$avd_name" "$incarnation"
# Orca's activity-less launch uses Android monkey, which aborts on this AVD
# ("SYS_KEYS has no physical keys"). Target Flint's declared launcher directly.
agent_orca emulator launch "$bundle_id" --activity .MainActivity --device "$serial" --worktree "path:$PWD" --json
echo "Flint launched: device=$avd ($serial) bundle=$bundle_id"
