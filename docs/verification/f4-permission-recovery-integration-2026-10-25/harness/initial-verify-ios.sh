#!/bin/bash
set -euo pipefail
cd /Users/prakash/.codex/worktrees/f4-accessible-text-layouts-recovery/flint
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
f4_root=/tmp/flint-fixes-20261024/F4/review-followup
f4_device=3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473
f4_run=${1:?unique run label}
exec > "$f4_root/verify-ios-$f4_run.log" 2>&1
date -u
git rev-parse HEAD
git diff --exit-code
python3 "$f4_root/check-baseline.py" "$f4_root/gate-$f4_run-baseline-before.json"
cleanup() {
    set +e
    {
        xcrun simctl ui "$f4_device" content_size large
        echo "large_restore_exit=$?"
        xcrun simctl ui "$f4_device" content_size
        python3 "$f4_root/check-baseline.py" "$f4_root/gate-$f4_run-baseline-after.json"
        echo "final_baseline_guard_exit=$?"
        date -u
    } > "$f4_root/gate-$f4_run-cleanup.txt" 2>&1
}
trap cleanup EXIT
make -f Makefile -f "$f4_root/destination.mk" verify-ios F4_GATE_RESULT="$f4_root/gate-$f4_run.xcresult"
xcodebuild -project ios-app/Flint.xcodeproj -scheme Flint -sdk iphonesimulator -showBuildSettings -json > "$f4_root/build-settings-$f4_run.json"
python3 - "$f4_root/build-settings-$f4_run.json" "$f4_root/product-$f4_run.json" <<'PY'
import hashlib,json,subprocess,sys
from pathlib import Path
s=next(x['buildSettings'] for x in json.load(open(sys.argv[1])) if x['target']=='Flint')
app=Path(s['TARGET_BUILD_DIR'])/s['FULL_PRODUCT_NAME']
files=[app/'Flint',app/'Flint.debug.dylib']
assert all(p.is_file() for p in files)
record={'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
        'appPath':str(app),'executables':[{'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in files]}
Path(sys.argv[2]).write_text(json.dumps(record,indent=2)+'\n')
print('Built product source and executable hashes recorded.')
PY
date -u
