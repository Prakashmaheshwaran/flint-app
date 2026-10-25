#!/bin/bash
set -euo pipefail
cd /Users/prakash/.codex/worktrees/f5-permission-errors-recovery/flint
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
f5_root=/tmp/flint-fixes-20261024/F5/review-followup
f5_run=${1:?unique run label}
exec > "$f5_root/verify-ios-$f5_run.log" 2>&1
date -u
git rev-parse HEAD
git diff --stat
xcodebuild -version
xcrun simctl list devices available -j > "$f5_root/devices-before-gate.json"
python3 - "$f5_root/devices-before-gate.json" <<'PY'
import json,sys
all_devices=[d for v in json.load(open(sys.argv[1]))['devices'].values() for d in v]
target='3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473'
assert any(d['udid']==target for d in all_devices), 'Authorized guest missing'
assert not [d for d in all_devices if d['state']=='Booted' and d['udid']!=target], 'Another guest is booted; aborting'
PY
make -f Makefile -f "$f5_root/destination.mk" verify-ios F5_GATE_RESULT="$f5_root/gate-$f5_run.xcresult"
xcrun simctl ui 3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473 content_size large
xcodebuild -project ios-app/Flint.xcodeproj -scheme Flint -sdk iphonesimulator -showBuildSettings -json > "$f5_root/build-settings.json"
date -u
