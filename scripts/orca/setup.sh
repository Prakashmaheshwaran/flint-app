#!/bin/bash
# Offline worktree bootstrap. No Gradle/Xcode build and no device operations.
set -eu
FLINT_ENV_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
export FLINT_ENV_ROOT
cd "$FLINT_ENV_ROOT"
. scripts/flint-env.sh

if [ ! -f android-app/local.properties ]; then
    if [ -d "$ANDROID_HOME" ]; then
        if [ "$(printf '%s' "$ANDROID_HOME" | LC_ALL=C tr -d '[:cntrl:]')" != "$ANDROID_HOME" ]; then
            printf '%s\n' 'ERROR: SDK path contains unsupported control characters; local.properties was not written.' >&2
            exit 1
        fi
        # Escape Java properties metacharacters, including spaces in an SDK path.
        sdk_property=$(printf '%s' "$ANDROID_HOME" | sed 's/\\/\\\\/g; s/ /\\ /g; s/:/\\:/g; s/=/\\=/g; s/#/\\#/g; s/!/\\!/g')
        # Noclobber also protects a file created concurrently by Android Studio.
        if (set -C; printf 'sdk.dir=%s\n' "$sdk_property" > android-app/local.properties) 2>/dev/null; then
            printf '%s\n' 'Created android-app/local.properties from the resolved SDK.'
        elif [ ! -f android-app/local.properties ]; then
            printf '%s\n' 'ERROR: could not create android-app/local.properties.' >&2
            exit 1
        fi
    else
        printf '%s\n' "WARNING: Android SDK missing at $ANDROID_HOME; Android builds unavailable." >&2
    fi
else
    printf '%s\n' 'Preserved existing android-app/local.properties.'
fi

[ -x "$JAVA_HOME/bin/java" ] || printf '%s\n' "WARNING: JDK missing at $JAVA_HOME; Android builds unavailable." >&2
[ -d "$DEVELOPER_DIR" ] || printf '%s\n' 'WARNING: Xcode missing; iOS builds unavailable.' >&2
for tool in adb emulator sdkmanager avdmanager; do
    command -v "$tool" >/dev/null 2>&1 || printf 'WARNING: %s unavailable; install the corresponding Android SDK package when needed.\n' "$tool" >&2
done
# Make is necessary to run the bootstrap contract. XcodeGen is needed to produce
# the generated project; fail loudly instead of claiming an incomplete setup.
command -v make >/dev/null 2>&1 || { printf '%s\n' 'ERROR: make is required for setup.' >&2; exit 1; }
if ! command -v xcodegen >/dev/null 2>&1; then
    make env
    printf '%s\n' 'ERROR: XcodeGen is required for setup (install xcodegen, then rerun).' >&2
    exit 1
fi
make ios-gen
make env
