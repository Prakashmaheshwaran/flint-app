#!/bin/bash
set -euo pipefail
cd /Users/prakash/.codex/worktrees/f5-permission-errors-recovery/flint
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
f5_root=/tmp/flint-fixes-20261024/F5/review-followup
f5_device=3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473
f5_run=${1:?unique run label}
mkdir -p "$f5_root/$f5_run"
exec > "$f5_root/$f5_run/sequence.log" 2>&1
f5_app=$(python3 - "$f5_root/build-settings.json" <<'PY'
import json,sys
settings=next(x['buildSettings'] for x in json.load(open(sys.argv[1])) if x['target']=='Flint')
print(settings['TARGET_BUILD_DIR']+'/'+settings['FULL_PRODUCT_NAME'])
PY
)
cleanup() {
    xcrun simctl ui "$f5_device" content_size large
    xcrun simctl terminate "$f5_device" com.flint.peakfocus || true
    xcrun simctl launch "$f5_device" com.flint.peakfocus
    xcrun simctl ui "$f5_device" content_size
    xcrun simctl io "$f5_device" screenshot "$f5_root/$f5_run/cleanup-large.png"
    xcrun simctl list devices booted
    date -u
}
trap cleanup EXIT
date -u
git rev-parse HEAD
git diff --stat
xcrun simctl list devices available -j > "$f5_root/$f5_run/devices-before.json"
python3 - "$f5_root/$f5_run/devices-before.json" <<'PY'
import json,sys
devices=[d for v in json.load(open(sys.argv[1]))['devices'].values() for d in v]
target='3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473'
assert [d['udid'] for d in devices if d['state']=='Booted']==[target], 'Expected only the authorized test guest to be booted'
PY
test -d "$f5_app"
stat -f '%Sm %N' "$f5_app/Flint" "$f5_app/Flint.debug.dylib"
shasum -a 256 "$f5_app/Flint" "$f5_app/Flint.debug.dylib"
xcrun simctl terminate "$f5_device" com.flint.peakfocus || true
xcrun simctl install "$f5_device" "$f5_app"
xcodegen generate --spec "$f5_root/ui/project.yml" --project "$f5_root/ui"
for f5_size in large accessibility-extra-extra-extra-large; do
    xcrun simctl ui "$f5_device" content_size "$f5_size"
    xcrun simctl ui "$f5_device" content_size
    xcodebuild test -project "$f5_root/ui/FlintF5Review.xcodeproj" -scheme FlintF5Review \
        -destination "platform=iOS Simulator,id=$f5_device" -derivedDataPath "$f5_root/UIBuild" \
        -resultBundlePath "$f5_root/$f5_run/$f5_size.xcresult" \
        -parallel-testing-enabled NO -maximum-concurrent-test-simulator-destinations 1 \
        -collect-test-diagnostics never CODE_SIGNING_ALLOWED=NO \
        > "$f5_root/$f5_run/$f5_size.log" 2>&1
done
