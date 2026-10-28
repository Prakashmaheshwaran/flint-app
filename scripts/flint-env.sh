#!/bin/bash
# Source from a Flint checkout (or set FLINT_ENV_ROOT). Bash 3.2 and zsh compatible.
# No installs, writes, cwd/options changes, or tool requirements beyond awk.
# Successful resolution is silent; unsupported properties fail with a diagnostic.
# Android: ANDROID_HOME > ANDROID_SDK_ROOT > local.properties > macOS default.
_flint_resolve_env() {
    local root sdk java_source xcode_source android_source rest entry resolved candidate
    root=${FLINT_ENV_ROOT:-$PWD}
    case "$root" in /*) ;; *) root="$PWD/$root" ;; esac
    # Walk lexically even when the supplied directory does not exist. Starting
    # absolute guarantees every iteration advances and eventually reaches /.
    while [ "$root" != / ] && [ ! -f "$root/android-app/settings.gradle.kts" ]; do
        root=${root%/*}
        [ -n "$root" ] || root=/
    done
    sdk=
    if [ -r "$root/android-app/local.properties" ]; then
        # Read data, never source it. Support ordinary Properties escaping;
        # reject Unicode/control/continuation syntax rather than misdecode it.
        # Last matching key wins, as in java.util.Properties.
        sdk=$(awk '
            function unsupported() {
                print "ERROR: unsupported escape/control character in local.properties; use a single-line sdk.dir with literal characters or escaped spaces, backslashes and separators." > "/dev/stderr"
                failed = 1
                exit 1
            }
            function decode(s,    j,c,out) {
                out = ""
                for (j = 1; j <= length(s); j++) {
                    c = substr(s,j,1)
                    if (c == "\\") {
                        if (++j > length(s)) unsupported()
                        c = substr(s,j,1)
                        if (c !~ /^[ \\:=#!]$/) unsupported()
                    }
                    if (c ~ /[[:cntrl:]]/) unsupported()
                    out = out c
                }
                return out
            }
            {
                sub(/\r$/, "")
                sub(/^[ \t\f]*/, "")
                if ($0 == "" || $0 ~ /^[#!]/) next
                key = ""
                for (i = 1; i <= length($0); i++) {
                    c = substr($0, i, 1)
                    if (c ~ /[ \t\f=:]/) break
                    key = key c
                    if (c == "\\") key = key substr($0, ++i, 1)
                }
                key = decode(key)
                value = substr($0,i)
                sub(/^[ \t\f]*/, "", value)
                sub(/^[=:][ \t\f]*/, "", value)
                # Even an unrelated continuation could consume the sdk.dir line.
                if (value ~ /\\$/) {
                    n = 0
                    for (j = length(value); j > 0 && substr(value,j,1) == "\\"; j--) n++
                    if (n % 2) unsupported()
                }
                if (key == "sdk.dir") sdk = decode(value)
            }
            END { if (!failed) printf "%s", sdk }
        ' "$root/android-app/local.properties") || return 1
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
if _flint_resolve_env; then
    unset -f _flint_resolve_env
else
    unset -f _flint_resolve_env
    return 1
fi
