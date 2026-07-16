#!/usr/bin/env bash
# check-release-version.sh — refuse to publish a release artifact that lies about its version.
#
#     scripts/check-release-version.sh v0.2.0        # or: make release-check TAG=v0.2.0
#
# Why this exists: .github/workflows/release.yml names the artifact after the git tag
# (flint-<tag>-android-debug.apk), while Gradle stamps the APK itself from versionName and
# versionCode in android-app/app/build.gradle.kts. Nothing tied those two together. Tag
# v0.2.0 without bumping build.gradle.kts and you publish a file called v0.2.0 that installs
# as 0.1.0 — and, because versionCode never moved, Android refuses it as an upgrade over the
# previous release (INSTALL_FAILED_UPDATE_INCOMPATIBLE). That is review P1-1's mislabeled
# artifact one layer down: P1-1 made the release *source* match the tag; it did not make the
# version metadata compiled into the artifact match the tag.
#
# Everything is read from the WORKING TREE, so this runs before the tag exists. That is the
# point — check first, then tag. Keep this device-free so it can also be wired into release
# automation before any build or upload step.
#
#   1. <tag> is vX.Y.Z, optionally with a -suffix        (v0.2.0, v0.2.0-rc.1)
#   2. android versionName  == the tag minus its leading "v"          (0.2.0-rc.1)
#   3. ios MARKETING_VERSION == X.Y.Z, any suffix stripped  (Apple takes digits and dots only)
#   4. android versionCode is a positive integer AND strictly greater than the versionCode at
#      the previous release tag — otherwise the build cannot install over the last release.
#      Skipped LOUDLY when no previous tag is reachable (first release, or a shallow clone:
#      actions/checkout needs fetch-depth: 0).
#   5. CHANGELOG.md carries a "## [X.Y.Z]" section.
#
# Checks 2-5 all run even after one fails, so a bump lands in one pass instead of five.
#
# Exit 0 = safe to tag.  Exit 1 = a check failed.  Exit 2 = bad usage or unparseable input.
#
#   ROOT=<dir>   repo to inspect (default: the repo this script lives in)
#
# Portability: bash 3.2 (macOS /bin/bash) — no ${var^^}, no mapfile, no assoc arrays.
# Proven by scripts/check-release-version-selftest.sh (no network, no toolchain).
set -uo pipefail

ROOT="${ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
GRADLE_REL="android-app/app/build.gradle.kts"
GRADLE="$ROOT/$GRADLE_REL"
PROJECT_YML="$ROOT/ios-app/project.yml"
CHANGELOG="$ROOT/CHANGELOG.md"

TAG="${1:-}"
if [[ -z "$TAG" ]]; then
  echo "usage: $(basename "$0") <tag>    e.g. $(basename "$0") v0.2.0" >&2
  exit 2
fi

FAILS=0
ok()   { printf 'ok   - %s\n' "$*"; }
skip() { printf 'SKIP - %s\n' "$*"; }
bad()  { FAILS=$((FAILS + 1)); printf 'FAIL - %s\n' "$*"; }

# Take the first line of a multi-line capture. Avoids `sed ... | head -1`, whose SIGPIPE
# trips `set -o pipefail`.
_first() { printf '%s' "${1%%$'\n'*}"; }

# All three parsers read stdin, so they work equally on a working-tree file and on
# `git show <tag>:<path>`.
parse_version_name() {
  local out; out="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p')"
  _first "$out"
}
parse_version_code() {
  local out; out="$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p')"
  _first "$out"
}
parse_marketing_version() {
  local out; out="$(sed -n 's/^[[:space:]]*MARKETING_VERSION:[[:space:]]*"\{0,1\}\([0-9][0-9.]*\)"\{0,1\}[[:space:]]*$/\1/p')"
  _first "$out"
}

# Escape a literal version for use inside a grep basic-regex (0.1.0 -> 0\.1\.0).
_re_escape() { printf '%s' "$1" | sed 's/\./\\./g'; }

for f in "$GRADLE" "$PROJECT_YML" "$CHANGELOG"; do
  [[ -r "$f" ]] || { echo "cannot read $f (ROOT=$ROOT)" >&2; exit 2; }
done

echo "=== release version check — tag $TAG (ROOT=$ROOT) ==="

# 1. tag shape. Everything downstream is derived from it, so a bad tag is fatal now.
if [[ ! "$TAG" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)(-[0-9A-Za-z.-]+)?$ ]]; then
  echo "FAIL - tag \"$TAG\" is not vX.Y.Z or vX.Y.Z-suffix (e.g. v0.2.0, v0.2.0-rc.1)" >&2
  exit 1
fi
CORE="${BASH_REMATCH[1]}"          # 0.2.0        — the semver core, no suffix
WANT_NAME="${TAG#v}"               # 0.2.0-rc.1   — what versionName must equal
ok "tag \"$TAG\" is a semver release tag (core $CORE)"

# 2. android versionName == tag without the leading "v"
NAME="$(parse_version_name < "$GRADLE")"
if [[ -z "$NAME" ]]; then
  echo "cannot parse versionName from $GRADLE_REL" >&2
  exit 2
elif [[ "$NAME" == "$WANT_NAME" ]]; then
  ok "android versionName $NAME matches $TAG"
else
  bad "android versionName is $NAME but the tag says $WANT_NAME — the APK would be published as \"$TAG\" and install as \"$NAME\". Bump versionName in $GRADLE_REL."
fi

# 3. ios MARKETING_VERSION == the semver core (CFBundleShortVersionString is digits + dots)
MKT="$(parse_marketing_version < "$PROJECT_YML")"
if [[ -z "$MKT" ]]; then
  echo "cannot parse MARKETING_VERSION from ios-app/project.yml" >&2
  exit 2
elif [[ "$MKT" == "$CORE" ]]; then
  ok "ios MARKETING_VERSION $MKT matches $CORE"
else
  bad "ios MARKETING_VERSION is $MKT but the tag says $CORE — bump MARKETING_VERSION in ios-app/project.yml."
fi

# 4. android versionCode: a positive integer, strictly greater than the last release's.
#    Equal versionCodes are the upgrade-breaker, so this is the check that matters most.
CODE="$(parse_version_code < "$GRADLE")"
if [[ -z "$CODE" ]]; then
  echo "cannot parse versionCode from $GRADLE_REL" >&2
  exit 2
elif [[ "$CODE" -le 0 ]]; then
  bad "android versionCode is $CODE — must be a positive integer."
else
  # The most recent vX.Y.Z tag that is not the one being released. Version-sorted by git,
  # so no dependency on `sort -V` (absent from BSD sort).
  PREV="$(git -C "$ROOT" tag -l 'v[0-9]*' --sort=-v:refname 2>/dev/null | grep -vxF "$TAG" | sed -n '1p')"
  if [[ -z "$PREV" ]]; then
    skip "no previous release tag reachable — cannot compare versionCode $CODE. First release, or a shallow clone (actions/checkout needs fetch-depth: 0)."
  else
    PREV_CODE="$(git -C "$ROOT" show "$PREV:$GRADLE_REL" 2>/dev/null | parse_version_code)"
    if [[ -z "$PREV_CODE" ]]; then
      skip "could not read versionCode at $PREV — cannot compare against $CODE."
    elif [[ "$CODE" -gt "$PREV_CODE" ]]; then
      ok "android versionCode $CODE > $PREV_CODE at $PREV"
    else
      bad "android versionCode is $CODE but $PREV already shipped $PREV_CODE — Android refuses to install a build whose versionCode did not increase (INSTALL_FAILED_UPDATE_INCOMPATIBLE). Bump versionCode in $GRADLE_REL."
    fi
  fi
fi

# 5. CHANGELOG section. A release with no changelog entry is a release nobody can read.
if grep -q "^## \[$(_re_escape "$CORE")\]" "$CHANGELOG"; then
  ok "CHANGELOG.md has a \"## [$CORE]\" section"
else
  bad "CHANGELOG.md has no \"## [$CORE]\" section — move the Unreleased entries under it."
fi

echo
if [[ "$FAILS" -eq 0 ]]; then
  echo "OK — $TAG is consistent with the tree. Safe to tag and publish."
  exit 0
fi
echo "$FAILS check(s) failed — fix them before tagging $TAG."
exit 1
