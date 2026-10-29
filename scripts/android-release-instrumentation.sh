#!/usr/bin/env bash
# Called by android-e2e.sh, which owns collection and token-bound grant cleanup.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/scripts/flint-env.sh"
serial=${1:-}
boot_id=${2:-}
run_token=${3:-}
[[ ${FLINT_E2E_DISPOSABLE:-} == 1 && $serial =~ ^emulator-[0-9]+$ ]] || exit 2
[[ $boot_id =~ ^[0-9a-f-]{36}$ && $run_token =~ ^[0-9a-f-]{36}$ ]] || exit 2
[[ ${FLINT_E2E_OUTER_RUN_TOKEN:-} == "$run_token" ]] || { echo 'Invoke release smoke through android-e2e.sh for grant cleanup.' >&2; exit 2; }
assert_boot() {
  [[ $(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
  [[ $(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r') == 1 ]]
  [[ $(adb -s "$serial" shell cat /proc/sys/kernel/random/boot_id | tr -d '\r') == "$boot_id" ]]
}
assert_boot
enabled=$(adb -s "$serial" shell settings get secure enabled_accessibility_services | tr -d '\r')
[[ -z $enabled || $enabled == null ]] || { echo 'Release smoke requires no existing accessibility services.' >&2; exit 2; }
cd "$ROOT/android-app"
# assembleDebug builds the isolated self-instrumenting test APK; it never installs
# the debug app. Never call connectedDebugAndroidTest on this release path.
./gradlew -PflintE2E=true :app:assembleRelease :e2e:assembleDebug
apk="$ROOT/android-app/app/build/outputs/apk/release/app-release.apk"
test_apk="$ROOT/android-app/e2e/build/outputs/apk/debug/e2e-debug.apk"
python3 "$ROOT/scripts/verify_local_first_apk.py" "$apk"
evidence="$ROOT/artifacts/android-e2e"
mkdir -p "$evidence"
git -C "$ROOT" rev-parse HEAD > "$evidence/source-commit.txt"
assert_boot
adb -s "$serial" install -r "$apk"
adb -s "$serial" install -r "$test_apk"
# Hash the installed package bytes, not just the intended input. A split or an
# unexpected package path must fail rather than accidentally certify debug.
for specification in "com.flint.peakfocus|$apk|release" "com.flint.peakfocus.test|$test_apk|test"; do
  IFS='|' read -r package expected label <<< "$specification"
  installed=$(adb -s "$serial" shell pm path "$package" | tr -d '\r')
  [[ $installed == package:/* && $installed != *$'\n'* ]] || { echo "Expected one installed APK for $package." >&2; exit 2; }
  adb -s "$serial" pull "${installed#package:}" "$evidence/installed-$label.apk"
  python3 - "$expected" "$evidence/installed-$label.apk" "$label" >> "$evidence/apk-provenance.txt" <<'PY'
import hashlib, pathlib, sys
expected, installed = (pathlib.Path(p) for p in sys.argv[1:3])
digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
if digest(expected) != digest(installed):
    raise SystemExit(f"Installed {sys.argv[3]} APK does not match build input")
print(f"{sys.argv[3]} sha256={digest(installed)} bytes={installed.stat().st_size}")
PY
done
adb -s "$serial" shell dumpsys package com.flint.peakfocus > "$evidence/release-package.txt"
assert_boot
# Author real state through the release UI and render the actual minified widget worker
# through an isolated test-APK host. DEBUG-only fixtures are excluded from release.
adb -s "$serial" shell am instrument -w -r \
  -e class com.flint.peakfocus.e2e.FlintSmokeTest#authoredRuleSurvivesProcessRestartAndShieldBreakWorks,com.flint.peakfocus.e2e.GlanceStartupWidgetTest \
  -e flintDisposable true -e flintBootId "$boot_id" -e flintRunToken "$run_token" \
  com.flint.peakfocus.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$evidence/release-instrumentation.txt"
python3 "$ROOT/scripts/verify_instrumentation_result.py" "$evidence/release-instrumentation.txt"
