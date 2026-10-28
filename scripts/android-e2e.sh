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
variant=${FLINT_E2E_VARIANT:-debug}
[[ $variant == debug || $variant == release ]] || { echo 'FLINT_E2E_VARIANT must be debug or release.' >&2; exit 2; }
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
boot_id=$(adb -s "$serial" shell cat /proc/sys/kernel/random/boot_id | tr -d '\r')
[[ $boot_id =~ ^[0-9a-f-]{36}$ ]] || { echo 'Cannot verify emulator boot incarnation.' >&2; exit 2; }
run_token=$(python3 -c 'import uuid; print(uuid.uuid4())')
service=com.flint.peakfocus/com.flint.peakfocus.blocking.accessibility.FlintAccessibilityService
prior_global=$(adb -s "$serial" shell settings get secure accessibility_enabled | tr -d '\r')
marker=/sdcard/Android/data/com.flint.peakfocus.test/files/owned-service.txt
printf '%s\n' "serial=$serial" "boot_id=$boot_id" "run_token=$run_token" > artifacts/android-e2e/device.txt
collect() {
  status=$?
  trap - EXIT
  cd "$ROOT"
  if [[ $(adb -s "$serial" shell cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r') == "$boot_id" ]]; then
    adb -s "$serial" logcat -d > artifacts/android-e2e/logcat.txt 2>&1 || true
    adb -s "$serial" pull /sdcard/Android/data/com.flint.peakfocus.test/files/smoke artifacts/android-e2e/ || true
    adb -s "$serial" exec-out screencap -p > artifacts/android-e2e/final.png || true
    # A test writes this run's token before granting Flint. Never erase another service.
    if [[ $(adb -s "$serial" shell cat "$marker" 2>/dev/null | tr -d '\r') == "$run_token" ]]; then
      current=$(adb -s "$serial" shell settings get secure enabled_accessibility_services | tr -d '\r')
      remaining=$(printf '%s' "$current" | python3 -c 'import sys; print(":".join(s for s in sys.stdin.read().split(":") if s and s not in ("null", sys.argv[1])))' "$service")
      adb -s "$serial" shell settings put secure enabled_accessibility_services "${remaining:-null}" || true
      if [[ -z $remaining ]]; then
        if [[ $prior_global == null ]]; then
          adb -s "$serial" shell settings delete secure accessibility_enabled || true
        else
          adb -s "$serial" shell settings put secure accessibility_enabled "$prior_global" || true
        fi
      fi
      adb -s "$serial" shell rm -f "$marker" || true
    fi
  else
    echo 'Emulator incarnation changed; skipped collection and grant cleanup.' > artifacts/android-e2e/cleanup.txt
  fi
  if [[ ${CI:-} != true ]]; then bash scripts/clean-devices.sh || true; fi
  exit "$status"
}
trap collect EXIT
export ANDROID_SERIAL="$serial"
cd android-app
if [[ $variant == release ]]; then
  FLINT_E2E_OUTER_RUN_TOKEN="$run_token" bash "$ROOT/scripts/android-release-instrumentation.sh" "$serial" "$boot_id" "$run_token"
  exit 0
fi
# Never retry assertions. Retain test APK so its screenshot files can be pulled on failure too.
./gradlew -PflintE2E=true :e2e:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.flintDisposable=true \
  -Pandroid.testInstrumentationRunnerArguments.flintBootId="$boot_id" \
  -Pandroid.testInstrumentationRunnerArguments.flintRunToken="$run_token" \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
