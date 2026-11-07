#!/usr/bin/env bash
# setup-android-upload-key.sh — create the Play Store upload key, once, on a developer machine.
#
#     scripts/setup-android-upload-key.sh            # prompts for both passwords
#     FLINT_KEYSTORE_PASSWORD=... FLINT_KEY_PASSWORD=... scripts/setup-android-upload-key.sh
#
# Why this exists: `android-app/app/build.gradle.kts` signs release builds with the *debug* key
# so preview APKs assemble everywhere. Google Play will not accept that key as an upload key you
# control, and a debug key cannot be rotated through Play. This script creates a dedicated
# RSA-2048 upload keystore (10 000 days, Google's guidance), writes the git-ignored
# `android-app/keystore.properties` that Gradle reads, and prints the Play Console steps.
#
# The keystore and its passwords are the app's upload identity. They are NOT secrets you can
# regenerate: after the first AAB reaches Play, a new upload key requires a Play support reset.
# Back the .keystore up somewhere durable and store the passwords in a password manager.
#
# Deliberately never prints a password. Refuses to overwrite an existing keystore or properties
# file unless FORCE=1, because silently replacing the key would break future uploads.
#
# Overrides (used by scripts/test_setup_android_upload_key.py):
#   ROOT              repo root (default: the repo this script lives in)
#   KEYTOOL           keytool binary (default: the one on PATH)
#   KEYSTORE_DIR      where the keystore and properties live (default: $ROOT/android-app)
#   KEYSTORE_NAME     keystore filename (default: flint-upload.keystore)
#   KEY_ALIAS         key alias (default: flint-upload)
#   DNAME             keytool -dname value
#   FORCE=1           replace an existing keystore and properties file
#
# Portability: bash 3.2 (macOS /bin/bash) — no ${var,,}, no mapfile, no assoc arrays.
set -uo pipefail

ROOT="${ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
KEYTOOL="${KEYTOOL:-keytool}"
KEYSTORE_DIR="${KEYSTORE_DIR:-$ROOT/android-app}"
KEYSTORE_NAME="${KEYSTORE_NAME:-flint-upload.keystore}"
KEY_ALIAS="${KEY_ALIAS:-flint-upload}"
DNAME="${DNAME:-CN=Flint Upload Key, OU=Mobile, O=Flint, L=Local, ST=Local, C=US}"
FORCE="${FORCE:-0}"

KEYSTORE="$KEYSTORE_DIR/$KEYSTORE_NAME"
PROPERTIES="$KEYSTORE_DIR/keystore.properties"

die() { printf '%s\n' "$*" >&2; exit 1; }

command -v "$KEYTOOL" >/dev/null 2>&1 || \
    die "keytool not found. Install a JDK 17+ (make env shows the resolved JAVA_HOME)."

[ -d "$KEYSTORE_DIR" ] || die "No Android app directory at $KEYSTORE_DIR"

if [ "$FORCE" != "1" ]; then
    [ -e "$KEYSTORE" ] && die "Refusing to overwrite $KEYSTORE (FORCE=1 to replace).
That file is the app's upload identity; if it is already registered with Play, replacing it
breaks every future upload. Back it up instead of regenerating it."
    [ -e "$PROPERTIES" ] && die "Refusing to overwrite $PROPERTIES (FORCE=1 to replace)."
fi

# Passwords: environment wins, otherwise prompt without echo.
STORE_PASSWORD="${FLINT_KEYSTORE_PASSWORD:-}"
KEY_PASSWORD="${FLINT_KEY_PASSWORD:-}"

if [ -z "$STORE_PASSWORD" ]; then
    printf 'Upload keystore password (min 6 chars): ' >&2
    IFS= read -r -s STORE_PASSWORD || die "No password read."
    printf '\n' >&2
fi
if [ -z "$KEY_PASSWORD" ]; then
    printf 'Key password (press Return to reuse the keystore password): ' >&2
    IFS= read -r -s KEY_PASSWORD || die "No password read."
    printf '\n' >&2
    [ -n "$KEY_PASSWORD" ] || KEY_PASSWORD="$STORE_PASSWORD"
fi

[ "${#STORE_PASSWORD}" -ge 6 ] || die "Keystore password must be at least 6 characters."
[ "${#KEY_PASSWORD}" -ge 6 ] || die "Key password must be at least 6 characters."

# Properties files treat \ : = # ! and leading whitespace specially. Escape rather than
# reject, so any password keytool accepts also round-trips through Gradle.
escape_property() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/:/\\:/g' -e 's/=/\\=/g' \
        -e 's/^[[:space:]]/\\ /' -e 's/\([#!]\)/\\\1/g'
}

umask 077

# Hand the passwords to keytool through the environment, never argv: a command line is visible
# to `ps` for the life of the process. `-storepass:env` needs JDK 9+ (make env shows the JDK).
FLINT_KEYTOOL_STOREPASS="$STORE_PASSWORD"
FLINT_KEYTOOL_KEYPASS="$KEY_PASSWORD"
export FLINT_KEYTOOL_STOREPASS FLINT_KEYTOOL_KEYPASS

"$KEYTOOL" -genkeypair \
    -keystore "$KEYSTORE" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass:env FLINT_KEYTOOL_STOREPASS -keypass:env FLINT_KEYTOOL_KEYPASS \
    -dname "$DNAME" \
    || die "keytool failed; no keystore.properties was written."

{
    printf '%s\n' \
        "# Play upload signing for app.flint.focus. GIT-IGNORED — never commit this file." \
        "# Created by scripts/setup-android-upload-key.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ)." \
        "# storeFile is resolved relative to android-app/ (the Gradle root project)."
    printf 'storeFile=%s\n' "$(escape_property "$KEYSTORE_NAME")"
    printf 'storePassword=%s\n' "$(escape_property "$STORE_PASSWORD")"
    printf 'keyAlias=%s\n' "$(escape_property "$KEY_ALIAS")"
    printf 'keyPassword=%s\n' "$(escape_property "$KEY_PASSWORD")"
} > "$PROPERTIES" || die "Could not write $PROPERTIES"

chmod 600 "$KEYSTORE" "$PROPERTIES" 2>/dev/null || true

FINGERPRINT="$("$KEYTOOL" -list -v -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
    -storepass:env FLINT_KEYTOOL_STOREPASS 2>/dev/null | sed -n 's/^[[:space:]]*SHA256: //p' | head -1)"
unset FLINT_KEYTOOL_STOREPASS FLINT_KEYTOOL_KEYPASS

cat <<EOF

Upload key created.

  keystore     $KEYSTORE
  properties   $PROPERTIES  (git-ignored)
  alias        $KEY_ALIAS
  SHA-256      ${FINGERPRINT:-unavailable}

Next:
  1. Back up the .keystore file and both passwords somewhere durable. Losing them means a Play
     support reset before you can upload again.
  2. Build the Play artifact:  make android-bundle
     -> android-app/app/build/outputs/bundle/release/app-release.aab
  3. Play Console -> your app -> Test and release -> App integrity -> Play App Signing.
     Upload the .aab and Google registers this certificate as the upload key; downloads use
     Play's own app-signing key.
  4. Keep keystore.properties out of any archive you share. It holds the passwords in clear text
     because Gradle needs them at build time.

Until this file exists, release builds keep using the debug key (preview APKs only).
EOF
