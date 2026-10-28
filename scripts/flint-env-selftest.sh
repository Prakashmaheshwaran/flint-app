#!/bin/bash
# Device-free resolver/Make parity tests; real tool installations are not required.
# Test payloads deliberately defer expansion to child shells or keep literal data.
# shellcheck disable=SC2016
set -eu
# This limit is local to the selftest process and its children, never the caller.
# Fixtures are tiny; a runaway diagnostic must not fill the host's disk.
ulimit -c 0
ulimit -f 1024
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
WORK=$(mktemp -d "${TMPDIR:-/tmp}/flint-env-selftest.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
. "$ROOT/scripts/selftest-isolation.sh"
FIXTURE="$WORK/checkout with spaces"
TEST_HOME="$WORK/home"
FAKE_BIN="$WORK/fake-bin"
mkdir -p "$FIXTURE/scripts" "$FIXTURE/android-app" "$TEST_HOME/Library/Android/sdk" "$FAKE_BIN"
: > "$WORK/tools.log"
for tool in xcrun xcodebuild xcodegen adb emulator sdkmanager avdmanager orca; do
    cat > "$FAKE_BIN/$tool" <<'FAKE'
#!/bin/sh
printf '%s %s\n' "${0##*/}" "$*" >> "$FAKE_TOOL_LOG"
echo "Unexpected tool execution in env selftest: ${0##*/}" >&2
exit 90
FAKE
    chmod +x "$FAKE_BIN/$tool"
done
cp "$ROOT/scripts/flint-env.sh" "$ROOT/scripts/flint-shell.sh" "$ROOT/scripts/flint-env-report.sh" "$FIXTURE/scripts/"
cp "$ROOT/Makefile" "$FIXTURE/Makefile"
: > "$FIXTURE/android-app/settings.gradle.kts"
CHECKS=0
eq() {
    if [ "$2" != "$3" ]; then
        printf 'FAIL - %s\nexpected: %s\nactual: %s\n' "$1" "$2" "$3" >&2
        exit 1
    fi
    CHECKS=$((CHECKS + 1)); printf 'ok - %s\n' "$1"
}
probe() {
    env -i HOME="$TEST_HOME" PATH="$FAKE_BIN:/usr/bin:/bin" FLINT_ENV_ROOT="$FIXTURE" \
        FAKE_TOOL_LOG="$WORK/tools.log" ORCA_CLI_COMMAND="$FAKE_BIN/orca" \
        FLINT_SCRIPT="$FIXTURE/scripts/flint-env.sh" "$@" /bin/bash -eu -c \
        '. "$FLINT_SCRIPT"; printf "%s|%s|%s" "$ANDROID_HOME" "$ANDROID_SDK_ROOT" "$FLINT_ENV_ANDROID_SOURCE"'
}
printf 'sdk.dir=/sdk/from-properties\n' > "$FIXTURE/android-app/local.properties"
eq 'CI ANDROID_HOME beats SDK_ROOT and local.properties' '/ci/sdk|/ci/sdk|env:ANDROID_HOME' \
    "$(probe ANDROID_HOME=/ci/sdk ANDROID_SDK_ROOT=/old/sdk)"
eq 'SDK_ROOT beats local.properties' '/root/sdk|/root/sdk|env:ANDROID_SDK_ROOT' "$(probe ANDROID_SDK_ROOT=/root/sdk)"
eq 'local.properties beats default' '/sdk/from-properties|/sdk/from-properties|local.properties:sdk.dir' "$(probe)"
printf '# comment\nsdk.dir=/first\n  sdk.dir : /SDK\\ folder/with\\:colon\\\\slash\n' > "$FIXTURE/android-app/local.properties"
eq 'property decoding, spaces and last key wins' '/SDK folder/with:colon\slash|/SDK folder/with:colon\slash|local.properties:sdk.dir' "$(probe)"
printf 'sdk.dir=relative-sdk\n' > "$FIXTURE/android-app/local.properties"
eq 'relative property resolves from android-app' "$FIXTURE/android-app/relative-sdk|$FIXTURE/android-app/relative-sdk|local.properties:sdk.dir" "$(probe)"
printf 'sdk.dir=/sdk/$(touch should-not-exist)\n' > "$FIXTURE/android-app/local.properties"
eq 'properties are data, never shell code' '/sdk/$(touch should-not-exist)|/sdk/$(touch should-not-exist)|local.properties:sdk.dir' "$(probe)"
rm "$FIXTURE/android-app/local.properties"
eq 'default SDK uses temporary HOME' "$TEST_HOME/Library/Android/sdk|$TEST_HOME/Library/Android/sdk|default:$TEST_HOME/Library/Android/sdk" "$(probe)"

result=$(env -i HOME="$TEST_HOME" ANDROID_HOME='/SDK with spaces' JAVA_HOME='/JDK with spaces' \
    DEVELOPER_DIR='/Xcode with spaces' PATH='/usr/bin:/bin:/usr/bin:/SDK with spaces/emulator' \
    FLINT_SCRIPT="$FIXTURE/scripts/flint-env.sh" FLINT_ENV_ROOT="$FIXTURE" /bin/bash -eu -c '
        before=$PWD; before_options=$-; . "$FLINT_SCRIPT"; first=$PATH; source=$FLINT_ENV_ANDROID_SOURCE
        . "$FLINT_SCRIPT"; [ "$first" = "$PATH" ]; [ "$source" = "$FLINT_ENV_ANDROID_SOURCE" ]
        [ "$before" = "$PWD" ]; [ "$before_options" = "$-" ]; ! command -v _flint_resolve_env >/dev/null
        printf "%s" "$PATH"
    ')
eq 'idempotent, exact PATH de-duplication, cwd/options/functions preserved' \
    '/SDK with spaces/platform-tools:/SDK with spaces/emulator:/SDK with spaces/cmdline-tools/latest/bin:/JDK with spaces/bin:/usr/bin:/bin' "$result"
for test_shell in /bin/bash /bin/sh /bin/zsh; do
    [ -x "$test_shell" ] || continue
    result=$(env -i HOME="$TEST_HOME" PATH="$FAKE_BIN:/usr/bin:/bin" \
        ANDROID_HOME=/ci/sdk JAVA_HOME=/ci/jdk DEVELOPER_DIR=/ci/xcode \
        FLINT_SCRIPT="$FIXTURE/scripts/flint-env.sh" FLINT_ENV_ROOT="$FIXTURE" "$test_shell" -eu -c \
        '. "$FLINT_SCRIPT"; first=$PATH; . "$FLINT_SCRIPT"; [ "$first" = "$PATH" ]; printf "%s" "$ANDROID_HOME|$JAVA_HOME|$DEVELOPER_DIR"')
    eq "sourceable and idempotent in $test_shell" '/ci/sdk|/ci/jdk|/ci/xcode' "$result"
done

# Use the host make explicitly: macOS ships GNU Make 3.81.
cat >> "$FIXTURE/Makefile" <<'MAKE'

env-selftest-probe:
	@printf '%s|%s|%s|%s|%s\n' '$(JAVA_HOME)' "$$JAVA_HOME" '$(XCODE_DEV)' "$$DEVELOPER_DIR" "$$ANDROID_HOME"
env-selftest-fail:
	@exit 23
env-selftest-errexit:
	@set -e; false; printf 'unreachable\n'
MAKE
make_probe() {
    env -i HOME="$TEST_HOME" PATH="$FAKE_BIN:/usr/bin:/bin" FAKE_TOOL_LOG="$WORK/tools.log" \
        ORCA_CLI_COMMAND="$FAKE_BIN/orca" /usr/bin/make --no-print-directory -C "$FIXTURE" "$@"
}
eq 'Make aliases and recipe overrides agree, including spaces' '/override jdk|/override jdk|/override xcode|/override xcode|/ci/sdk' \
    "$(make_probe JAVA_HOME='/override jdk' XCODE_DEV='/override xcode' ANDROID_HOME=/ci/sdk env-selftest-probe)"
defaults=$(make_probe env-selftest-probe)
IFS='|' read -r make_java shell_java make_xcode shell_xcode android <<< "$defaults"
eq 'Make default JAVA_HOME alias equals resolver' "$shell_java" "$make_java"
eq 'Make default XCODE_DEV alias equals resolver' "$shell_xcode" "$make_xcode"
eq 'Make default SDK matches sourced default' "$TEST_HOME/Library/Android/sdk" "$android"
if make_probe env-selftest-fail > "$WORK/failure.log" 2>&1; then exit 1; fi
grep -q 'Error 23' "$WORK/failure.log"
eq 'recipe exit status preserved' yes yes
if make_probe env-selftest-errexit > "$WORK/errexit.log" 2>&1; then exit 1; fi
if grep -q unreachable "$WORK/errexit.log"; then exit 1; fi
eq 'recipe errexit preserved' yes yes
make_probe JAVA_HOME=/missing/jdk XCODE_DEV=/missing/xcode ANDROID_HOME=/missing/sdk help env > "$WORK/missing.log" 2>&1
grep -q 'missing' "$WORK/missing.log"
eq 'missing toolchains do not block help/env' yes yes
grep -q 'WARNING: shell ANDROID_HOME=.*differs from checkout fallback' "$WORK/missing.log"
grep -q 'WARNING: shell JAVA_HOME=.*differs from checkout fallback' "$WORK/missing.log"
eq 'generic shell path drift is visible and never overrides environment' yes yes
make_probe env > "$WORK/env.log"
make_probe doctor > "$WORK/doctor.log"
cmp "$WORK/env.log" "$WORK/doctor.log"
eq 'doctor reuses env exactly' yes yes

printf 'sdk.dir=/different/sdk\n' > "$FIXTURE/android-app/local.properties"
if make_probe ANDROID_HOME=/ci/sdk android > "$WORK/conflict.log" 2>&1; then exit 1; fi
grep -q 'Gradle prefers sdk.dir' "$WORK/conflict.log"
eq 'conflicting Gradle SDK is caught before build' yes yes
[ ! -s "$WORK/tools.log" ]
eq 'no build or device binary is executed by environment diagnostics' yes yes
printf '=== flint-env: %s checks passed ===\n' "$CHECKS"
