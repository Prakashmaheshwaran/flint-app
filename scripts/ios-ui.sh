#!/usr/bin/env bash
# UI fixtures only. Run on an empty, dedicated iOS simulator, never a personal device.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
[[ ${FLINT_UI_DISPOSABLE:-} == 1 ]] || {
  echo "UI fixtures write a synthetic PIN. Set FLINT_UI_DISPOSABLE=1 only for an empty, dedicated simulator; see docs/verification/ios-ui.md." >&2
  exit 2
}
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
else
  # Xcode's first two app launches failed on a cold hosted simulator.
  # Match local ios-run's boot readiness before starting any tests. These are
  # bounded system-state checks, never app launches or test/action retries.
  python3 - "$device" 2>&1 <<'PY' | tee "$results/simulator-readiness.log"
import importlib.util
import subprocess
import sys
import traceback

spec = importlib.util.spec_from_file_location("flint_device_run", "scripts/agent/device-run.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
device = sys.argv[1]
try:
    state = runner.ios_state(device)
    print(f"simulator={device} initial_state={state}", flush=True)
    if state == "Shutdown":
        print("boot_begin timeout=30", flush=True)
        runner.run(["xcrun", "simctl", "boot", device], timeout=30)
    print("bootstatus_begin timeout=180", flush=True)
    result = runner.run(["xcrun", "simctl", "bootstatus", device, "-b"], timeout=180)
    print(result.stdout, end="", flush=True)
    print("springboard_check timeout=30", flush=True)
    runner.springboard_ready(device, timeout=30)
    print("springboard_ready=true (running with positive PID)", flush=True)
except (ValueError, subprocess.TimeoutExpired, OSError) as error:
    print(f"preflight_failed: {error}", flush=True)
    traceback.print_exc()
    # Observe after failure without changing readiness or launching the app.
    # Keep exact query output so a missing service is distinguishable from
    # a service that exists but is not running. Diagnostics cannot resume tests.
    queries = [
        ["xcrun", "simctl", "spawn", device, "launchctl", "print", "system/com.apple.SpringBoard"],
        ["xcrun", "simctl", "spawn", device, "launchctl", "print", "system"],
        ["xcrun", "simctl", "list", "devices", "available", "--json"],
    ]
    for query in queries:
        print(f"post_failure_query={query!r}", flush=True)
        try:
            result = runner.run(query, timeout=10, check=False)
            print(f"exit={result.returncode}\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}", flush=True)
        except (ValueError, subprocess.TimeoutExpired, OSError) as diagnostic_error:
            print(f"diagnostic_failed: {diagnostic_error}", flush=True)
    sys.exit(1)
PY
fi

(cd "$ios_dir" && xcodegen generate)
set -o pipefail
xcodebuild test -project "$ios_dir/Flint.xcodeproj" -scheme FlintUI \
  -configuration Debug -derivedDataPath "$derived_data" \
  -destination "platform=iOS Simulator,id=$device" \
  -resultBundlePath "$results/FlintUI.xcresult" \
  -parallel-testing-enabled NO -maximum-concurrent-test-simulator-destinations 1 \
  -only-testing:FlintUITests -only-testing:FlintTests/FlintInsightsTests \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO 2>&1 | tee "$results/xcodebuild.log"
