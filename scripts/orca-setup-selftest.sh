#!/bin/bash
# Exercise the actual hook twice in a temporary git worktree, with fake tools.
set -eu
# Preserve the diagnostic destination BEFORE any per-case redirection. A failure
# dump must never append to the file it is reading, even while stderr is captured.
exec 9>&2
dump_fixture_log() { tail -c 8192 "$1" >&9; }
# Bound every fixture/log write in this selftest and its children (not the caller).
ulimit -c 0
ulimit -f 1024
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
WORK=$(mktemp -d "${TMPDIR:-/tmp}/flint-setup-selftest.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
. "$ROOT/scripts/selftest-isolation.sh"
SEED="$WORK/seed"
CHECKOUT="$WORK/worktree"
TEST_HOME="$WORK/home"
SDK="$TEST_HOME/Library/Android/sdk"
BIN="$WORK/bin"
mkdir -p "$SEED/scripts/orca" "$SEED/android-app" "$SEED/ios-app" "$BIN" "$SDK/platform-tools" "$WORK/jdk/bin"
: > "$WORK/tools.log"
for tool in xcrun xcodebuild adb emulator sdkmanager avdmanager orca; do
    cat > "$BIN/$tool" <<'FAKE'
#!/bin/sh
printf 'unexpected %s %s\n' "${0##*/}" "$*" >> "$FAKE_TOOL_LOG"
echo "Unexpected build/device execution in setup selftest: ${0##*/}" >&2
exit 90
FAKE
    chmod +x "$BIN/$tool"
done
cp "$ROOT/Makefile" "$SEED/"
cp "$ROOT/scripts/flint-env.sh" "$ROOT/scripts/flint-shell.sh" "$ROOT/scripts/flint-env-report.sh" "$SEED/scripts/"
cp "$ROOT/scripts/orca/setup.sh" "$SEED/scripts/orca/"
: > "$SEED/android-app/settings.gradle.kts"
: > "$SEED/ios-app/project.yml"
cat > "$SEED/.gitignore" <<'IGNORE'
android-app/local.properties
ios-app/*.xcodeproj/
IGNORE
cat > "$BIN/xcodegen" <<'FAKE'
#!/bin/sh
printf 'xcodegen %s\n' "$*" >> "$FAKE_TOOL_LOG"
[ "$1" = generate ] || exit 71
mkdir -p Flint.xcodeproj
printf 'generated\n' > Flint.xcodeproj/project.pbxproj
FAKE
cat > "$BIN/make" <<'FAKE'
#!/bin/sh
# Keep this test device/toolchain-free, including macOS /usr/bin/make's Xcode
# launcher. Real GNU Make integration is exercised by flint-env-selftest.sh.
set -e
printf 'make %s\n' "$*" >> "$FAKE_TOOL_LOG"
case "$*" in
    ios-gen) cd ios-app; xcodegen generate ;;
    env) bash scripts/flint-env-report.sh ;;
    *) echo "Unexpected setup make target: $*" >&2; exit 74 ;;
esac
FAKE
cat > "$SEED/android-app/gradlew" <<'FAKE'
#!/bin/sh
echo 'ERROR: setup attempted Gradle' >&2
exit 73
FAKE
chmod +x "$BIN/make" "$BIN/xcodegen" "$BIN/xcodebuild" "$SEED/android-app/gradlew"
git -C "$SEED" init -q
git -C "$SEED" add .
git -C "$SEED" -c user.name=Selftest -c user.email=selftest@flint.invalid -c commit.gpgsign=false commit -qm fixture
git -C "$SEED" worktree add -q --detach "$CHECKOUT" HEAD
run_setup() {
    env -i HOME="$TEST_HOME" PATH="$BIN:/usr/bin:/bin" JAVA_HOME="$WORK/missing-jdk" \
        FAKE_TOOL_LOG="$WORK/tools.log" ORCA_CLI_COMMAND="$BIN/orca" \
        DEVELOPER_DIR="$WORK/missing-xcode" ORCA_ROOT_PATH="$SEED" ORCA_WORKTREE_PATH="$CHECKOUT" \
        ORCA_WORKSPACE_NAME=setup-selftest "$@" /bin/bash -e "$CHECKOUT/scripts/orca/setup.sh"
}
run_setup > "$WORK/first.log" 2>&1 || { dump_fixture_log "$WORK/first.log"; exit 1; }
[ -f "$CHECKOUT/ios-app/Flint.xcodeproj/project.pbxproj" ]
grep -q '^sdk.dir=' "$CHECKOUT/android-app/local.properties"
cp "$CHECKOUT/android-app/local.properties" "$WORK/original.properties"
cp "$CHECKOUT/ios-app/Flint.xcodeproj/project.pbxproj" "$WORK/original.pbxproj"
run_setup > "$WORK/second.log" 2>&1
cmp "$WORK/original.properties" "$CHECKOUT/android-app/local.properties"
cmp "$WORK/original.pbxproj" "$CHECKOUT/ios-app/Flint.xcodeproj/project.pbxproj"
grep -q 'WARNING: JDK missing' "$WORK/first.log"
grep -q 'WARNING: Xcode missing' "$WORK/first.log"
printf 'ok - setup twice generates stable project/properties under set -e, with loud missing-tool warnings\n'

printf '# user-owned settings\nsdk.dir=/custom/sdk\ncustom.flag=retain\n' > "$CHECKOUT/android-app/local.properties"
cp "$CHECKOUT/android-app/local.properties" "$WORK/custom.properties"
run_setup ANDROID_HOME="$SDK" > "$WORK/preserve.log" 2>&1
cmp "$WORK/custom.properties" "$CHECKOUT/android-app/local.properties"
printf 'ok - an existing local.properties is never overwritten, including an env conflict\n'

rm "$CHECKOUT/android-app/local.properties"
run_setup ANDROID_HOME="$WORK/missing-sdk" > "$WORK/missing-sdk.log" 2>&1
[ ! -e "$CHECKOUT/android-app/local.properties" ]
grep -q 'WARNING: Android SDK missing' "$WORK/missing-sdk.log"
printf 'ok - missing SDK does not block iOS generation or write a bogus properties file\n'

mv "$BIN/xcodegen" "$BIN/xcodegen.disabled"
if run_setup > "$WORK/no-xcodegen.log" 2>&1; then
    printf 'FAIL: missing XcodeGen must fail setup\n' >&2; exit 1
fi
grep -q 'ERROR: XcodeGen is required' "$WORK/no-xcodegen.log"
printf 'ok - missing XcodeGen fails clearly\n'
if grep -vE '^(make (ios-gen|env)|xcodegen generate)$' "$WORK/tools.log"; then
    printf 'FAIL: unexpected fake tool call\n' >&2; exit 1
fi
grep -q '^xcodegen generate$' "$WORK/tools.log"
printf 'ok - fake-tool call log contains only project generation and diagnostics\n'

# Regression: even a caller redirecting stderr into the source log cannot make
# the dump read its own output. The preserved fd receives at most 8192 bytes.
awk 'BEGIN { for (i = 0; i < 20000; i++) printf "x" }' > "$WORK/dump-source.log"
(
    exec 9> "$WORK/dump-output.log"
    # Intentionally reproduce the old redirection hazard; output must use fd 9.
    # shellcheck disable=SC2094
    dump_fixture_log "$WORK/dump-source.log" 2>> "$WORK/dump-source.log"
)
[ "$(wc -c < "$WORK/dump-source.log")" -eq 20000 ]
[ "$(wc -c < "$WORK/dump-output.log")" -eq 8192 ]
printf 'ok - failure dumps are bounded and cannot feed their own redirected log\n'
if (ulimit -f 1; dd if=/dev/zero of="$WORK/size-cap.log" bs=4096 count=1) > "$WORK/cap.out" 2> "$WORK/cap.err"; then
    printf 'FAIL: fixture size cap was not enforced\n' >&2; exit 1
fi
[ "$(wc -c < "$WORK/size-cap.log")" -le 1024 ]
printf 'ok - process-local file-size limit stops unbounded fixture writes\n'
git -C "$SEED" worktree remove --force "$CHECKOUT"
printf '=== orca-setup: 7 checks passed ===\n'
