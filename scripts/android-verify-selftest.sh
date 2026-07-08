#!/usr/bin/env bash
# android-verify-selftest.sh — proves lib/android-verify.sh's contracts with a stub adb
# and canned uiautomator dumps. No device, no emulator, no Android SDK: run it anywhere
# bash 3.2+ and python3 exist (same spirit as scripts/fleet-selftest). Exit 0 = every
# guarantee the library documents actually holds:
#
#   * wait_text is viewport-only and, on a below-the-fold miss, saves failed-wait-*
#     artifacts and says to use scroll_find_text (the 2026-07-08 run-1 defect).
#   * assert_badge_text matches the UPPERCASE rendered form; the source-case grep that
#     burned run 2 fails (fixture nodes carry the real shield dump's text + bounds).
#   * scroll_find_text logs every swipe with purpose, reports how many swipes found the
#     text, and distinguishes "list stopped moving" from "ran out of swipes".
#   * tap_text centers match independent math on real emulator bounds ("Blocklist"
#     at [341,2253][465,2295] → 403 2274, the tap run 3 actually logged), maxy picks
#     the lowest duplicate, XML entities are unescaped, misses save failed-tap-*.
#   * assert_overlay/assert_no_overlay discriminate the bare overlay window from the
#     "/Activity"-suffixed windows.
#   * av_init logs full sha256 of driver + lib, copies both into 00-harness/, refuses
#     to reuse an evidence dir, maintains the latest symlink, and flags an unhashable
#     $0 (bash -c / heredoc execution — the run-1 drift trap).
#   * the driver lint warns (never fails) on source-case badge greps and raw
#     `shell input` events.
#
# Usage:  scripts/android-verify-selftest.sh
#   AV_SELFTEST_WORK=<dir>  work dir (default: mktemp -d; removed on success)
#   LIB=<path>              lib under test (default: lib/android-verify.sh beside this)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="${LIB:-$HERE/lib/android-verify.sh}"
[[ -f "$LIB" ]] || { echo "lib not found at $LIB" >&2; exit 2; }
KEEP_WORK="${AV_SELFTEST_WORK:-}"
WORK="${AV_SELFTEST_WORK:-$(mktemp -d "${TMPDIR:-/tmp}/av-selftest.XXXXXX")}"
mkdir -p "$WORK"

# ---------------------------------------------------------------------------
# stub adb: serves canned dumps / window tables, records input events.
# State per scenario in $STUB_STATE: dump-N.xml (position N, clamped down to the last
# existing), pos (input swipe increments), windows (path served for dumpsys window),
# events.log (every `shell input ...`, verbatim).
# ---------------------------------------------------------------------------
cat > "$WORK/stub-adb" <<'STUB'
#!/usr/bin/env bash
set -u
S="${STUB_STATE:?STUB_STATE not set}"
ev() { printf '%s\n' "$*" >> "$S/events.log"; }
pos() { cat "$S/pos" 2>/dev/null || echo 0; }
cmd="${1:-}"; shift || true
case "$cmd" in
  wait-for-device) exit 0 ;;
  install) echo "Performing Streamed Install"; echo "Success" ;;
  logcat) echo "07-08 03:00:00.000 I peakfocus(123): stub logcat line" ;;
  exec-out)
    sub="${1:-}"
    if [[ "$sub" == "uiautomator" ]]; then
      p="$(pos)"; f="$S/dump-$p.xml"
      while [[ ! -f "$f" && "$p" -gt 0 ]]; do p=$((p - 1)); f="$S/dump-$p.xml"; done
      cat "$f" 2>/dev/null || true
    elif [[ "$sub" == "screencap" ]]; then
      printf 'PNGSTUB'
    fi ;;
  shell)
    sub="${1:-}"; shift || true
    case "$sub" in
      getprop)
        case "${1:-}" in
          sys.boot_completed) echo "1" ;;
          ro.build.fingerprint) echo "stub/emu64a:15/STUB.000/0:userdebug/dev-keys" ;;
          ro.build.version.sdk) echo "35" ;;
          *) echo "" ;;
        esac ;;
      wm) [[ "${1:-}" == "size" ]] && echo "Physical size: 1080x2400" ;;
      input)
        ev "input $*"
        if [[ "${1:-}" == "swipe" ]]; then p="$(pos)"; echo $((p + 1)) > "$S/pos"; fi ;;
      dumpsys)
        if [[ "${1:-}" == "window" ]]; then
          w="$(cat "$S/windows" 2>/dev/null || true)"
          [[ -n "$w" ]] && cat "$w" 2>/dev/null
        else
          echo "stub dumpsys"
        fi ;;
      *) ev "shell $sub $*" ;;
    esac ;;
  *) exit 0 ;;
esac
exit 0
STUB
chmod +x "$WORK/stub-adb"

# ---------------------------------------------------------------------------
# fixtures — node text + bounds lifted verbatim from real emulator dumps
# (sdk_gphone64_arm64, Android 15, 1080x2400; the 2026-07-08 Open Limits evidence).
# ---------------------------------------------------------------------------

# Blocklist overview rendered at the top of the list: "New app limit" is genuinely
# absent (LazyColumn hasn't composed it) — the run-1 failure viewport.
cat > "$WORK/dump-toplist.xml" <<'XML'
<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0"><node index="0" text="Blocking" resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[53,127][338,222]" /><node index="1" text="RULES, SCHEDULES &amp; LIMITS" resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[53,222][470,264]" /><node index="2" text="BLOCK RULES" resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[53,327][250,369]" /><node index="3" text="New block rule" resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[419,912][662,965]" /><node index="4" text="Home" resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[86,2253][168,2295]" /><node index="5" text="Blocklist" resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[341,2253][465,2295]" /></hierarchy>
XML

# The same screen one swipe later: the APP LIMITS section is now composed.
cat > "$WORK/dump-newlimit.xml" <<'XML'
<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0"><node index="0" text="APP LIMITS" resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[53,1800][250,1842]" /><node index="1" text="New app limit" resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[151,1900][929,2050]" /><node index="2" text="Blocklist" resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[341,2253][465,2295]" /></hierarchy>
XML

# The Open-limit shield exactly as run 2 dumped it: badge is text="OPEN LIMIT"
# (FlintBadge uppercases at render); copy carries the curly apostrophe.
cat > "$WORK/dump-shield.xml" <<'XML'
<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0"><node index="0" text="PEAK FOCUS" resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[449,788][632,830]" /><node index="1" text="Clock can wait" resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[302,872][778,967]" /><node index="2" text="You’ve used today’s opens for this app." resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[153,999][927,1062]" /><node index="3" text="OPEN LIMIT" resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[456,1115][625,1157]" /><node index="4" text="Unblocks in 20h 57m" resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[369,1252][712,1305]" /><node index="5" text="Go back" resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[473,1842][607,1895]" /><node index="6" text="Take a break" resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[437,2006][644,2059]" /><node index="7" text="Open Flint" resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[457,2164][624,2217]" /></hierarchy>
XML

# Two "Duplicated" nodes at different heights — maxy must pick the lower (200,2050).
cat > "$WORK/dump-dup.xml" <<'XML'
<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0"><node index="0" text="Duplicated" resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[100,300][300,400]" /><node index="1" text="Duplicated" resource-id="" class="android.widget.TextView" package="com.flint.peakfocus" content-desc="" bounds="[100,2000][300,2100]" /></hierarchy>
XML

# Window tables in the real dumpsys line shape. The overlay-present table carries BOTH
# the bare overlay window and the /MainActivity window — the discriminator must match
# only the bare one, so the no-overlay table (activity window only) must NOT match.
cat > "$WORK/windows-overlay.txt" <<'WIN'
WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #7 Window{1f2d0c8 u0 com.flint.peakfocus}:
  Window #12 Window{dd4d7c1 u0 com.flint.peakfocus/com.flint.peakfocus.MainActivity}:
WIN
cat > "$WORK/windows-noverlay.txt" <<'WIN'
WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #12 Window{dd4d7c1 u0 com.flint.peakfocus/com.flint.peakfocus.MainActivity}:
WIN

# ---------------------------------------------------------------------------
# scenario drivers (run in their own process so expected failures don't kill us)
# ---------------------------------------------------------------------------
cat > "$WORK/driver.sh" <<'DRV'
#!/usr/bin/env bash
set -euo pipefail
# shellcheck source=/dev/null
source "${LIB:?LIB not set}"
case "${1:?scenario}" in
  anchor-hit)         av_init t-anchor;   av_device_ready; wait_text "Blocking" 3 ;;
  wait-miss)          av_init t-waitmiss; av_device_ready; wait_text "New app limit" 2 ;;
  scroll-found)       av_init t-scroll;   av_device_ready; scroll_find_text "New app limit" 4 ;;
  scroll-dry)         av_init t-dry;      av_device_ready; scroll_find_text "Nonexistent thing" 4 ;;
  anchor-then-scroll) av_init t-ats;      av_device_ready; wait_anchor_then_scroll "Blocking" "New app limit" 3 4 ;;
  badge)              av_init t-badge;    assert_badge_text "Open limit"; wait_badge_text "Open limit" 2 ;;
  badge-source-case)  av_init t-badgesrc; assert_ui_text "Open limit" ;;
  tap-first)          av_init t-tap;      tap_text "Blocklist" ;;
  tap-escaped)        av_init t-tapesc;   tap_text "RULES, SCHEDULES & LIMITS" ;;
  tap-maxy)           av_init t-maxy;     tap_text "Duplicated" maxy ;;
  tap-miss)           av_init t-tapmiss;  tap_text "New app limit" ;;
  overlay-assert)     av_init t-ov;       assert_overlay "01-ov" ;;
  no-overlay-assert)  av_init t-noov;     assert_no_overlay "01-noov" ;;
  reuse)              av_init t-reuse ;;
  type)               av_init t-type;     type_text "hello world" ;;
  apk)                av_init t-apk;      av_log_apk "${STUB_APK:?}" ;;
  *) echo "unknown scenario $1" >&2; exit 99 ;;
esac
DRV

cat > "$WORK/driver-lint.sh" <<'DRV'
#!/usr/bin/env bash
# lint-bait: (unreached) instances of the two lintable traps.
set -euo pipefail
# shellcheck source=/dev/null
source "${LIB:?LIB not set}"
if false; then
  grep -qF 'text="Open limit"' /dev/null
  "$ADB" shell input swipe 540 1600 540 800 300
fi
av_init t-lint
DRV

# ---------------------------------------------------------------------------
# harness
# ---------------------------------------------------------------------------
PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); echo "ok   - $*"; }
bad() { FAIL=$((FAIL + 1)); echo "FAIL - $*"; }
t()   { local d="$1"; shift; if "$@" >/dev/null 2>&1; then ok "$d"; else bad "$d"; fi; }
file_has() { grep -qF -- "$2" "$1"; }
sha_of() { shasum -a 256 "$1" 2>/dev/null | awk '{print $1}' || sha256sum "$1" | awk '{print $1}'; }

# sc <name> <scenario> <windows-fixture> <dump0> [dump1 ...] — exposes RC, D (evidence
# dir), EV (recorded input events).
sc() {
  local name="$1" scenario="$2" windows="$3"; shift 3
  local S="$WORK/state-$name"
  D="$WORK/out-$name"; EV="$S/events.log"
  mkdir -p "$S"; : > "$EV"
  printf '%s\n' "$windows" > "$S/windows"
  local i=0
  for dump in "$@"; do cp "$dump" "$S/dump-$i.xml"; i=$((i + 1)); done
  set +e
  STUB_STATE="$S" ADB="$WORK/stub-adb" LIB="$LIB" OUT_DIR="$D" AV_ROOT="$WORK/root-$name" \
    AV_SETTLE=0 STUB_APK="${STUB_APK:-}" bash "$WORK/driver.sh" "$scenario" \
    > "$WORK/stdout-$name.txt" 2>&1
  RC=$?
  set -e
}

echo "=== android-verify.sh selftest (lib: $LIB) ==="

# 1. wait_text anchor hit + full provenance block
sc anchor anchor-hit "$WORK/windows-overlay.txt" "$WORK/dump-toplist.xml"
t "anchor-hit exits 0"                          test "$RC" -eq 0
t "provenance: script sha256 full + correct"    file_has "$D/run.txt" "script: sha256 $(sha_of "$WORK/driver.sh")"
t "provenance: lib sha256 logged"               file_has "$D/run.txt" "lib: sha256 $(sha_of "$LIB")"
t "harness copies: driver + lib"                test -s "$D/00-harness/driver.sh" -a -s "$D/00-harness/android-verify.sh"
t "harness copy matches executed driver"        cmp -s "$D/00-harness/driver.sh" "$WORK/driver.sh"
t "latest symlink points at the run dir"        test "$(readlink "$WORK/root-anchor/latest")" = "$D"
t "fingerprint + geometry logged"               file_has "$D/run.txt" "screen: 1080x2400"
t "END line on success"                         file_has "$D/run.txt" "— exit 0"
t "exit trap: 99-final.png + logcat"            test -s "$D/99-final.png" -a -s "$D/99-logcat-flint.txt"

# 2. wait_text below-the-fold miss (the run-1 defect), diagnosed
sc waitmiss wait-miss "$WORK/windows-overlay.txt" "$WORK/dump-toplist.xml"
t "wait-miss exits nonzero"                     test "$RC" -ne 0
t "failed-wait artifacts saved"                 test -s "$D/failed-wait-New-app-limit.xml" -a -s "$D/failed-wait-New-app-limit.png"
t "failure hints at scroll_find_text"           file_has "$D/run.txt" "scroll_find_text"
t "failure explains LazyColumn composition"     file_has "$D/run.txt" "LazyColumn only composes visible items"
t "trap logs RUN FAILED with exit code"         file_has "$D/run.txt" "RUN FAILED (exit 1)"

# 3. scroll_find_text finds after one LOGGED swipe
sc scroll scroll-found "$WORK/windows-overlay.txt" "$WORK/dump-toplist.xml" "$WORK/dump-newlimit.xml"
t "scroll-found exits 0"                        test "$RC" -eq 0
t "swipe logged with geometry + purpose"        file_has "$D/run.txt" 'swipe (540,1600)->(540,800) 300ms — swipe up 1/4 looking for "New app limit"'
t "swipe recorded as a real input event"        file_has "$EV" "input swipe 540 1600 540 800 300"
t "outcome logged (found after N swipes)"       file_has "$D/run.txt" 'found "New app limit" after 1 swipe(s)'

# 4. scroll_find_text exhaustion beats max-swipes
sc dry scroll-dry "$WORK/windows-overlay.txt" "$WORK/dump-toplist.xml"
t "scroll-dry exits nonzero"                    test "$RC" -ne 0
t "distinguishes 'list stopped moving'"         file_has "$D/run.txt" "stopped moving after 1 swipe(s)"
t "verdict: absent, not off-viewport"           file_has "$D/run.txt" "not merely off-viewport"
t "only one swipe spent (not max=4)"            test "$(grep -c '^input swipe' "$EV")" -eq 1

# 5. the composed post-navigation pattern (what fixed run 1)
sc ats anchor-then-scroll "$WORK/windows-overlay.txt" "$WORK/dump-toplist.xml" "$WORK/dump-newlimit.xml"
t "wait_anchor_then_scroll exits 0"             test "$RC" -eq 0

# 6. uppercase badge contract on the real shield nodes
sc badge badge "$WORK/windows-overlay.txt" "$WORK/dump-shield.xml"
t "assert_badge_text 'Open limit' passes"       test "$RC" -eq 0
sc badgesrc badge-source-case "$WORK/windows-overlay.txt" "$WORK/dump-shield.xml"
t "source-case 'Open limit' fails (run-2 bug)"  test "$RC" -ne 0
t "source-case failure saves artifacts"         test -s "$D/failed-text-Open-limit.xml"

# 7. tap_text coordinate math on real bounds; entities; maxy; miss artifacts
sc tap tap-first "$WORK/windows-overlay.txt" "$WORK/dump-toplist.xml"
t "tap-first exits 0"                           test "$RC" -eq 0
t "tap center = 403 2274 (run 3's real tap)"    file_has "$EV" "input tap 403 2274"
t "tap coords logged in run.txt"                file_has "$D/run.txt" 'tap "Blocklist" (first) @ 403 2274'
sc tapesc tap-escaped "$WORK/windows-overlay.txt" "$WORK/dump-toplist.xml"
t "XML-entity text tappable (&amp; unescaped)"  test "$RC" -eq 0
sc maxy tap-maxy "$WORK/windows-overlay.txt" "$WORK/dump-dup.xml"
t "maxy picks the lowest duplicate"             file_has "$EV" "input tap 200 2050"
sc tapmiss tap-miss "$WORK/windows-overlay.txt" "$WORK/dump-toplist.xml"
t "tap-miss exits nonzero + saves artifacts"    test "$RC" -ne 0 -a -s "$D/failed-tap-New-app-limit.xml"

# 8. overlay discriminator 2x2 (bare window vs /Activity-suffixed only)
sc ovp overlay-assert "$WORK/windows-overlay.txt" "$WORK/dump-toplist.xml"
t "assert_overlay passes w/ bare window"        test "$RC" -eq 0
sc ovf overlay-assert "$WORK/windows-noverlay.txt" "$WORK/dump-toplist.xml"
t "assert_overlay fails w/ activity-only"       test "$RC" -ne 0
t "overlay failure names the artifact"          file_has "$D/run.txt" "ASSERT FAILED: expected Flint overlay window in 01-ov"
sc noovp no-overlay-assert "$WORK/windows-noverlay.txt" "$WORK/dump-toplist.xml"
t "assert_no_overlay passes w/ activity-only"   test "$RC" -eq 0
sc noovf no-overlay-assert "$WORK/windows-overlay.txt" "$WORK/dump-toplist.xml"
t "assert_no_overlay fails w/ bare window"      test "$RC" -ne 0

# 9. evidence-dir immutability
sc reuse1 reuse "$WORK/windows-overlay.txt" "$WORK/dump-toplist.xml"
LINES_BEFORE="$(wc -l < "$D/run.txt")"
FIRST_D="$D"
set +e
STUB_STATE="$WORK/state-reuse1" ADB="$WORK/stub-adb" LIB="$LIB" OUT_DIR="$FIRST_D" \
  AV_ROOT="$WORK/root-reuse1" AV_SETTLE=0 bash "$WORK/driver.sh" reuse \
  > "$WORK/stdout-reuse2.txt" 2>&1
RC2=$?
set -e
t "second run into same dir refused (exit 2)"   test "$RC2" -eq 2
t "refusal names the guard"                     file_has "$WORK/stdout-reuse2.txt" "refusing to reuse evidence dir"
t "first run's run.txt untouched"               test "$(wc -l < "$FIRST_D/run.txt")" -eq "$LINES_BEFORE"

# 10. type_text logging + %s escaping
sc type type "$WORK/windows-overlay.txt" "$WORK/dump-toplist.xml"
t "type_text logs the literal string"           file_has "$D/run.txt" 'type "hello world"'
t "type_text escapes spaces for input text"     file_has "$EV" "input text hello%sworld"

# 11. av_log_apk full sha256
STUB_APK="$WORK/dump-shield.xml"
sc apk apk "$WORK/windows-overlay.txt" "$WORK/dump-toplist.xml"
t "apk sha256 logged in full"                   file_has "$D/run.txt" "apk: sha256 $(sha_of "$WORK/dump-shield.xml")"
STUB_APK=""

# 12. driver lint: warn-only, both traps flagged
local_S="$WORK/state-lint"; local_D="$WORK/out-lint"
mkdir -p "$local_S"; : > "$local_S/events.log"
printf '%s\n' "$WORK/windows-overlay.txt" > "$local_S/windows"
cp "$WORK/dump-toplist.xml" "$local_S/dump-0.xml"
set +e
STUB_STATE="$local_S" ADB="$WORK/stub-adb" LIB="$LIB" OUT_DIR="$local_D" \
  AV_ROOT="$WORK/root-lint" AV_SETTLE=0 bash "$WORK/driver-lint.sh" \
  > "$WORK/stdout-lint.txt" 2>&1
RC=$?
set -e
t "lint-bait driver still exits 0 (warn-only)"  test "$RC" -eq 0
t "lint flags source-case badge grep"           file_has "$local_D/run.txt" "badge strings render UPPERCASE"
t "lint flags raw shell input"                  file_has "$local_D/run.txt" "raw 'shell input' events will not appear in run.txt"

# 13. unhashable $0 (bash -c / heredoc execution) called out loudly
local_S="$WORK/state-unhash"; local_D="$WORK/out-unhash"
mkdir -p "$local_S"; : > "$local_S/events.log"
printf '%s\n' "$WORK/windows-overlay.txt" > "$local_S/windows"
set +e
STUB_STATE="$local_S" ADB="$WORK/stub-adb" OUT_DIR="$local_D" AV_ROOT="$WORK/root-unhash" \
  AV_SETTLE=0 bash -c "set -euo pipefail; source '$LIB'; av_init t-unhash" \
  > "$WORK/stdout-unhash.txt" 2>&1
set -e
t "bash -c \$0 flagged as unhashable"           file_has "$local_D/run.txt" "cannot hash the executed"

echo "=== $PASS passed, $FAIL failed (work: $WORK) ==="
if [[ "$FAIL" -eq 0 ]]; then
  [[ -z "$KEEP_WORK" ]] && rm -rf "$WORK"
  echo "OK"
  exit 0
fi
exit 1
