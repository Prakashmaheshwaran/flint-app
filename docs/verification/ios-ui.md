# iOS fixture UI gate

`make ios-ui` is the maintained local fixture gate. The `iOS UI fixtures`
workflow is **manual-only** (`workflow_dispatch`), an optional hosted diagnostic
that emits no PR check. Hosted simulator startup proved unreliable, so it is not
a required merge gate. It runs the production app in **Debug** on a fresh hosted
iPhone simulator when explicitly dispatched. The suite uses existing
`-FlintUIPreview` authorization fixtures; it
does not request system access, choose actual apps, or save/arm a schedule.
This is fixture UI evidence, never Screen Time or physical-device proof.
Hosted CI explicitly selects an available iPhone 17 Pro on iOS 26.5, matching the
observed green standard unit-test destination, then resolves its exact UDID.
It fails if that runtime/device is missing or ambiguous; simulator JSON order
cannot silently select an older runtime. This is a maintained CI pin, not
minimum-iOS coverage.
No push or pull-request event starts the hosted workflow. Test assertions and
the separate UI target remain enabled in the local gate.

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
SpringBoard with a positive PID. It saves `simulator-readiness.log` and the
direct CLI output in `simulator-bootstatus.log`, including on timeout, and fails
before Xcode tests if readiness is not established; it never launches the app
as a warmup or retries a failed test. Local runs already use this readiness
check through the device claim/attach path. A failed preflight retains bounded,
read-only post-failure queries for the SpringBoard service, system launchctl
domain and simulator inventory, including exit status, stdout and stderr.
These diagnostic observations cannot resume the tests.
CI allows one boot command up to 30 seconds, one boot-status wait up to 600
seconds, then 30 seconds for SpringBoard. The [green unit job in run
40771331381](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40771331381) took 6m30
between completed build validation and its first app log; that interval includes
startup/install and does not isolate boot time. The longer bounded CI wait is
based on that observed latency. Local ownership/readiness limits, UI assertions,
and the 35-minute CI job cap are unchanged; no failed wait or test is rerun.

Current run/commit evidence is recorded in the PR and worker report after CI
or local testing completes. The capped hosted attempts retained these outcomes:

- [Run 40774736338](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40774736338)
  passed all 10 UI and 11 Insights cases without skips or retries on head
  `007a80a`, actual checkout merge `8ee09fb335b7d5a20572a91b3e5c2991a71a78f6`.
  [Artifact 12856260581](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40774736338/artifacts/12856260581)
  includes the verified xcresult, 20 screenshots and 14 accessibility trees.
- After the integration rebase, [run 40778595423](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40778595423)
  on head `af24db4`, actual merge `44d0e6dfd347aa0c228e3593d587f231bed31716`,
  failed before project generation or tests. Bootstatus finished in about 103s
  wall time, then the 30s SpringBoard query timed out. Post-failure service and
  inventory queries also timed out; the system-domain query returned successfully.
  [Artifact 12856950390](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40778595423/artifacts/12856950390)
  retains boot progress, readiness diagnostics and provenance, with no xcresult.

The owned suite/runner files were identical between those heads, but the actual
checkout trees differed in 35 upstream iOS production/test files. The earlier
green result therefore does not establish UI coverage of the rebased app. Local
verification must run the complete unchanged suite against the final checkout
under both semaphores on a newly created dedicated simulator. Do not rerun failed
assertions or silently treat a later healthy diagnostic query as prior readiness.

This gate does not establish minimum-iOS/iPad coverage, full VoiceOver,
real authorization, ManagedSettings/FamilyControls enforcement, DeviceActivity
callbacks, Safari restrictions, signing, release readiness or store approval.
