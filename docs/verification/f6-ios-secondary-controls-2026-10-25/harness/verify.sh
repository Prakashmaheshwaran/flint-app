#!/bin/bash
# Execute a per-run frozen copy of this file; never edit a running script.
set -euo pipefail
cd /Users/prakash/.codex/worktrees/f6-insights-recovery/flint
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
f6_out=${1:?absolute frozen run directory}
f6_expected=${2:?exact merged source commit}
f6_device=3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473
test -d "$f6_out/ios-ui/Tests"
test ! -e "$f6_out/sequence.log"
exec > "$f6_out/sequence.log" 2>&1

cleanup() {
    f6_exit=$?
    trap - EXIT
    set +e
    xcrun simctl ui "$f6_device" content_size large > "$f6_out/restore-large.log" 2>&1
    f6_restore=$?
    xcrun simctl terminate "$f6_device" com.flint.peakfocus > "$f6_out/terminate.log" 2>&1
    xcrun simctl launch "$f6_device" com.flint.peakfocus > "$f6_out/normal-launch.log" 2>&1
    f6_launch=$?
    xcrun simctl ui "$f6_device" content_size > "$f6_out/final-content-size.txt" 2>&1
    f6_readback=$?
    python3 "$f6_out/check_pin_baseline.py" final "$f6_out/pin-baseline.json"
    f6_pin=$?
    xcrun simctl list devices booted -j > "$f6_out/devices-after.json"
    sleep 2
    xcrun simctl io "$f6_device" screenshot "$f6_out/cleanup-large.png" > "$f6_out/cleanup-screenshot.log" 2>&1
    f6_screenshot=$?
    python3 - "$f6_out" "$f6_exit" "$f6_restore" "$f6_launch" "$f6_readback" "$f6_pin" "$f6_screenshot" <<'PY'
from pathlib import Path
import datetime, json, sys
out = Path(sys.argv[1])
keys = ["sequenceExit", "restoreLargeExit", "normalLaunchExit", "readbackExit", "PINCheckExit", "screenshotExit"]
record = dict(zip(keys, map(int, sys.argv[2:])))
record["finalContentSize"] = (out / "final-content-size.txt").read_text().strip()
record["normalLaunchUsedPreviewArguments"] = False
record["completedAt"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
(out / "cleanup.json").write_text(json.dumps(record, indent=2) + "\n")
PY
    if [ "$f6_restore" -ne 0 ] || [ "$f6_launch" -ne 0 ] || [ "$f6_readback" -ne 0 ] || [ "$f6_pin" -ne 0 ]; then
        f6_exit=1
    fi
    date -u
    exit "$f6_exit"
}

date -u
test "$(git rev-parse HEAD)" = "$f6_expected"
git rev-parse HEAD > "$f6_out/product-sha.txt"
git diff --exit-code
git diff --cached --exit-code
xcodebuild -version > "$f6_out/toolchain.txt"
xcrun simctl list devices available -j > "$f6_out/devices-before.json"
xcrun simctl list runtimes -j > "$f6_out/runtimes.json"
python3 - "$f6_out/devices-before.json" <<'PY'
import json, sys
devices = [device for runtime in json.load(open(sys.argv[1]))["devices"].values() for device in runtime]
target = "3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473"
assert any(device["udid"] == target for device in devices), "Authorized guest is absent"
assert [device["udid"] for device in devices if device["state"] == "Booted"] == [target], "Expected only the authorized guest to be booted"
PY
python3 "$f6_out/check_pin_baseline.py" initial "$f6_out/pin-baseline.json"
xcrun simctl ui "$f6_device" content_size > "$f6_out/initial-content-size.txt"
test "$(cat "$f6_out/initial-content-size.txt")" = large
trap cleanup EXIT

make -f Makefile -f "$f6_out/destination.mk" verify-ios \
    F6_REVIEW_ROOT="$f6_out" F6_GATE_RESULT="$f6_out/gate.xcresult" > "$f6_out/verify-ios.log" 2>&1
xcrun xcresulttool get test-results summary --path "$f6_out/gate.xcresult" > "$f6_out/gate-summary.json"
f6_app="$f6_out/DerivedData/Build/Products/Debug-iphonesimulator/Flint.app"
test -d "$f6_app"
shasum -a 256 "$f6_app/Flint" "$f6_app/Flint.debug.dylib" > "$f6_out/app-sha256.txt"
xcrun simctl terminate "$f6_device" com.flint.peakfocus || true
xcrun simctl install "$f6_device" "$f6_app"
python3 "$f6_out/check_pin_baseline.py" installed "$f6_out/pin-baseline.json"
xcodegen generate --spec "$f6_out/ios-ui/project.yml" --project "$f6_out/ios-ui" > "$f6_out/ui-project.log" 2>&1
for f6_size in large accessibility-extra-extra-extra-large; do
    xcrun simctl ui "$f6_device" content_size "$f6_size"
    xcrun simctl ui "$f6_device" content_size > "$f6_out/$f6_size-content-size.txt"
    xcodebuild test -project "$f6_out/ios-ui/FlintF6SecondaryUI.xcodeproj" -scheme FlintF6SecondaryUI \
        -destination "platform=iOS Simulator,id=$f6_device" -derivedDataPath "$f6_out/UIDerivedData" \
        -resultBundlePath "$f6_out/$f6_size.xcresult" \
        -parallel-testing-enabled NO -maximum-concurrent-test-simulator-destinations 1 \
        -collect-test-diagnostics never CODE_SIGNING_ALLOWED=NO > "$f6_out/$f6_size.log" 2>&1
    xcrun xcresulttool get test-results summary --path "$f6_out/$f6_size.xcresult" > "$f6_out/$f6_size-summary.json"
done
date -u
