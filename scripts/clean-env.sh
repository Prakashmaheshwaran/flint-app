#!/usr/bin/env bash
# Sourced by cleanup entry points; the bootstrap resolver owns normal tool paths.
if [[ -f "$FLINT_CLEAN_ROOT/scripts/flint-env.sh" ]]; then
  source "$FLINT_CLEAN_ROOT/scripts/flint-env.sh" || printf '%s\n' 'Cleanup: environment resolver unavailable; continuing.' >&2
else
  flint_cleanup_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  if [[ -d "$flint_cleanup_sdk" ]]; then
    export ANDROID_HOME="$flint_cleanup_sdk" ANDROID_SDK_ROOT="$flint_cleanup_sdk"
    export PATH="$flint_cleanup_sdk/platform-tools:$flint_cleanup_sdk/emulator:$PATH"
  fi
fi
