#!/bin/bash
# Subshell changes below are intentionally isolated when comparing fallback paths.
# shellcheck disable=SC2031
set -eu
FLINT_ENV_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
incoming_path=${PATH:-}
. "$FLINT_ENV_ROOT/scripts/flint-env.sh"

sdk_conflict() {
    [ -n "$FLINT_ENV_LOCAL_SDK" ] && [ "$FLINT_ENV_LOCAL_SDK" != "$ANDROID_HOME" ] &&
        ! [ "$FLINT_ENV_LOCAL_SDK" -ef "$ANDROID_HOME" ]
}
if [ "${1:-}" = --check-android ]; then
    if sdk_conflict; then
        printf '%s\n' "ERROR: local.properties sdk.dir=$FLINT_ENV_LOCAL_SDK differs from resolved ANDROID_HOME=$ANDROID_HOME." \
            'Gradle prefers sdk.dir; reconcile these paths before building (the file is never overwritten automatically).' >&2
        exit 1
    fi
    exit 0
fi

printf '%s\n' '== Flint toolchain (source; path exists) =='
for name in ANDROID_HOME ANDROID_SDK_ROOT JAVA_HOME DEVELOPER_DIR XCODE_DEV; do
    case "$name" in
        ANDROID*) value=$ANDROID_HOME; origin=$FLINT_ENV_ANDROID_SOURCE ;;
        JAVA_HOME) value=$JAVA_HOME; origin=$FLINT_ENV_JAVA_SOURCE ;;
        *) value=$DEVELOPER_DIR; origin=$FLINT_ENV_XCODE_SOURCE ;;
    esac
    present=missing
    [ ! -d "$value" ] || present=exists
    printf '%-17s %s [%s; %s]\n' "$name" "${value:-<unset>}" "$origin" "$present"
done
printf '%-17s %s\n' PATH "$PATH"
for tool in xcodebuild xcodegen java adb emulator sdkmanager avdmanager orca; do
    location=$(command -v "$tool" 2>/dev/null) || location='MISSING (not on PATH)'
    printf '%-17s %s\n' "$tool" "$location"
done
# A generic shell-startup shim may outlive this checkout's defaults. Preserve its
# exports, but make drift visible without maintaining a second path resolver.
for entry in "$ANDROID_HOME/platform-tools" "$ANDROID_HOME/emulator"; do
    case ":$incoming_path:" in
        *":$entry:"*) ;;
        *) printf '%s\n' "WARNING: shell PATH lacked $entry; the checkout resolver restored it (see scripts/flint-zshenv.example)." >&2 ;;
    esac
done
fallback_sdk=$(
    unset ANDROID_HOME ANDROID_SDK_ROOT
    . "$FLINT_ENV_ROOT/scripts/flint-env.sh"
    printf '%s' "$ANDROID_HOME"
)
fallback_java=$(
    unset JAVA_HOME
    . "$FLINT_ENV_ROOT/scripts/flint-env.sh"
    printf '%s' "$JAVA_HOME"
)
if [ "$ANDROID_HOME" != "$fallback_sdk" ]; then
    printf '%s\n' "WARNING: shell ANDROID_HOME=$ANDROID_HOME differs from checkout fallback $fallback_sdk; existing environment wins." >&2
fi
if [ "$JAVA_HOME" != "$fallback_java" ]; then
    printf '%s\n' "WARNING: shell JAVA_HOME=$JAVA_HOME differs from checkout fallback $fallback_java; existing environment wins." >&2
fi
if sdk_conflict; then
    printf '%s\n' "WARNING: local.properties sdk.dir=$FLINT_ENV_LOCAL_SDK conflicts with ANDROID_HOME; Android make targets will refuse to use a different SDK." >&2
fi
