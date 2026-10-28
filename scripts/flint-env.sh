#!/bin/bash
# Source from a Flint checkout (or set FLINT_ENV_ROOT). Bash 3.2 and zsh compatible.
# No installs, writes, output, cwd/options changes, or tool requirements beyond awk.
# Android: ANDROID_HOME > ANDROID_SDK_ROOT > local.properties > macOS default.
_flint_resolve_env() {
    local root sdk java_source xcode_source android_source rest entry resolved candidate
    root=${FLINT_ENV_ROOT:-$PWD}
    while [ "$root" != / ] && [ ! -f "$root/android-app/settings.gradle.kts" ]; do
        root=${root%/*}
        [ -n "$root" ] || root=/
    done
    sdk=
    if [ -r "$root/android-app/local.properties" ]; then
        # Read data, never source it. Android Studio escapes spaces, colons and '\'.
        # Last matching key wins, as in java.util.Properties.
        sdk=$(awk '
            /^[ \t]*sdk[.]dir([ \t]*[=:]|[ \t]+)/ {
                sub(/^[ \t]*sdk[.]dir[ \t]*/, "")
                sub(/^[=:][ \t]*/, "")
                sub(/\r$/, "")
                value = ""
                for (i = 1; i <= length($0); i++) {
                    c = substr($0, i, 1)
                    if (c == "\\" && i < length($0)) c = substr($0, ++i, 1)
                    value = value c
                }
                sdk = value
            }
            END { printf "%s", sdk }
        ' "$root/android-app/local.properties")
        case "$sdk" in ''|/*) ;; *) sdk="$root/android-app/$sdk" ;; esac
    fi
    export FLINT_ENV_LOCAL_SDK="$sdk"

    if [ -n "${ANDROID_HOME:-}" ]; then
        android_source=env:ANDROID_HOME
        if [ "$ANDROID_HOME" = "${FLINT_ENV_ANDROID_VALUE:-}" ]; then
            android_source=${FLINT_ENV_ANDROID_SOURCE:-$android_source}
        fi
    elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
        ANDROID_HOME=$ANDROID_SDK_ROOT
        android_source=env:ANDROID_SDK_ROOT
    elif [ -n "$sdk" ]; then
        ANDROID_HOME=$sdk
        android_source=local.properties:sdk.dir
    else
        ANDROID_HOME=${HOME:-}/Library/Android/sdk
        android_source=default:~/Library/Android/sdk
    fi
    export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
    export FLINT_ENV_ANDROID_VALUE="$ANDROID_HOME" FLINT_ENV_ANDROID_SOURCE="$android_source"

    if [ -n "${JAVA_HOME:-}" ]; then
        java_source=env:JAVA_HOME
        if [ "$JAVA_HOME" = "${FLINT_ENV_JAVA_VALUE:-}" ]; then
            java_source=${FLINT_ENV_JAVA_SOURCE:-$java_source}
        fi
    else
        JAVA_HOME=/opt/homebrew/opt/openjdk@21
        java_source=default:Homebrew-openjdk@21
        if [ ! -x "$JAVA_HOME/bin/java" ] && [ -x /usr/libexec/java_home ]; then
            candidate=$(/usr/libexec/java_home -v 21 2>/dev/null) || candidate=
            if [ -n "$candidate" ]; then
                JAVA_HOME=$candidate
                java_source=default:java_home-21
            fi
        fi
    fi
    export JAVA_HOME FLINT_ENV_JAVA_VALUE="$JAVA_HOME" FLINT_ENV_JAVA_SOURCE="$java_source"

    if [ -n "${DEVELOPER_DIR:-}" ]; then
        xcode_source=env:DEVELOPER_DIR
        if [ "$DEVELOPER_DIR" = "${FLINT_ENV_XCODE_VALUE:-}" ]; then
            xcode_source=${FLINT_ENV_XCODE_SOURCE:-$xcode_source}
        fi
    elif [ -n "${XCODE_DEV:-}" ]; then
        DEVELOPER_DIR=$XCODE_DEV
        xcode_source=env:XCODE_DEV
    else
        # Expand in sh so a missing Xcode cannot trip zsh's NOMATCH option.
        # Same lexical latest-Xcode rule as the original Makefile, without ls.
        DEVELOPER_DIR=$(/bin/sh -c '
            selected=
            for app in /Applications/Xcode*.app; do
                [ ! -d "$app/Contents/Developer" ] || selected="$app/Contents/Developer"
            done
            printf "%s" "$selected"
        ')
        xcode_source='default:latest-/Applications/Xcode*.app'
    fi
    export DEVELOPER_DIR XCODE_DEV="$DEVELOPER_DIR"
    export FLINT_ENV_XCODE_VALUE="$DEVELOPER_DIR" FLINT_ENV_XCODE_SOURCE="$xcode_source"

    # Walk quoted colon-separated entries; preserve spaces/globs and empty entries.
    # Exact entry matching removes existing duplicates as well as our additions.
    rest="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$JAVA_HOME/bin:${PATH:-}"
    resolved=
    while :; do
        entry=${rest%%:*}
        case ":$resolved:" in *":$entry:"*) ;; *) resolved=${resolved:+$resolved:}$entry ;; esac
        case "$rest" in *:*) rest=${rest#*:} ;; *) break ;; esac
    done
    export PATH="$resolved"
}
_flint_resolve_env
unset -f _flint_resolve_env
