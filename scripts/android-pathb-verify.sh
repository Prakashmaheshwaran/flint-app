#!/usr/bin/env bash
# Flint — Path B (a11y OFF / usage access ON / overlay ON) emulator verification.
# Helpers, provenance logging, and evidence conventions live in lib/android-verify.sh —
# read the contract block at the top of that file before editing assertions here.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/android-verify.sh
source "$ROOT/scripts/lib/android-verify.sh"

PKG="com.flint.peakfocus"
CLOCK_PKG="com.google.android.deskclock"
CLOCK_ACTIVITY="com.google.android.deskclock/com.android.deskclock.DeskClock"
CONTACTS_PKG="com.google.android.contacts"
CONTACTS_ACTIVITY="com.google.android.contacts/com.android.contacts.activities.PeopleActivity"
APK="${APK:-$ROOT/android-app/app/build/outputs/apk/debug/app-debug.apk}"

av_init "pathb"          # evidence dir under /tmp/flint-verify/pathb/run-<UTC>-<pid>
av_device_ready
av_log_apk "$APK"

run "$ADB" install -r -t "$APK"

run "$ADB" shell pm clear "$PKG"
"$ADB" shell settings delete secure enabled_accessibility_services >/dev/null 2>&1 || true
"$ADB" shell settings put secure accessibility_enabled 0
run "$ADB" shell appops set "$PKG" android:get_usage_stats allow
run "$ADB" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
sdk="$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "${sdk:-0}" -ge 33 ]]; then
  "$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
fi

run "$ADB" shell am start -n "$PKG/.MainActivity"
sleep 4
"$ADB" shell dumpsys activity services "$PKG" > "$OUT_DIR/01-pathb-service.txt"
grep -q 'UsageStatsForegroundService' "$OUT_DIR/01-pathb-service.txt"
grep -q 'isForeground=true' "$OUT_DIR/01-pathb-service.txt"
pass "service starts as foreground when usage access is allowed and a11y is off"

run "$ADB" shell am broadcast -n "$PKG/.SetBlocklistReceiver" --es package "$CLOCK_PKG"
sleep 1
run "$ADB" shell am start -n "$CLOCK_ACTIVITY"
sleep 3
assert_overlay "02-clock-blocked"
screenshot "02-clock-blocked"
pass "Path B blocks a debug-seeded blocklist app"

tap_text "Open Flint"
sleep 3
assert_no_overlay "03-open-flint-standdown"
screenshot "03-open-flint-standdown"
pass "Open Flint CTA stands the Path B overlay down"

run "$ADB" shell am start -n "$CLOCK_ACTIVITY"
sleep 3
assert_overlay "04-clock-reshielded"
screenshot "04-clock-reshielded"
pass "blocked app re-shields after leaving Flint"

tap_text "Take a break"
date -u +%Y-%m-%dT%H:%M:%SZ > "$OUT_DIR/05-break-granted-at.txt"
sleep 3
assert_no_overlay "05-break-granted"
screenshot "05-break-granted"
pass "EASY break grants and suppresses the shield"

run "$ADB" shell am broadcast -n "$PKG/.SetBlocklistReceiver" --ez clear true
run "$ADB" shell am broadcast -n "$PKG/.SetBlocklistReceiver" --es limitPkg "$CONTACTS_PKG" --ei limitMin 0
sleep 1
run "$ADB" shell am start -n "$CONTACTS_ACTIVITY"
sleep 3
assert_overlay "06-contacts-time-limit"
screenshot "06-contacts-time-limit"
wait_badge_text "Time limit" 6      # FlintBadge renders uppercase: matches text="TIME LIMIT"
pass "Path B enforces a 0-minute daily Time Limit"

log "DONE Path B emulator verification passed"
