# Android emulator smoke suite

`.github/workflows/android-e2e.yml` runs the instrumentation APK on an API 36
Google APIs x86_64 emulator with KVM. The 25-minute job builds before booting,
uses bounded readiness waits, and never retries assertions. It runs on non-draft
Android/runner/Makefile PR changes, ready-for-review, manual dispatch, and always
for the draft `integration/v1` standing PR. Screenshot/hierarchy,
logcat and Gradle JUnit/HTML artifacts are uploaded on success and failure.

Both debug and release smoke jobs cache the API 36 image and a pristine AVD
before its first boot. The key includes the stable image revision and archive
checksum from Google's repository, command-line tool identity, and setup script
and device configuration. Only those image/AVD paths are cached; no adb keys or
post-test snapshots are saved. A restored image must have the exact package
revision and nonempty boot/system payloads before use.

Preparation permits three image-install attempts, each bounded to 120 seconds,
with 5/10-second backoff. A successful installer exit without a verified package
still fails. This handles observed SDK ZIP preparation failures; it never
restarts tests or retries assertions. Setup logs and cache provenance are in the
ordinary always-uploaded artifact even when no emulator starts. The 25-minute
job cap remains, including the emulator action's subsequent standard SDK checks.
The cache runner is CI-only; local execution below is unchanged.

Both hosted smoke jobs explicitly use two cores, 3 GB RAM, a 512 MB VM heap,
SwiftShader, cold boot without snapshot loading/saving, no boot animation/audio
and no emulated cameras. The existing runner disables all three global animation
scales. The pristine AVD cache records the actual RAM/heap/core configuration.
System UI, navigation and notifications remain enabled so their app interactions
are still exercised.

Before each fixture clears Flint, setup requests Home and requires repeated fresh
launcher accessibility samples and a stable System UI process. Its 30-second
polling budget permits at most two recognized system/launcher ANR dismissals,
preferring the resource-ID-identified Wait control, with Close app allowed only
for an identified non-Flint system/launcher owner. A dialog click must disappear
within five seconds and is never blindly replayed. Flint/unknown-owner ANRs fail
setup. Recovery never runs inside visibility helpers, test bodies or assertions.
`smoke/setup-events.jsonl` records every detection/dismissal, per-test readiness
and setup failure with a run token; pre-dismissal PNG/XML captures remain visible.
`setup-summary.json` and the job summary report counts for that run only, including
zero dismissals and whether a setup log was collected. A green run with zero
dismissals proves the stable path, not live ANR recovery; the policy regression
case separately checks allowed owners, Unicode titles and fail-closed behavior.

The opt-in `:e2e` module instruments itself and contains a harmless launchable **Flint E2E
Target** activity. UIAutomator interacts with the separately installed Flint
app, so stopping Flint really terminates its process while the test survives.
It sources the tests from `app/src/androidTest`, uses the app's SDK settings,
and is included only with `-PflintE2E=true`; ordinary unit/release builds do not
build or package it. The standalone module is necessary because AGP overrides
`targetPackage` in an application module's androidTest manifest.
No production code or release permissions are changed. Android UI automation
explicitly preserves accessibility services during the test session.

| Test | Evidence | Limit |
| --- | --- | --- |
| Consent | Welcome, disclosure, accept/decline controls render; declining returns to Today without granting accessibility | No Play approval or OEM Settings handoff proof |
| Rule and restart | Author rule through UI, enable real service via secure settings, launch target, see shield; stop/relaunch Flint and see saved rule plus shield; Easy break dismisses shield | Service is explicitly re-enabled after restart; no automatic OS recovery, break expiry or reboot claim |
| Emergency Pass | Hardcore rule shows shield, omits ordinary break, explicit pass opens target | No pass reset, overlap or durable grant proof |
| Budget | Existing DEBUG receiver seeds a zero-minute legacy budget, real service shows Time limit shield | Does not prove elapsed UsageStats measurement, modern limit authoring or cross-midnight reset |

These are emulator integration checks, not hardware/OEM, anti-removal, web
blocking, Play compliance or complete enforcement certification. There are no
quarantined tests initially. Any later quarantine must record the specific
failure, issue/owner and missing coverage here; do not mask it with reruns.

## Local execution

Use a dedicated disposable emulator with no existing enabled accessibility
services. The runner deliberately clears Flint app data before each test and
temporarily enables Flint's real AccessibilityService with shell authority.
It refuses phones, ambiguous device selectors, and missing explicit opt-in.
Boot/attach the emulator with the project device skill first; this target uses
an existing exact serial and does not acquire boot/shutdown ownership.

On the shared v1 build host, hold the device semaphore for the whole emulator
session, then wrap the command in the Android semaphore:

```sh
~/flint-planning/2026-10-28/build-v1/bin/flint-heavy device -- sleep 2700 &
device_slot=$!
FLINT_E2E_DISPOSABLE=1 ~/flint-planning/2026-10-28/build-v1/bin/flint-heavy android -- \
  make android-e2e AVD=emulator-5554
make clean-devices
kill "$device_slot"
```

Retain the device slot across preparation and the test command; use the actual
serial returned for your disposable emulator. The tests and exit trap remove
their token-recorded temporary Flint grant, preserve other services, and refuse
cleanup against a changed boot incarnation. Worktree cleanup preserves shared or
unclaimed devices. Outside this shared build, serialize device/build work using
the host's equivalent semaphore. Reports are under
`android-app/e2e/build/outputs/androidTest-results/connected/` and
`artifacts/android-e2e/`; inspect the original failure before changing a test.
