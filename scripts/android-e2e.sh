#!/usr/bin/env bash
# Destructive only to Flint's app data on an explicitly opted-in disposable emulator.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/scripts/flint-env.sh"
cd "$ROOT"
[[ ${FLINT_E2E_DISPOSABLE:-} == 1 ]] || {
  echo 'Use a disposable emulator: FLINT_E2E_DISPOSABLE=1 AVD=emulator-N make android-e2e (clears Flint data).' >&2
  exit 2
}
serial=${AVD:-}
[[ $serial =~ ^emulator-[0-9]+$ ]] || { echo 'AVD must be an exact booted emulator serial (emulator-N).' >&2; exit 2; }
[[ $(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || exit 2
[[ $(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r') == 1 ]] || exit 2
enabled=$(adb -s "$serial" shell settings get secure enabled_accessibility_services | tr -d '\r')
[[ -z $enabled || $enabled == null ]] || {
  echo 'Refusing to overwrite existing accessibility services; use a fresh disposable emulator.' >&2
  exit 2
}
if [[ ${CI:-} != true ]]; then
  avd_name=$(adb -s "$serial" emu avd name | tr -d '\r' | head -1)
  # Using an existing emulator confers no boot/shutdown ownership.
  bash scripts/flint-devices.sh claim android "$serial" --avd "$avd_name"
fi
mkdir -p artifacts/android-e2e
collect() {
  status=$?
  trap - EXIT
  cd "$ROOT"
  adb -s "$serial" logcat -d > artifacts/android-e2e/logcat.txt 2>&1 || true
  adb -s "$serial" pull /sdcard/Android/data/com.flint.peakfocus.test/files/smoke artifacts/android-e2e/ || true
  adb -s "$serial" exec-out screencap -p > artifacts/android-e2e/final.png || true
  adb -s "$serial" shell settings put secure enabled_accessibility_services null || true
  adb -s "$serial" shell settings put secure accessibility_enabled 0 || true
  if [[ ${CI:-} != true ]]; then bash scripts/clean-devices.sh || true; fi
  exit "$status"
}
trap collect EXIT
export ANDROID_SERIAL="$serial"
cd android-app
# Never retry assertions. Retain test APK so its screenshot files can be pulled on failure too.
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.flintDisposable=true \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
