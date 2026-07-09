#!/usr/bin/env bash
# check-release-version-selftest.sh — proves scripts/check-release-version.sh's contracts
# against fixture repos. No network, no Gradle, no Xcode, no Android SDK, no device: run it
# anywhere bash 3.2+ and git exist (same spirit as scripts/android-verify-selftest.sh).
# Exit 0 = every guarantee the checker documents actually holds:
#
#   * a consistent tree passes; each of the four inconsistencies fails on its own;
#   * all of checks 2-5 report in ONE pass (a bump lands in one edit, not five);
#   * a versionCode that did not increase past the previous tag fails, and says
#     INSTALL_FAILED_UPDATE_INCOMPATIBLE — the reason the check exists;
#   * a malformed tag is fatal before any version is read (nothing downstream is derived
#     from a tag we could not parse);
#   * pre-release tags (v0.2.0-rc.1) match versionName in full but MARKETING_VERSION only
#     on the semver core, because CFBundleShortVersionString takes digits and dots;
#   * a missing previous tag SKIPs loudly instead of silently passing — a shallow clone
#     must never look like a clean upgrade path;
#   * unreadable/unparseable inputs exit 2 (usage), never 0.
#
# Usage:  scripts/check-release-version-selftest.sh
#   CRV_SELFTEST_WORK=<dir>  work dir (default: mktemp -d; removed on success)
#   SCRIPT=<path>            checker under test (default: beside this script)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT:-$HERE/check-release-version.sh}"
[[ -f "$SCRIPT" ]] || { echo "checker not found at $SCRIPT" >&2; exit 2; }
KEEP_WORK="${CRV_SELFTEST_WORK:-}"
WORK="${CRV_SELFTEST_WORK:-$(mktemp -d "${TMPDIR:-/tmp}/crv-selftest.XXXXXX")}"
mkdir -p "$WORK"

PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); echo "ok   - $*"; }
bad() { FAIL=$((FAIL + 1)); echo "FAIL - $*"; }
t()   { if "${@:2}"; then ok "$1"; else bad "$1"; fi; }

# ---------------------------------------------------------------------------
# fixtures
# ---------------------------------------------------------------------------

# fixture <dir> <versionName> <versionCode> <MARKETING_VERSION> <changelogVersion>
# Shapes mirror the real files closely enough that the parsers are exercised as written
# (leading indentation, quoted Kotlin strings, quoted YAML scalar).
fixture() {
  local d="$1" name="$2" code="$3" mkt="$4" cl="$5"
  mkdir -p "$d/android-app/app" "$d/ios-app"
  cat > "$d/android-app/app/build.gradle.kts" <<EOF
android {
    namespace = "com.flint.peakfocus"
    defaultConfig {
        applicationId = "com.flint.peakfocus"
        minSdk = 23
        versionCode = $code
        versionName = "$name"
    }
}
EOF
  cat > "$d/ios-app/project.yml" <<EOF
settings:
    base:
        MARKETING_VERSION: "$mkt"
        CURRENT_PROJECT_VERSION: "1"
EOF
  printf '# Changelog\n\n## [Unreleased]\n\n## [%s] — a release\n' "$cl" > "$d/CHANGELOG.md"
}

# git_tag_here <dir> <tag> — commit whatever the fixture currently holds and tag it, so a
# later fixture() call leaves that tag as the "previous release" to compare against.
git_tag_here() {
  local d="$1" tag="$2"
  git -C "$d" init -q >/dev/null 2>&1
  git -C "$d" add -A >/dev/null 2>&1
  git -C "$d" -c user.email=selftest@flint.invalid -c user.name=selftest \
    -c commit.gpgsign=false commit -qm "release $tag" >/dev/null 2>&1
  git -C "$d" tag "$tag" >/dev/null 2>&1
}

# check <dir> <tag> — run the checker; exposes RC and OUT.
check() {
  RC=0
  OUT="$(ROOT="$1" bash "$SCRIPT" "$2" 2>&1)" || RC=$?
}
# Invoked indirectly, as the command `t` runs. shellcheck disable=SC2329 for all three.
# shellcheck disable=SC2329
has()     { printf '%s\n' "$OUT" | grep -qF -- "$1"; }
# shellcheck disable=SC2329
not()     { ! "$@"; }
# shellcheck disable=SC2329
fail_n()  { printf '%s\n' "$OUT" | grep -c '^FAIL' | tr -d ' '; }

echo "=== check-release-version.sh selftest (checker: $SCRIPT) ==="

# 1. a consistent tree passes, and says so
D="$WORK/clean"; fixture "$D" "0.2.0" 2 "0.2.0" "0.2.0"
check "$D" v0.2.0
t "consistent tree exits 0"                     test "$RC" -eq 0
t "consistent tree reports OK"                  has "OK — v0.2.0 is consistent"
t "no previous tag SKIPs loudly (not a pass)"   has "SKIP - no previous release tag reachable"
t "SKIP names the shallow-clone cause"          has "fetch-depth: 0"

# 2. the live defect: tag moved, versionName did not
D="$WORK/stale-name"; fixture "$D" "0.1.0" 2 "0.2.0" "0.2.0"
check "$D" v0.2.0
t "stale versionName exits 1"                   test "$RC" -eq 1
t "stale versionName names both versions"       has 'android versionName is 0.1.0 but the tag says 0.2.0'
t "stale versionName spells out the symptom"    has 'published as "v0.2.0" and install as "0.1.0"'

# 3. iOS marketing version left behind
D="$WORK/stale-mkt"; fixture "$D" "0.2.0" 2 "0.1.0" "0.2.0"
check "$D" v0.2.0
t "stale MARKETING_VERSION exits 1"             test "$RC" -eq 1
t "stale MARKETING_VERSION is named"            has "ios MARKETING_VERSION is 0.1.0 but the tag says 0.2.0"

# 4. changelog section missing
D="$WORK/no-changelog"; fixture "$D" "0.2.0" 2 "0.2.0" "0.1.0"
check "$D" v0.2.0
t "missing CHANGELOG section exits 1"           test "$RC" -eq 1
t "missing CHANGELOG section is named"          has 'CHANGELOG.md has no "## [0.2.0]" section'

# 5. every check reports in one pass — not just the first failure
D="$WORK/all-bad"; fixture "$D" "0.1.0" 1 "0.1.0" "0.1.0"
check "$D" v0.2.0
t "all-bad tree exits 1"                        test "$RC" -eq 1
t "3 independent FAILs in one pass"             test "$(fail_n)" -eq 3
t "summary counts the failures"                 has "3 check(s) failed"

# 6. versionCode monotonicity against the previous tag — the upgrade-breaker
D="$WORK/code-same"; fixture "$D" "0.1.0" 1 "0.1.0" "0.1.0"; git_tag_here "$D" v0.1.0
fixture "$D" "0.2.0" 1 "0.2.0" "0.2.0"          # bumped everything BUT versionCode
check "$D" v0.2.0
t "unbumped versionCode exits 1"                test "$RC" -eq 1
t "names the shipped code and the tag"          has "versionCode is 1 but v0.1.0 already shipped 1"
t "says INSTALL_FAILED_UPDATE_INCOMPATIBLE"     has "INSTALL_FAILED_UPDATE_INCOMPATIBLE"
t "versionCode is the ONLY failure"             test "$(fail_n)" -eq 1

D="$WORK/code-bumped"; fixture "$D" "0.1.0" 1 "0.1.0" "0.1.0"; git_tag_here "$D" v0.1.0
fixture "$D" "0.2.0" 2 "0.2.0" "0.2.0"
check "$D" v0.2.0
t "bumped versionCode exits 0"                  test "$RC" -eq 0
t "logs the comparison it made"                 has "android versionCode 2 > 1 at v0.1.0"

D="$WORK/code-back"; fixture "$D" "0.2.0" 5 "0.2.0" "0.2.0"; git_tag_here "$D" v0.2.0
fixture "$D" "0.3.0" 3 "0.3.0" "0.3.0"          # went backwards
check "$D" v0.3.0
t "regressed versionCode exits 1"               test "$RC" -eq 1

D="$WORK/code-zero"; fixture "$D" "0.2.0" 0 "0.2.0" "0.2.0"
check "$D" v0.2.0
t "versionCode 0 exits 1"                       test "$RC" -eq 1
t "versionCode 0 named as non-positive"         has "must be a positive integer"

# 7. malformed tags die before any version is read
for t_bad in "0.2.0" "v0.2" "v0.2.0.1" "release-1" "" ; do
  D="$WORK/badtag"; fixture "$D" "0.2.0" 2 "0.2.0" "0.2.0"
  check "$D" "$t_bad"
  if [[ -z "$t_bad" ]]; then
    t "empty tag exits 2 (usage)"               test "$RC" -eq 2
  else
    t "tag \"$t_bad\" rejected (exit 1)"        test "$RC" -eq 1
  fi
done
D="$WORK/badtag2"; fixture "$D" "0.2.0" 2 "0.2.0" "0.2.0"
check "$D" "v0.2"
t "bad tag short-circuits before versionName"   test "$RC" -eq 1
t "bad tag reads no versions"                   not has "android versionName"

# 8. pre-release: versionName carries the suffix, MARKETING_VERSION only the core
D="$WORK/rc"; fixture "$D" "0.2.0-rc.1" 2 "0.2.0" "0.2.0"
check "$D" v0.2.0-rc.1
t "v0.2.0-rc.1 with matching tree exits 0"      test "$RC" -eq 0
t "suffixed tag reports its semver core"        has "core 0.2.0"
D="$WORK/rc-bad"; fixture "$D" "0.2.0" 2 "0.2.0" "0.2.0"
check "$D" v0.2.0-rc.1
t "suffixed tag needs suffixed versionName"     test "$RC" -eq 1

# 9. unreadable / unparseable inputs are usage errors (exit 2), never a silent pass
D="$WORK/missing"; mkdir -p "$D"
check "$D" v0.2.0
t "missing files exit 2 (not 0, not 1)"         test "$RC" -eq 2
t "missing files name the unreadable path"      has "cannot read"

D="$WORK/unparseable"; fixture "$D" "0.2.0" 2 "0.2.0" "0.2.0"
printf 'android { defaultConfig { } }\n' > "$D/android-app/app/build.gradle.kts"
check "$D" v0.2.0
t "unparseable versionName exits 2"             test "$RC" -eq 2
t "unparseable versionName is named"            has "cannot parse versionName"

# 10. a non-git ROOT SKIPs the tag comparison rather than erroring or passing it silently
D="$WORK/nogit"; fixture "$D" "0.2.0" 2 "0.2.0" "0.2.0"
check "$D" v0.2.0
t "non-git ROOT exits 0 with a SKIP"            test "$RC" -eq 0
t "non-git ROOT does not claim a comparison"    not has "android versionCode 2 >"

echo "=== $PASS passed, $FAIL failed (work: $WORK) ==="
if [[ "$FAIL" -eq 0 ]]; then
  [[ -z "$KEEP_WORK" ]] && rm -rf "$WORK"
  echo "OK"
  exit 0
fi
exit 1
