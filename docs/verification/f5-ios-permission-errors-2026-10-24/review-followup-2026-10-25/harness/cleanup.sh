#!/bin/bash
set -euo pipefail
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
f5_root=/tmp/flint-fixes-20261024/F5/review-followup
f5_device=3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473
exec > "$f5_root/cleanup.log" 2>&1
date -u
xcrun simctl list devices booted -j > "$f5_root/cleanup-devices.json"
python3 - "$f5_root/cleanup-devices.json" <<'PY'
import json,sys
devices=[d['udid'] for v in json.load(open(sys.argv[1]))['devices'].values() for d in v if d['state']=='Booted']
assert devices==['3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473'], 'Unexpected booted guest'
PY
f5_app=$(python3 - "$f5_root/build-settings.json" <<'PY'
import json,sys
s=next(x['buildSettings'] for x in json.load(open(sys.argv[1])) if x['target']=='Flint')
print(s['TARGET_BUILD_DIR']+'/'+s['FULL_PRODUCT_NAME'])
PY
)
shasum -a 256 "$f5_app/Flint" "$f5_app/Flint.debug.dylib"
xcrun simctl ui "$f5_device" content_size large
xcrun simctl terminate "$f5_device" com.flint.peakfocus || true
xcrun simctl install "$f5_device" "$f5_app"
xcrun simctl launch "$f5_device" com.flint.peakfocus
# Allow the normal launch animation to finish before capturing cleanup evidence.
sleep 3
xcrun simctl ui "$f5_device" content_size
xcrun simctl io "$f5_device" screenshot "$f5_root/cleanup-large-final.png"
date -u
