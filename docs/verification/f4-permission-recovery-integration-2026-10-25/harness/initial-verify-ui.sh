#!/bin/bash
set -euo pipefail
cd /Users/prakash/.codex/worktrees/f4-accessible-text-layouts-recovery/flint
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
f4_root=/tmp/flint-fixes-20261024/F4/review-followup
f4_device=3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473
f4_gate=${1:?successful gate label}
f4_run=${2:?unique UI run label}
f4_sizes=${3:-"large accessibility-extra-extra-extra-large"}
mkdir -p "$f4_root/$f4_run"
exec > "$f4_root/$f4_run/sequence.log" 2>&1
python3 - "$f4_run" <<'PY'
import json,sys
from pathlib import Path
from datetime import datetime,timezone
p=Path('/tmp/flint-fixes-20261024/F4/review-followup-status.json')
s=json.loads(p.read_text());s.update(updatedAt=datetime.now(timezone.utc).isoformat(),phase='native_sequence_running',nativeStatus='fresh merged-source binary; requested font sequence',nativeRun=sys.argv[1])
p.write_text(json.dumps(s,indent=2)+'\n')
PY
date -u
git rev-parse HEAD
git diff --exit-code
python3 "$f4_root/check-baseline.py" "$f4_root/$f4_run/baseline-before.json"
xcrun simctl ui "$f4_device" content_size > "$f4_root/$f4_run/content-size-before.txt"
f4_app=$(python3 - "$f4_root/product-$f4_gate.json" <<'PY'
import hashlib,json,subprocess,sys
from pathlib import Path
m=json.load(open(sys.argv[1]))
assert m['sourceCommit']==subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
for record in m['executables']:
    assert hashlib.sha256(Path(record['path']).read_bytes()).hexdigest()==record['sha256']
print(m['appPath'])
PY
)
cleanup() {
    set +e
    {
        xcrun simctl ui "$f4_device" content_size large
        echo "large_restore_exit=$?"
        xcrun simctl ui "$f4_device" content_size
        xcrun simctl terminate "$f4_device" com.flint.peakfocus
        xcrun simctl launch "$f4_device" com.flint.peakfocus
        echo "production_launch_exit=$?"
        python3 "$f4_root/check-baseline.py" "$f4_root/$f4_run/baseline-after.json"
        echo "final_baseline_guard_exit=$?"
        date -u
    } > "$f4_root/$f4_run/cleanup.txt" 2>&1
}
trap cleanup EXIT
xcrun simctl bootstatus "$f4_device" -b
xcrun simctl terminate "$f4_device" com.flint.peakfocus || true
xcrun simctl install "$f4_device" "$f4_app"
cp "$f4_root/ios-ui/Tests/RecoveryLayoutTests.swift" "$f4_root/$f4_run/RecoveryLayoutTests.swift"
cp "$f4_root/ios-ui/project.yml" "$f4_root/$f4_run/project.yml"
cp "$f4_root/product-$f4_gate.json" "$f4_root/$f4_run/product.json"
xcodegen generate --spec "$f4_root/ios-ui/project.yml" --project "$f4_root/ios-ui"
for f4_size in $f4_sizes; do
    case "$f4_size" in large|accessibility-extra-extra-extra-large) ;; *) exit 64;; esac
    xcrun simctl ui "$f4_device" content_size "$f4_size"
    xcrun simctl ui "$f4_device" content_size
    f4_result=0
    xcodebuild test -project "$f4_root/ios-ui/FlintF4RecoveryUI.xcodeproj" -scheme FlintF4RecoveryUI \
        -destination "platform=iOS Simulator,id=$f4_device" -derivedDataPath "$f4_root/UIBuild" \
        -resultBundlePath "$f4_root/$f4_run/$f4_size.xcresult" \
        -parallel-testing-enabled NO -maximum-concurrent-test-simulator-destinations 1 \
        -collect-test-diagnostics never CODE_SIGNING_ALLOWED=NO \
        > "$f4_root/$f4_run/$f4_size.log" 2>&1 || f4_result=$?
    xcrun xcresulttool export attachments --path "$f4_root/$f4_run/$f4_size.xcresult" \
        --output-path "$f4_root/$f4_run/$f4_size-attachments" \
        > "$f4_root/$f4_run/$f4_size-export.log" 2>&1 || true
    xcrun xcresulttool get test-results summary --path "$f4_root/$f4_run/$f4_size.xcresult" \
        > "$f4_root/$f4_run/$f4_size-summary.json" 2> "$f4_root/$f4_run/$f4_size-summary-error.log" || true
    if [ "$f4_result" -ne 0 ]; then exit "$f4_result"; fi
done
date -u
