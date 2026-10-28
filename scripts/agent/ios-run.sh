#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=scripts/agent/common.sh
source "$here/common.sh"
ios_dir=${IOS_DIR:-ios-app}
derived_data=${IOS_DERIVED_DATA:-$ios_dir/DerivedData}
simulator=${IOS_SIM:-iPhone 17}
bundle_id=com.flint.peakfocus
device_info=$(xcrun simctl list devices available --json | python3 "$here/ios-device.py" "$simulator" --state)
IFS=$'\t' read -r device state <<< "$device_info"
case "$state" in
  Booted|Shutdown) ;;
  *) echo "Simulator $device is in state $state; wait for it to settle and retry." >&2; exit 2 ;;
esac

xcodebuild -project "$ios_dir/Flint.xcodeproj" -scheme Flint \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "$derived_data" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
app="$derived_data/Build/Products/Debug-iphonesimulator/Flint.app"
[[ -d "$app" ]] || { echo "Built app not found: $app" >&2; exit 1; }
# Fresh state, explicit boot proof and returned attach identity are checked after
# the build. Always attach the exact resolved UDID, never Orca's fuzzy name match.
agent_device ios "$device"
xcrun simctl install "$device" "$app"
xcrun simctl launch "$device" "$bundle_id"
echo "Flint launched: device=$simulator ($device) bundle=$bundle_id"
