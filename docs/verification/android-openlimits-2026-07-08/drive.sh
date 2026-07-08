#!/usr/bin/env bash
# Flint — Android Open Limits emulator verification (Path B: a11y OFF, usage access ON,
# overlay ON). Modeled on scripts/android-pathb-verify.sh (the proven 2026-07-08 harness),
# but the Open Limit is authored through the REAL Limits-editor UI, because the debug
# SetBlocklistReceiver has no open-limit hook — this run therefore also exercises the
# Blocklist → New app limit → picker → editor → DataStore → coordinator wiring end to end.
set -euo pipefail

export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
ADB="${ADB:-/opt/homebrew/share/android-commandlinetools/platform-tools/adb}"
PKG=com.flint.peakfocus
CLOCK_PKG=com.google.android.deskclock
CLOCK_ACTIVITY=com.google.android.deskclock/com.android.deskclock.DeskClock
APK="${APK:-/Users/prakash/Desktop/open-source/flint/android-app/app/build/outputs/apk/debug/app-debug.apk}"
OUT_DIR="${OUT_DIR:-/tmp/flint-openlimits-agent/evidence}"

mkdir -p "$OUT_DIR"

log() { printf '%s\n' "$*" | tee -a "$OUT_DIR/run.txt"; }
run() { log "$ $*"; "$@" 2>&1 | tee -a "$OUT_DIR/run.txt"; }

windows_dump() { "$ADB" shell dumpsys window windows > "$OUT_DIR/$1-windows.txt"; }
screenshot()   { "$ADB" exec-out screencap -p > "$OUT_DIR/$1.png"; }
ui_dump()      { "$ADB" exec-out uiautomator dump /dev/tty 2>/dev/null > "$OUT_DIR/ui.xml" || true; }

# The Path B overlay window's title is the bare package name; MainActivity/BlockActivity
# windows carry a "/Class" suffix and never match. Same discriminator the prior run used.
has_bare_flint_overlay() { grep -Eq 'Window\{[^ ]+ u0 com\.flint\.peakfocus\}' "$1"; }

assert_overlay() {
  windows_dump "$1"
  has_bare_flint_overlay "$OUT_DIR/$1-windows.txt" || { echo "ASSERT FAILED: expected Flint overlay in $1" >&2; exit 1; }
}
assert_no_overlay() {
  windows_dump "$1"
  ! has_bare_flint_overlay "$OUT_DIR/$1-windows.txt" || { echo "ASSERT FAILED: expected NO Flint overlay in $1" >&2; exit 1; }
}

# Poll the UI hierarchy until an exact text node appears (Compose emits text= verbatim).
wait_text() {
  local text="$1" timeout="${2:-12}" i=0
  while (( i < timeout * 2 )); do
    ui_dump
    grep -qF "text=\"$text\"" "$OUT_DIR/ui.xml" && return 0
    sleep 0.5; i=$(( i + 1 ))
  done
  echo "ASSERT FAILED: timeout waiting for text \"$text\"" >&2
  cp "$OUT_DIR/ui.xml" "$OUT_DIR/failed-wait-$(echo "$text" | tr -c 'A-Za-z0-9' '-').xml" || true
  return 1
}

# Tap a node by exact text=. Mode: "first" (default) = first match in document order;
# "maxy" = the match lowest on screen (for bottom-nav labels that may repeat in content).
tap_text() {
  local text="$1" mode="${2:-first}"
  ui_dump
  local xy
  xy="$(python3 - "$text" "$mode" "$OUT_DIR/ui.xml" <<'PY'
import re, sys
target, mode, path = sys.argv[1], sys.argv[2], sys.argv[3]
xml = open(path, encoding="utf-8", errors="ignore").read()
hits = []
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    text, l, t, r, b = m.groups()
    if text == target:
        hits.append((int(t), (int(l)+int(r))//2, (int(t)+int(b))//2))
if not hits:
    sys.exit(1)
if mode == "maxy":
    hits.sort(key=lambda h: h[0])
    hit = hits[-1]
else:
    hit = hits[0]
print(hit[1], hit[2])
PY
)" || { echo "ASSERT FAILED: no tappable node with text \"$text\"" >&2; return 1; }
  log "tap \"$text\" ($mode) @ $xy"
  "$ADB" shell input tap $xy
}

# Swipe up (reveal lower content) until a text node exists. find = locate only; tap = + tap.
scroll_find_text() {
  local text="$1" tries=0
  while (( tries < 5 )); do
    ui_dump
    grep -qF "text=\"$text\"" "$OUT_DIR/ui.xml" && return 0
    "$ADB" shell input swipe 540 1600 540 800 300
    sleep 1
    tries=$(( tries + 1 ))
  done
  echo "ASSERT FAILED: text \"$text\" not found after scrolling" >&2
  return 1
}
scroll_tap_text() { scroll_find_text "$1" && tap_text "$1"; }

on_exit() {
  local code=$?
  screenshot "99-final" || true
  windows_dump "99-final" || true
  "$ADB" logcat -d -v time 2>/dev/null | grep -iE "peakfocus|flint" | tail -300 > "$OUT_DIR/99-logcat-flint.txt" || true
  (( code == 0 )) || log "RUN FAILED (exit $code) — see $OUT_DIR"
}
trap on_exit EXIT

log "=== Flint Open Limits verification — $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
log "device: $ANDROID_SERIAL  out: $OUT_DIR"
run "$ADB" wait-for-device
boot="$("$ADB" shell getprop sys.boot_completed | tr -d '\r')"
[[ "$boot" == "1" ]] || { echo "device not booted (sys.boot_completed=$boot)" >&2; exit 1; }
log "fingerprint: $("$ADB" shell getprop ro.build.fingerprint | tr -d '\r')"
log "apk: sha256 $(shasum -a 256 "$APK" | cut -c1-16)…  mtime $(stat -f %Sm "$APK")"
"$ADB" shell input keyevent KEYCODE_WAKEUP || true
"$ADB" shell wm dismiss-keyguard || true

# ---- 0. Clean install + Path B preconditions (a11y OFF, usage ON, overlay ON) ----
run "$ADB" install -r -t "$APK"
run "$ADB" shell pm clear "$PKG"
"$ADB" shell settings delete secure enabled_accessibility_services >/dev/null 2>&1 || true
"$ADB" shell settings put secure accessibility_enabled 0
run "$ADB" shell appops set "$PKG" android:get_usage_stats allow
run "$ADB" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

run "$ADB" shell am start -n "$PKG/.MainActivity"
sleep 4
"$ADB" shell dumpsys activity services "$PKG" > "$OUT_DIR/00-pathb-service.txt"
grep -q 'UsageStatsForegroundService' "$OUT_DIR/00-pathb-service.txt"
grep -q 'isForeground=true' "$OUT_DIR/00-pathb-service.txt"
log "PASS 0: UsageStatsForegroundService is running as a foreground service (a11y off)"

# ---- 1. Author the Open Limit through the real UI: Clock, 2 opens/day, EASY ----
wait_text "Blocklist"
tap_text "Blocklist" maxy        # bottom-nav item (lowest on screen if the label repeats)
wait_text "Blocking"             # Blocklist overview header
screenshot "01-blocklist-tab"
scroll_tap_text "New app limit"
wait_text "Choose an app to limit"
screenshot "02-app-picker"
scroll_tap_text "Clock"          # single-select: tapping the row confirms
wait_text "Daily open limit"
screenshot "03-limit-editor"
tap_text "Daily open limit"      # whole row is toggleable
wait_text "Opens per day"        # field only exists once the toggle is on
tap_text "Opens per day"
sleep 1
"$ADB" shell input text "2"
sleep 1
ui_dump
grep -qF 'text="2"' "$OUT_DIR/ui.xml" || { echo "ASSERT FAILED: opens field did not accept input" >&2; exit 1; }
screenshot "04-opens-entered"
"$ADB" shell input keyevent 4    # dismiss IME (consumed by the IME, not the app back handler)
sleep 1
scroll_tap_text "Save limits"
wait_text "Blocking"                     # editor popped back to the Blocklist overview
scroll_find_text "2 opens/day (Easy)"    # the saved row's summary (limitSummary format)
ui_dump
grep -qF 'text="Clock"' "$OUT_DIR/ui.xml" || { echo "ASSERT FAILED: saved limit row lacks the Clock label" >&2; exit 1; }
screenshot "05-limit-saved-overview"
log "PASS 1: Open Limit authored via UI — overview row shows Clock · 2 opens/day (Easy)"

# ---- 2. Open #1 — under quota, must stay usable (no shield) ----
run "$ADB" shell input keyevent KEYCODE_HOME
sleep 2                          # ≥1 poll tick on the launcher so the transition counts
run "$ADB" shell am start -n "$CLOCK_ACTIVITY"
sleep 5                          # ~5 poll ticks: a wrongly-early shield would land in 1-2s
assert_no_overlay "06-open1-allowed"
screenshot "06-open1-allowed"
ui_dump
grep -qF "$CLOCK_PKG" "$OUT_DIR/ui.xml" || { echo "ASSERT FAILED: Clock not foreground on open #1" >&2; exit 1; }
log "PASS 2: open #1 allowed — Clock stayed unshielded for ~5s (5 poll ticks)"

# ---- 3. Open #2 — reaches the quota; the at-quota re-check shields it ----
run "$ADB" shell input keyevent KEYCODE_HOME
sleep 2
run "$ADB" shell am start -n "$CLOCK_ACTIVITY"
sleep 4
assert_overlay "07-open2-blocked"
screenshot "07-open2-blocked"
ui_dump
grep -qF 'text="OPEN LIMIT"' "$OUT_DIR/ui.xml" || { echo "ASSERT FAILED: shield lacks the OPEN LIMIT badge" >&2; exit 1; }
grep -qF "opens for this app" "$OUT_DIR/ui.xml" || { echo "ASSERT FAILED: shield lacks the open-limit copy" >&2; exit 1; }
cp "$OUT_DIR/ui.xml" "$OUT_DIR/07-open2-blocked-ui.xml"
log "PASS 3: open #2 hit the quota — Flint overlay up with the 'Open limit' cause"

# ---- 4. Leaving the app stands the shield down ----
run "$ADB" shell input keyevent KEYCODE_HOME
sleep 3
assert_no_overlay "08-home-standdown"
screenshot "08-home-standdown"
log "PASS 4: shield stands down on the launcher"

# ---- 5. Open #3 — over quota, re-shields immediately ----
run "$ADB" shell am start -n "$CLOCK_ACTIVITY"
sleep 3
assert_overlay "09-open3-reshield"
screenshot "09-open3-reshield"
log "PASS 5: over-quota reopen re-shields"

# ---- 6. EASY 'use anyway': Take a break drops the shield (spends one more open) ----
tap_text "Take a break"
sleep 3
assert_no_overlay "10-break-standdown"
screenshot "10-break-standdown"
ui_dump
grep -qF "$CLOCK_PKG" "$OUT_DIR/ui.xml" || { echo "ASSERT FAILED: Clock not usable after break" >&2; exit 1; }
log "PASS 6: EASY break granted from the Open-limit shield — Clock usable (5-min exemption)"

log "DONE — all Open Limits checks passed"
