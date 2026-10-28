#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=scripts/agent/common.sh
source "$here/common.sh"
android_dir=$(cd "${ANDROID_DIR:-android-app}" && pwd)
avd=${AVD:-flint-test}
bundle_id=com.flint.peakfocus

before=$(agent_orca emulator devices --json | python3 "$here/orca-device.py" "$avd")
IFS=$'\t' read -r _ state <<< "$before"
agent_orca emulator attach "$avd" --worktree "path:$PWD" --json
# A cold AVD has a name before attach and a real adb serial after it boots.
after=$(agent_orca emulator devices --json | python3 "$here/orca-device.py" "$avd")
IFS=$'\t' read -r serial after_state <<< "$after"
[[ "$after_state" == booted ]] || { echo "Android device $avd did not boot." >&2; exit 2; }
if [[ -x scripts/flint-devices.sh ]]; then
  # AVD may be an adb serial; the ledger requires its actual AVD name and
  # independently verifies that identity before accepting the claim.
  avd_info=$(adb -s "$serial" emu avd name)
  IFS= read -r avd_name <<< "$avd_info"
  [[ -n "$avd_name" ]] || { echo "Cannot resolve AVD name for $serial." >&2; exit 2; }
  claim=(claim android "$serial" --avd "$avd_name")
  if [[ "$state" == shutdown ]]; then claim+=(--booted); fi
  scripts/flint-devices.sh "${claim[@]}"
fi
# common.sh and Make share scripts/flint-env.sh; the ledger selects the same
# inherited Orca/Codex/Claude session identity as session-end cleanup.
"${FLINT_MAKE:-make}" android
apk="$android_dir/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$apk" ]] || { echo "Built APK not found: $apk" >&2; exit 1; }
agent_orca emulator install "$apk" --reinstall --device "$serial" --worktree "path:$PWD" --json
agent_orca emulator launch "$bundle_id" --device "$serial" --worktree "path:$PWD" --json
echo "Flint launched: device=$avd ($serial) bundle=$bundle_id"
