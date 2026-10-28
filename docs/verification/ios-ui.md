# iOS fixture UI gate

`iOS UI fixtures` runs the production app in **Debug** on a fresh GitHub-hosted
iPhone simulator. It uses existing `-FlintUIPreview` authorization fixtures; it
does not request system access, choose actual apps, or save/arm a schedule.
This is fixture UI evidence, never Screen Time or physical-device proof.
To conserve hosted macOS capacity, the job runs for ready PRs, the standing
`integration/v1` PR, and explicit workflow dispatches;
ordinary draft PRs skip it until marked ready for review.

The separate `FlintUI` scheme leaves the existing `Flint` unit-test scheme intact.
The same command is used locally and in `.github/workflows/ios-ui.yml`:

```sh
make ios-ui FLINT_UI_DISPOSABLE=1 IOS_SIM='<dedicated simulator name or UDID>'
```

The runner fails before any device/app mutation unless `FLINT_UI_DISPOSABLE=1`
explicitly confirms an empty, dedicated simulator. CI sets this only on its
fresh disposable hosted runner. The PIN case writes a synthetic PIN to the
app group and removes it through production authentication at the end. A failing
case can leave that synthetic PIN behind; do not reuse a personal simulator or
silently retry a failed suite. No defaults, simulator data, or result bundles are
erased by the script. Local runs use the project device claim/cleanup mechanism;
the hosted runner is disposable. During the v1 multi-agent build, wrap local
runs in both the mandatory `flint-heavy device` and `flint-heavy ios` semaphores.
For example, from the repository root:

```sh
~/flint-planning/2026-10-28/build-v1/bin/flint-heavy device -- \
  ~/flint-planning/2026-10-28/build-v1/bin/flint-heavy ios -- \
  make ios-ui FLINT_UI_DISPOSABLE=1 IOS_SIM='<dedicated simulator UDID>'
```

| Coverage | Large and Accessibility XXXL (AX5) |
| --- | --- |
| Onboarding / Today | Welcome → fixture authorization failure → retry label and visible/enabled skip; separate explicit launch for access-needed Today |
| Today / Start focus | Empty schedule copy, reachable Start, duration selection, disabled Begin without app selection, close |
| App PIN | Create synthetic PIN, cold relaunch gate, hidden protected hierarchy, wrong PIN, correct PIN, authenticated removal |
| Schedule validation | Empty block-list cannot save; allow-list draft; zero-length and 10-minute errors; exact 15-minute boundary; cancel without saving |
| Insights unavailable | Approved fixture explains simulator limitation; range changes; unapproved fixture explains access needed; unavailable never claims measured zero |
| Insights empty / absent | Existing `FlintInsightsTests` renders the actual report content with no-data, zero-usage and activity inputs at Large/AX5; this is a unit render, not the OS report service |

The host app has no DEBUG empty-report fixture, so XCUITest cannot truthfully
claim an end-to-end zero-usage report. The maintained report tests distinguish
empty returned usage from absent data without adding a production fixture.
Onboarding uses `-flint.onboarded NO`, whose argument-domain override also masks
completion writes for that process. The suite covers consent/error/skip controls
and launches Today separately; it does not prove persisted onboarding completion.

Every UI case attaches a screenshot and accessibility hierarchy to the xcresult.
The workflow uploads `FlintUI.xcresult`, the complete `xcodebuild.log`, and
`provenance.txt` (commit, exact UDID, installed runtimes, Xcode version and fixture
boundary) on success or failure. Test execution is serial with one iteration and
test retries disabled. Bounded existence waits and scrolling handle UI readiness;
no failed assertion or input action is retried.
The first input after a launch allows up to 60 seconds for cold accessibility
automation startup (a retained CI snapshot took 48 seconds); later inputs allow
8 seconds. Both require the same existing, enabled, hittable target before a
single tap. Scroll drags stay in the visible scroll view's padding and above
the keyboard so they do not invoke secure-field text selection.
Before testing, CI boots the selected exact UDID once if needed and uses the
existing bounded device helpers to wait for boot completion and a running
SpringBoard with a positive PID. It saves `simulator-readiness.log` and fails
before Xcode tests if readiness is not established; it never launches the app
as a warmup or retries a failed test. Local runs already use this readiness
check through the device claim/attach path.

Current run/commit evidence is recorded in the PR and worker report after CI
completes. This gate does not establish minimum-iOS/iPad coverage, full VoiceOver,
real authorization, ManagedSettings/FamilyControls enforcement, DeviceActivity
callbacks, Safari restrictions, signing, release readiness or store approval.
