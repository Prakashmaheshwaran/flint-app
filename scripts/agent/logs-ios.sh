#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=scripts/agent/common.sh
source "$here/common.sh"
simulator=${IOS_SIM:-iPhone 17}
device=$(xcrun simctl list devices available --json | python3 "$here/ios-device.py" "$simulator")
echo "Flint logs: device=$simulator ($device) bundle=com.flint.peakfocus"
exec xcrun simctl spawn "$device" log stream --level debug --style compact --predicate 'process == "Flint"'
