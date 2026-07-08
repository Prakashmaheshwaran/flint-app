#!/usr/bin/env bash
# android-verify.sh — shared helpers for Flint's Android emulator verification drivers.
#
# Source this file from a driver script, then:
#     av_init "pathb"            # evidence dir, EXIT trap, provenance logging, driver lint
#     av_device_ready            # wait-for-device + boot check + wake + geometry probe
#     av_log_apk "$APK"          # APK identity next to the script identity
#     ... wait_text / tap_text / scroll_find_text / assert_* / pass ...
#
# Born from the 2026-07-08 Open Limits triage (two driver false-failures against a
# correctly-behaving app). The contracts below are load-bearing — each one maps to a
# defect that actually burned a run:
#
#   1. wait_text NEVER scrolls, and Compose LazyColumn only composes on-viewport items,
#      so a dump genuinely contains no node for below-the-fold content. Wait on an
#      always-visible anchor (the screen header), then scroll_find_text the content —
#      or use wait_anchor_then_scroll, which is that pattern as one call.
#   2. FlintBadge, FlintSectionLabel, the FlintScreenHeader eyebrow, and the
#      FlintTimeField label all render text.uppercase() (core-common FlintKit.kt), and
#      Compose semantics carry the TRANSFORMED string. uiautomator can only ever see
#      "OPEN LIMIT", never "Open limit". assert_badge_text uppercases for you.
#   3. Every UI mutation goes through a logged verb (tap_text/tap_xy/swipe/type_text/
#      key). Raw `adb shell input` calls leave evidence you cannot triage — an unlogged
#      swipe made run-1's tap coordinates unexplainable.
#   4. run.txt records the sha256 of the executed driver AND this library, and both are
#      copied into <out>/00-harness/. Evidence that can't drift from its harness.
#   5. Evidence dirs are immutable per run: av_init mints <root>/run-<UTC>-<pid> and
#      refuses any directory that already holds a run.txt. Never rotate or reuse.
#
# Text-matching caveats (they bite):
#   * Matches are against the uiautomator XML, i.e. the XML-ESCAPED rendered string.
#     Flint copy uses the curly apostrophe: grep "opens for this app", not "You've...",
#     or pass the exact ’ character.
#   * grep_ui anchors on text="..." so resource-ids/content-desc can't false-match.
#
# Portability: bash 3.2 (macOS /bin/bash) — no ${var^^}, no mapfile, no assoc arrays.
# Safe under `set -euo pipefail` (assert/wait helpers return 1; set -e turns that into
# the run failing, which is the intent — or wrap in `if` to branch).

# ---------------------------------------------------------------------------
# state (set by av_init)
# ---------------------------------------------------------------------------
AV_NAME=""
AV_PASS_N=0
AV_UI_XML=""          # $OUT_DIR/ui.xml — the "latest dump" the greps run against
AV_W=1080             # screen geometry; av_device_ready probes the real values
AV_H=2400
AV_SETTLE="${AV_SETTLE:-1}"        # seconds to let the UI settle after a swipe
AV_SWIPE_MS="${AV_SWIPE_MS:-300}"  # swipe duration; ~300ms is a drag, not a fling

# ---------------------------------------------------------------------------
# logging — everything lands in $OUT_DIR/run.txt
# ---------------------------------------------------------------------------
log() { printf '%s\n' "$*" | tee -a "$OUT_DIR/run.txt"; }

# Log-and-execute for adb/build commands whose output belongs in the record.
run() { log "\$ $*"; "$@" 2>&1 | tee -a "$OUT_DIR/run.txt"; }

# Auto-numbered PASS line: pass "claim"  ->  "PASS 3: claim". Hand-written
# `log "PASS ..."` lines remain valid grammar; this just numbers for free.
pass() { AV_PASS_N=$((AV_PASS_N + 1)); log "PASS $AV_PASS_N: $*"; }

# Uniform failure line. Helpers call this and return 1; under set -e the run dies
# with the reason already in run.txt (and the EXIT trap adds the 99-final artifacts).
_avfail() { log "ASSERT FAILED: $*"; return 1; }

# slug "New app limit"  ->  "New-app-limit"   (artifact-name-safe, no ---- runs)
_avslug() {
  printf '%s' "$1" | tr -c 'A-Za-z0-9' '-' | sed -e 's/--*/-/g' -e 's/^-//' -e 's/-$//' | cut -c1-60
}

# Snapshot the world at a failure: latest dump + screenshot + window table, all under
# one failed-<name> stem. The run-1 triage lived off exactly this artifact.
_avfail_artifacts() {
  local stem="failed-$1"
  cp "$AV_UI_XML" "$OUT_DIR/$stem.xml" 2>/dev/null || true
  "$ADB" exec-out screencap -p > "$OUT_DIR/$stem.png" 2>/dev/null || true
  "$ADB" shell dumpsys window windows > "$OUT_DIR/$stem-windows.txt" 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# init / provenance
# ---------------------------------------------------------------------------

# Locate adb: $ADB > $ANDROID_HOME > $ANDROID_SDK_ROOT > android-app/local.properties.
av_resolve_adb() {
  if [[ -n "${ADB:-}" && -x "${ADB:-}" ]]; then return 0; fi
  local lib_dir root sdk_dir=""
  lib_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  root="$(cd "$lib_dir/../.." && pwd)"
  sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -z "$sdk_dir" && -f "$root/android-app/local.properties" ]]; then
    sdk_dir="$(sed -n 's/^sdk.dir=//p' "$root/android-app/local.properties" | tail -1)"
  fi
  ADB="${sdk_dir:+$sdk_dir/platform-tools/adb}"
  if [[ -z "$ADB" || ! -x "$ADB" ]]; then
    echo "adb not found; set ADB, ANDROID_HOME, or ANDROID_SDK_ROOT" >&2
    return 2
  fi
}

_avsha() { shasum -a 256 "$1" 2>/dev/null | awk '{print $1}' || sha256sum "$1" | awk '{print $1}'; }
_avmtime() { stat -f %Sm "$1" 2>/dev/null || stat -c %y "$1" 2>/dev/null || echo "?"; }

# av_init <name> — mint the evidence dir, install the EXIT trap, write the provenance
# header, lint the driver. Local-only (no device I/O) so it works before a boot check.
#
#   evidence root:  $AV_ROOT           (default /tmp/flint-verify/<name>)
#   run dir:        $OUT_DIR           (default $AV_ROOT/run-<UTCstamp>-<pid>)
#   latest pointer: $AV_ROOT/latest -> $OUT_DIR   (atomic ln -sfn)
#
# A pre-set $OUT_DIR is honored but still passes the never-reuse guard: any directory
# already containing a run.txt is refused. This is what prevents the "driving session
# re-ran and rotated the evidence dirs mid-triage" failure mode — run dirs are minted
# fresh, never recycled, so archived paths in a triage report stay valid.
av_init() {
  AV_NAME="${1:?av_init needs a run-family name}"
  AV_ROOT="${AV_ROOT:-/tmp/flint-verify/$AV_NAME}"
  if [[ -z "${OUT_DIR:-}" ]]; then
    OUT_DIR="$AV_ROOT/run-$(date -u +%Y%m%dT%H%M%SZ)-$$"
  fi
  if [[ -e "$OUT_DIR/run.txt" ]]; then
    echo "refusing to reuse evidence dir $OUT_DIR (run.txt already exists; evidence dirs are immutable per run)" >&2
    return 2
  fi
  mkdir -p "$OUT_DIR/00-harness"
  mkdir -p "$AV_ROOT" && ln -sfn "$OUT_DIR" "$AV_ROOT/latest" 2>/dev/null || true
  AV_UI_XML="$OUT_DIR/ui.xml"
  av_resolve_adb
  export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
  trap _av_on_exit EXIT

  log "=== $AV_NAME — $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
  log "device: $ANDROID_SERIAL  out: $OUT_DIR"
  # Harness identity: hash + copy the EXECUTED driver and this library. If $0 is not a
  # regular file (heredoc, `bash -c`, process substitution) say so loudly — that is the
  # run-1 drift trap: evidence that cannot be tied back to the script that produced it.
  if [[ -f "$0" && -r "$0" ]]; then
    log "script: $0"
    log "script: sha256 $(_avsha "$0")  mtime $(_avmtime "$0")"
    cp "$0" "$OUT_DIR/00-harness/driver.sh" 2>/dev/null || true
  else
    log "script: WARNING — \$0 ($0) is not a readable regular file; cannot hash the executed"
    log "script: harness, so this evidence CANNOT be tied to a driver. Save the driver to a"
    log "script: file and run it as one."
  fi
  log "lib: ${BASH_SOURCE[0]}"
  log "lib: sha256 $(_avsha "${BASH_SOURCE[0]}")"
  cp "${BASH_SOURCE[0]}" "$OUT_DIR/00-harness/android-verify.sh" 2>/dev/null || true
  if [[ -n "${AV_NO_LINT:-}" ]]; then :; else av_lint_driver || true; fi
}

# APK identity, logged in the same block as the script identity. Full sha256 — the
# truncated hash in older runs left "…" in triage reports.
av_log_apk() {
  local apk="${1:?av_log_apk needs an apk path}"
  [[ -f "$apk" ]] || { echo "apk missing at $apk (run: make android)" >&2; return 2; }
  log "apk: $apk"
  log "apk: sha256 $(_avsha "$apk")  mtime $(_avmtime "$apk")"
}

# Device-facing init: block until booted, wake + dismiss keyguard, log the build
# fingerprint, probe real screen geometry for the swipe math.
av_device_ready() {
  run "$ADB" wait-for-device
  local boot
  boot="$("$ADB" shell getprop sys.boot_completed | tr -d '\r')"
  if [[ "$boot" != "1" ]]; then
    echo "device connected but not booted (sys.boot_completed=$boot)" >&2
    return 1
  fi
  log "fingerprint: $("$ADB" shell getprop ro.build.fingerprint | tr -d '\r')"
  "$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  "$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  local size
  size="$("$ADB" shell wm size 2>/dev/null | sed -n 's/.*: *\([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)"
  if [[ -n "$size" ]]; then
    AV_W="${size%% *}"
    AV_H="${size##* }"
  fi
  log "screen: ${AV_W}x${AV_H}"
}

# EXIT trap: unconditionally capture the end-of-run world. On failure the last thing
# in run.txt is the exit code and where to look.
_av_on_exit() {
  local code=$?
  "$ADB" exec-out screencap -p > "$OUT_DIR/99-final.png" 2>/dev/null || true
  "$ADB" shell dumpsys window windows > "$OUT_DIR/99-final-windows.txt" 2>/dev/null || true
  "$ADB" logcat -d -v time 2>/dev/null | grep -iE "peakfocus|flint" | tail -300 \
    > "$OUT_DIR/99-logcat-flint.txt" || true
  if (( code == 0 )); then
    log "END $(date -u +%Y-%m-%dT%H:%M:%SZ) — exit 0"
  else
    log "RUN FAILED (exit $code) — see $OUT_DIR"
  fi
}

# Warn-only static checks on the driver source for the traps this library exists to
# prevent. Never fails the run; set AV_NO_LINT=1 to silence.
av_lint_driver() {
  local f="${1:-$0}"
  [[ -f "$f" && -r "$f" ]] || return 0
  local hits
  # 1. badge/section strings grepped in source case — semantics only ever carry uppercase
  hits="$(grep -nE 'text="(Focus session|Schedule|Time limit|Open limit|Deep Focus|Protected)"' "$f" || true)"
  if [[ -n "$hits" ]]; then
    log "LINT: badge strings render UPPERCASE (FlintBadge does text.uppercase()); these greps can never match:"
    printf '%s\n' "$hits" | while IFS= read -r l; do log "LINT:   $l"; done
    log "LINT:   use assert_badge_text \"Open limit\" (it uppercases for you)"
  fi
  # 2. raw input events bypassing the logged verbs
  hits="$(grep -nE '"\$ADB"[[:space:]]+shell[[:space:]]+input[[:space:]]|adb[[:space:]]+shell[[:space:]]+input[[:space:]]' "$f" | grep -v 'KEYCODE_WAKEUP' || true)"
  if [[ -n "$hits" ]]; then
    log "LINT: raw 'shell input' events will not appear in run.txt (unlogged swipes made run-1 untriageable):"
    printf '%s\n' "$hits" | while IFS= read -r l; do log "LINT:   $l"; done
    log "LINT:   use the logged verbs: tap_text / tap_xy / swipe / type_text / key"
  fi
}

# ---------------------------------------------------------------------------
# observation
# ---------------------------------------------------------------------------

# Dump the UI hierarchy to $OUT_DIR/ui.xml. Fail-closed: the file is truncated first,
# so a failed dump yields an empty file and greps fail loudly — never a silent match
# against a stale dump from an earlier screen.
ui_dump() {
  : > "$AV_UI_XML"
  "$ADB" exec-out uiautomator dump /dev/tty 2>/dev/null > "$AV_UI_XML" \
    || log "WARN: uiautomator dump failed (ui.xml is empty this poll)"
}

# Exact-text presence in the LATEST dump (no fresh dump). Anchored on text="…" so
# resource-ids and content-desc can't false-match.
grep_ui() { grep -qF "text=\"$1\"" "$AV_UI_XML"; }

screenshot()   { "$ADB" exec-out screencap -p > "$OUT_DIR/$1.png"; }
windows_dump() { "$ADB" shell dumpsys window windows > "$OUT_DIR/$1-windows.txt"; }
# One checkpoint = screenshot + window table under the same NN-name stem.
snap()         { screenshot "$1"; windows_dump "$1"; }

# ---------------------------------------------------------------------------
# waiting — viewport-only vs scroll-aware (contract #1)
# ---------------------------------------------------------------------------

# wait_text <text> [timeout-s] — poll fresh dumps until an exact text node appears.
# VIEWPORT-ONLY BY DESIGN: it never scrolls, and it must not — a waiter that silently
# swipes would mask navigation failures and mutate the screen it is observing. Use it
# for anchors that are always on-viewport when the screen is correct: headers, dialog
# titles, bottom-nav labels. For anything that can sit below the fold in a LazyColumn,
# use scroll_find_text (LazyColumn does not compose off-screen items, so the node is
# genuinely absent from the dump until you swipe).
wait_text() {
  local text="$1" timeout="${2:-12}" i=0
  while (( i < timeout * 2 )); do
    ui_dump
    grep_ui "$text" && return 0
    sleep 0.5
    i=$((i + 1))
  done
  local s
  s="$(_avslug "$text")"
  _avfail_artifacts "wait-$s"
  _avfail "timeout (${timeout}s) waiting for text \"$text\" — artifacts: failed-wait-$s.{xml,png}. If this text can be below the fold, wait on an always-visible anchor then scroll_find_text it (LazyColumn only composes visible items)."
}

# scroll_find_text <text> [max-swipes] [up|down] — reveal content by swiping until the
# node exists in the dump. Every swipe is LOGGED (contract #3). Stops early when a
# swipe no longer changes the dump (list exhausted) — that distinguishes "the text is
# truly absent from this list" from "didn't swipe far enough", which need different
# fixes. Exhaustion detection is best-effort: content that repaints every second
# (countdowns) defeats it, and then only max-swipes bounds the search.
scroll_find_text() {
  local text="$1" max="${2:-5}" dir="${3:-up}" tries=0 prev="" cur=""
  local x=$((AV_W / 2)) y1 y2
  if [[ "$dir" == "down" ]]; then
    y1=$((AV_H / 3)); y2=$((AV_H * 2 / 3))   # reveal content ABOVE the viewport
  else
    y1=$((AV_H * 2 / 3)); y2=$((AV_H / 3))   # reveal content BELOW the viewport
  fi
  while :; do
    ui_dump
    if grep_ui "$text"; then
      (( tries > 0 )) && log "scroll_find_text: found \"$text\" after $tries swipe(s)"
      return 0
    fi
    cur="$(cksum "$AV_UI_XML" 2>/dev/null || true)"
    if [[ -n "$prev" && "$cur" == "$prev" ]]; then
      _avfail_artifacts "scroll-$(_avslug "$text")"
      _avfail "\"$text\" not found and the list stopped moving after $tries swipe(s) — the text is absent from this screen (wrong assertion or wrong screen), not merely off-viewport."
      return 1
    fi
    if (( tries >= max )); then
      _avfail_artifacts "scroll-$(_avslug "$text")"
      _avfail "\"$text\" not found after $tries swipes (list still moving — raise max-swipes if the list is long)"
      return 1
    fi
    prev="$cur"
    tries=$((tries + 1))
    swipe "$x" "$y1" "$x" "$y2" "$AV_SWIPE_MS" "swipe $dir $tries/$max looking for \"$text\""
    sleep "$AV_SETTLE"
  done
}

scroll_tap_text() { scroll_find_text "$1" "${2:-5}" && tap_text "$1"; }

# The post-navigation pattern from the triage as one call: prove the screen landed
# (always-visible anchor), then scroll for the content. This is what replaced run-1's
# fatal `wait_text "New app limit"`:  wait_anchor_then_scroll "Blocking" "2 opens/day (Easy)"
wait_anchor_then_scroll() {
  local anchor="$1" text="$2" timeout="${3:-12}" max="${4:-5}"
  wait_text "$anchor" "$timeout" && scroll_find_text "$text" "$max"
}

# ---------------------------------------------------------------------------
# assertions
# ---------------------------------------------------------------------------

# Fresh dump + exact text, with failed-* artifacts on miss.
assert_ui_text() {
  local text="$1"
  ui_dump
  if grep_ui "$text"; then return 0; fi
  local s
  s="$(_avslug "$text")"
  _avfail_artifacts "text-$s"
  _avfail "expected text \"$text\" on screen — artifacts: failed-text-$s.{xml,png}"
}

# ASCII-only uppercase (matches Kotlin .uppercase() for Flint's ASCII badge strings).
uc() { printf '%s' "$1" | tr '[:lower:]' '[:upper:]'; }

# assert_badge_text <source-string> — for anything rendered by FlintBadge /
# FlintSectionLabel / the FlintScreenHeader eyebrow / the FlintTimeField label
# (core-common FlintKit.kt uppercases all four at render; semantics carry the
# transformed string). Write the string as it appears in product source —
#   assert_badge_text "Open limit"     # matches text="OPEN LIMIT"
# — so driver code stays greppable against BlockScreenState.kt. The six shield badges:
# Focus session · Schedule · Time limit · Open limit · Deep Focus · Protected.
assert_badge_text() { assert_ui_text "$(uc "$1")"; }

# Polling form of the same contract, for badges that need a beat to compose.
wait_badge_text() { wait_text "$(uc "$1")" "${2:-12}"; }

# The Path B overlay window's title is the bare package name; MainActivity /
# BlockActivity windows carry a "/Class" suffix and never match this.
has_bare_flint_overlay() { grep -Eq 'Window\{[^ ]+ u0 com\.flint\.peakfocus\}' "$1"; }

assert_overlay() {
  windows_dump "$1"
  has_bare_flint_overlay "$OUT_DIR/$1-windows.txt" \
    || _avfail "expected Flint overlay window in $1 (see $1-windows.txt)"
}
assert_no_overlay() {
  windows_dump "$1"
  ! has_bare_flint_overlay "$OUT_DIR/$1-windows.txt" \
    || _avfail "expected NO Flint overlay window in $1 (see $1-windows.txt)"
}

# ---------------------------------------------------------------------------
# input — every UI mutation is a logged verb (contract #3)
# ---------------------------------------------------------------------------

tap_xy() {
  local x="$1" y="$2" why="${3:-}"
  log "tap @ $x $y${why:+ ($why)}"
  "$ADB" shell input tap "$x" "$y"
}

swipe() {
  local x1="$1" y1="$2" x2="$3" y2="$4" ms="${5:-$AV_SWIPE_MS}" why="${6:-}"
  log "swipe ($x1,$y1)->($x2,$y2) ${ms}ms${why:+ — $why}"
  "$ADB" shell input swipe "$x1" "$y1" "$x2" "$y2" "$ms"
}

# `input text` splits on spaces; %s is its space escape. Logged with the literal text.
type_text() {
  local text="$1" escaped
  escaped="$(printf '%s' "$text" | sed 's/ /%s/g')"
  log "type \"$text\""
  "$ADB" shell input text "$escaped"
}

key() {
  local code="$1" why="${2:-}"
  log "key $code${why:+ ($why)}"
  "$ADB" shell input keyevent "$code"
}

# tap_text <text> [first|maxy] — tap a node by exact rendered text. "first" = first
# match in document order; "maxy" = the match lowest on screen (bottom-nav labels that
# also appear in content). XML entities are unescaped before comparison, so pass the
# rendered string ("Time & open limits", not "Time &amp; open limits").
tap_text() {
  local text="$1" mode="${2:-first}" xy
  ui_dump
  xy="$(python3 - "$text" "$mode" "$AV_UI_XML" <<'PY'
import html, re, sys
target, mode, path = sys.argv[1], sys.argv[2], sys.argv[3]
xml = open(path, encoding="utf-8", errors="ignore").read()
hits = []
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    text, l, t, r, b = m.groups()
    if html.unescape(text) == target:
        hits.append((int(t), (int(l) + int(r)) // 2, (int(t) + int(b)) // 2))
if not hits:
    sys.exit(1)
hits.sort(key=lambda h: h[0])
hit = hits[-1] if mode == "maxy" else hits[0]
print(hit[1], hit[2])
PY
)" || {
    local s
    s="$(_avslug "$text")"
    _avfail_artifacts "tap-$s"
    _avfail "no tappable node with text \"$text\" — artifacts: failed-tap-$s.{xml,png}"
    return 1
  }
  log "tap \"$text\" ($mode) @ $xy"
  # shellcheck disable=SC2086 — xy is "X Y" on purpose
  "$ADB" shell input tap $xy
}
