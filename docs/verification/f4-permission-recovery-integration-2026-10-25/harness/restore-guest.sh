#!/bin/bash
set -euo pipefail
cd /Users/prakash/.codex/worktrees/f4-accessible-text-layouts-recovery/flint
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
f4_root=/tmp/flint-fixes-20261024/F4/review-followup
f4_device=3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473
exec > "$f4_root/ui-01/cleanup-recovery.log" 2>&1
date -u
xcodebuild -version
python3 "$f4_root/check-baseline.py" "$f4_root/ui-01/recovery-baseline-before.json"
f4_state=$(xcrun simctl list devices available -j | python3 -c 'import json,sys; print(next(d["state"] for v in json.load(sys.stdin)["devices"].values() for d in v if d["udid"]=="3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473"))')
if [ "$f4_state" = Shutdown ]; then xcrun simctl boot "$f4_device"; fi
xcrun simctl bootstatus "$f4_device" -b
xcrun simctl ui "$f4_device" content_size large
xcrun simctl ui "$f4_device" content_size
python3 "$f4_root/check-baseline.py" "$f4_root/ui-01/recovery-baseline-after.json"
xcrun simctl launch "$f4_device" com.flint.peakfocus
date -u
