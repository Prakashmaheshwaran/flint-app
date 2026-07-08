#!/usr/bin/env bash
# Follow-up measurements: (1) precise time-to-shield on each open under a 2-opens/day limit
# (expect: open #1 never shields; open #2 shields ~1-2s in — the at-quota re-check runs every
# 1s poll tick and `used >= dailyOpens` includes the in-progress open), and (2) open-count
# persistence across process death (force-stop → relaunch → reopen must re-shield at once).
set -euo pipefail

export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
ADB="${ADB:-/opt/homebrew/share/android-commandlinetools/platform-tools/adb}"
PKG=com.flint.peakfocus
CLOCK_ACTIVITY=com.google.android.deskclock/com.android.deskclock.DeskClock
APK="${APK:-/Users/prakash/Desktop/open-source/flint/android-app/app/build/outputs/apk/debug/app-debug.apk}"
OUT_DIR="${OUT_DIR:-/tmp/flint-openlimits-agent/evidence-measure}"
mkdir -p "$OUT_DIR"

log() { printf '%s\n' "$*" | tee -a "$OUT_DIR/run.txt"; }
run() { log "$ $*"; "$@" 2>&1 | tee -a "$OUT_DIR/run.txt"; }
now_s() { python3 -c 'import time; print(f"{time.time():.2f}")'; }
screenshot() { "$ADB" exec-out screencap -p > "$OUT_DIR/$1.png"; }
ui_dump() { "$ADB" exec-out uiautomator dump /dev/tty 2>/dev/null > "$OUT_DIR/ui.xml" || true; }
overlay_up() { "$ADB" shell dumpsys window windows | grep -Eq 'Window\{[^ ]+ u0 com\.flint\.peakfocus\}'; }

wait_text() {
  local text="$1" timeout="${2:-12}" i=0
  while (( i < timeout * 2 )); do
    ui_dump; grep -qF "text=\"$text\"" "$OUT_DIR/ui.xml" && return 0
    sleep 0.5; i=$(( i + 1 ))
  done
  echo "timeout waiting for \"$text\"" >&2; return 1
}
tap_text() {
  local text="$1" mode="${2:-first}"
  ui_dump
  local xy
  xy="$(python3 - "$text" "$mode" "$OUT_DIR/ui.xml" <<'PY'
import re, sys
target, mode, path = sys.argv[1], sys.argv[2], sys.argv[3]
xml = open(path, encoding="utf-8", errors="ignore").read()
hits = [(int(t), (int(l)+int(r))//2, (int(t)+int(b))//2)
        for text, l, t, r, b in re.findall(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if text == target]
if not hits: sys.exit(1)
hit = sorted(hits)[-1] if mode == "maxy" else hits[0]
print(hit[1], hit[2])
PY
)" || { echo "no node \"$text\"" >&2; return 1; }
  log "tap \"$text\" @ $xy"; "$ADB" shell input tap $xy
}
scroll_tap_text() {
  local text="$1" tries=0
  while (( tries < 5 )); do
    ui_dump; grep -qF "text=\"$text\"" "$OUT_DIR/ui.xml" && { tap_text "$text"; return 0; }
    "$ADB" shell input swipe 540 1600 540 800 300; sleep 1; tries=$(( tries + 1 ))
  done
  return 1
}

# Launch Clock and poll the window table (~0.35s cadence) up to $1 s; print time-to-shield.
launch_clock_time_shield() {
  local budget="$1" t0 t
  "$ADB" shell am start -n "$CLOCK_ACTIVITY" >/dev/null 2>&1
  t0=$(now_s)
  while :; do
    if overlay_up; then t=$(now_s); python3 -c "print(f'{$t - $t0:.2f}')"; return 0; fi
    t=$(now_s)
    python3 -c "import sys; sys.exit(0 if $t - $t0 > $budget else 1)" && { echo "none"; return 0; }
    sleep 0.15
  done
}

log "=== Open Limits timing + persistence measurements — $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="

# Clean slate + author Clock 2 opens/day (EASY) through the UI, as in drive.sh.
run "$ADB" shell pm clear "$PKG"
run "$ADB" shell appops set "$PKG" android:get_usage_stats allow
run "$ADB" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
run "$ADB" shell am start -n "$PKG/.MainActivity"
sleep 4
wait_text "Blocklist"; tap_text "Blocklist" maxy
wait_text "Blocking"; scroll_tap_text "New app limit"
wait_text "Choose an app to limit"; scroll_tap_text "Clock"
wait_text "Daily open limit"; tap_text "Daily open limit"
wait_text "Opens per day"; tap_text "Opens per day"; sleep 1
"$ADB" shell input text "2"; sleep 1
"$ADB" shell input keyevent 4; sleep 1
scroll_tap_text "Save limits"
wait_text "Blocking"
log "limit authored: Clock · 2 opens/day (EASY)"

"$ADB" shell input keyevent KEYCODE_HOME; sleep 2
t1="$(launch_clock_time_shield 8)"
log "MEASURE open#1 time-to-shield: ${t1}s (expected: none within 8s)"
[[ "$t1" == "none" ]] || { echo "FAIL: open #1 was shielded" >&2; exit 1; }

"$ADB" shell input keyevent KEYCODE_HOME; sleep 2
t2="$(launch_clock_time_shield 10)"
log "MEASURE open#2 time-to-shield: ${t2}s (expected: ~1-2.5s — at-quota re-check truncates the last allowed open)"
[[ "$t2" != "none" ]] || { echo "FAIL: open #2 never shielded" >&2; exit 1; }
screenshot "m1-open2-shielded"

# ---- Persistence across process death: force-stop, relaunch, reopen → immediate shield ----
"$ADB" shell input keyevent KEYCODE_HOME; sleep 2
run "$ADB" shell am force-stop "$PKG"
sleep 1
run "$ADB" shell am start -n "$PKG/.MainActivity"   # PathBServiceGate re-starts the poll FGS
sleep 4
"$ADB" shell dumpsys activity services "$PKG" > "$OUT_DIR/m2-service-after-forcestop.txt"
grep -q 'isForeground=true' "$OUT_DIR/m2-service-after-forcestop.txt"
log "service back up after force-stop (gate re-armed on resume)"
"$ADB" shell input keyevent KEYCODE_HOME; sleep 2
t3="$(launch_clock_time_shield 10)"
log "MEASURE reopen-after-force-stop time-to-shield: ${t3}s (expected: immediate — counts persisted)"
[[ "$t3" != "none" ]] || { echo "FAIL: persisted counts did not re-shield after process death" >&2; exit 1; }
screenshot "m2-reshield-after-forcestop"
ui_dump
grep -qF 'text="OPEN LIMIT"' "$OUT_DIR/ui.xml" && log "cause after restart is still OPEN LIMIT"

log "DONE measurements: open1=${t1} open2=${t2}s post-force-stop=${t3}s"
