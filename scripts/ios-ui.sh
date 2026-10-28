#!/usr/bin/env bash
# UI fixtures only. Run on an empty, dedicated iOS simulator, never a personal device.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
# shellcheck source=scripts/agent/common.sh
source "$root/scripts/agent/common.sh"

simulator=${IOS_SIM:-iPhone 17}
ios_dir=${IOS_DIR:-ios-app}
derived_data=${IOS_DERIVED_DATA:-$ios_dir/DerivedData}
results=${IOS_UI_RESULTS:-$ios_dir/build/ios-ui/$(date -u +%Y%m%dT%H%M%SZ)-$$}
device=$(xcrun simctl list devices available --json | python3 scripts/agent/ios-device.py "$simulator")
mkdir -p "$results"
[[ ! -e "$results/FlintUI.xcresult" ]] || {
  echo "Result bundle already exists: $results/FlintUI.xcresult" >&2
  exit 2
}

{
  echo "commit=$(git rev-parse HEAD)"
  echo "simulator=$device"
  echo "fixture_only=true"
  echo "content_sizes=Large,AccessibilityXXXL"
  xcodebuild -version
  xcrun simctl list devices available
  xcrun simctl list runtimes
} > "$results/provenance.txt"

# Local device setup uses the same exact-identity and claim safeguards as ios-run.
# Hosted CI owns its entire disposable runner and does not have Orca installed.
if [[ ${CI:-false} != true ]]; then
  trap 'bash "$root/scripts/clean-devices.sh"' EXIT
  agent_device ios "$device"
fi

(cd "$ios_dir" && xcodegen generate)
set -o pipefail
xcodebuild test -project "$ios_dir/Flint.xcodeproj" -scheme FlintUI \
  -configuration Debug -derivedDataPath "$derived_data" \
  -destination "platform=iOS Simulator,id=$device" \
  -resultBundlePath "$results/FlintUI.xcresult" \
  -parallel-testing-enabled NO -maximum-concurrent-test-simulator-destinations 1 \
  -test-iterations 1 \
  -only-testing:FlintUITests -only-testing:FlintTests/FlintInsightsTests \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO 2>&1 | tee "$results/xcodebuild.log"
