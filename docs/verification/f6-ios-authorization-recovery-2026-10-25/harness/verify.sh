#!/bin/bash
set -euo pipefail
cd /Users/prakash/.codex/worktrees/f6-insights-recovery/flint
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
f6_root=/tmp/flint-fixes-20261024/F6/review-followup-2026-10-25
f6_device=3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473
f6_run=${1:?unique run label}
f6_mode=${2:-all}
f6_out="$f6_root/$f6_run"
mkdir "$f6_out"
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
    python3 "$f6_root/check_pin_baseline.py" final "$f6_out/pin-baseline.json"
    f6_pin=$?
    xcrun simctl list devices booted -j > "$f6_out/devices-after.json"
    xcrun simctl io "$f6_device" screenshot "$f6_out/cleanup-large.png"
    python3 - "$f6_out" "$f6_exit" "$f6_restore" "$f6_launch" "$f6_readback" "$f6_pin" <<'PY'
from pathlib import Path
import json, sys
out = Path(sys.argv[1])
record = dict(zip(["sequenceExit", "restoreLargeExit", "normalLaunchExit", "readbackExit", "PINCheckExit"], map(int, sys.argv[2:])))
record["finalContentSize"] = (out / "final-content-size.txt").read_text().strip()
record["fixtureLaunchStateCleared"] = record["normalLaunchExit"] == 0
(out / "cleanup.json").write_text(json.dumps(record, indent=2) + "\n")
PY
    if [ "$f6_restore" -ne 0 ] || [ "$f6_launch" -ne 0 ] || [ "$f6_readback" -ne 0 ] || [ "$f6_pin" -ne 0 ]; then
        f6_exit=1
    fi
    date -u
    exit "$f6_exit"
}

date -u
git rev-parse HEAD > "$f6_out/product-sha.txt"
git diff --exit-code
git diff --cached --exit-code
xcodebuild -version > "$f6_out/toolchain.txt"
xcrun simctl list devices available -j > "$f6_out/devices-before.json"
python3 - "$f6_out/devices-before.json" <<'PY'
import json, sys
devices = [device for runtime in json.load(open(sys.argv[1]))["devices"].values() for device in runtime]
target = "3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473"
assert any(device["udid"] == target for device in devices), "Authorized guest is absent"
assert [device["udid"] for device in devices if device["state"] == "Booted"] == [target], "Expected only the authorized guest to be booted"
PY
trap cleanup EXIT
python3 "$f6_root/check_pin_baseline.py" initial "$f6_out/pin-baseline.json"
xcrun simctl ui "$f6_device" content_size large
if [ "$f6_mode" = all ]; then
    make -f Makefile -f "$f6_root/destination.mk" verify-ios \
        F6_REVIEW_ROOT="$f6_root" F6_GATE_RESULT="$f6_out/gate.xcresult" > "$f6_out/verify-ios.log" 2>&1
    echo 'F6 make verify-ios passed.'
elif [ "$f6_mode" = ui-only ]; then
    python3 - "$f6_root/01" "$f6_out" <<'PY'
from pathlib import Path
import json, sys
gate, current = map(Path, sys.argv[1:])
assert (gate / "product-sha.txt").read_text() == (current / "product-sha.txt").read_text(), "Source changed since gate"
summary = json.loads((gate / "gate-summary.json").read_text())
assert summary["result"] == "Passed" and summary["totalTestCount"] == 82 and summary["failedTests"] == 0 and summary["skippedTests"] == 0
(current / "gate-reference.txt").write_text(str(gate / "gate.xcresult") + "\n")
PY
    shasum -a 256 -c "$f6_root/01/app-sha256.txt"
    echo 'Reusing unchanged source/binary and passed 82-test gate; rerunning only native UI after harness correction.'
else
    echo 'Unknown verification mode.' >&2
    exit 2
fi

f6_app="$f6_root/DerivedData/Build/Products/Debug-iphonesimulator/Flint.app"
test -d "$f6_app"
shasum -a 256 "$f6_app/Flint" "$f6_app/Flint.debug.dylib" > "$f6_out/app-sha256.txt"
xcrun simctl terminate "$f6_device" com.flint.peakfocus || true
xcrun simctl install "$f6_device" "$f6_app"
python3 "$f6_root/check_pin_baseline.py" installed "$f6_out/pin-baseline.json"
xcodegen generate --spec "$f6_root/ios-ui/project.yml" --project "$f6_root/ios-ui" > "$f6_out/ui-project.log" 2>&1
run_ui() {
    xcodebuild test -project "$f6_root/ios-ui/FlintF6AuthorizationUI.xcodeproj" -scheme FlintF6AuthorizationUI \
        -destination "platform=iOS Simulator,id=$f6_device" -derivedDataPath "$f6_root/UIDerivedData" \
        -resultBundlePath "$f6_out/$f6_size.xcresult" \
        -parallel-testing-enabled NO -maximum-concurrent-test-simulator-destinations 1 \
        -collect-test-diagnostics never "$@" CODE_SIGNING_ALLOWED=NO > "$f6_out/$f6_size.log" 2>&1
}
for f6_size in large accessibility-extra-extra-extra-large; do
    xcrun simctl ui "$f6_device" content_size "$f6_size"
    xcrun simctl ui "$f6_device" content_size > "$f6_out/$f6_size-content-size.txt"
    if [ "$f6_mode" = ui-only ] && [ "$f6_size" = large ]; then
        run_ui -only-testing:FlintF6AuthorizationUITests/InsightsAuthorizationUITests/testDeniedUsesSettingsAndOneRecheckForBothRangesAndResume
    else
        run_ui
    fi
done
date -u
