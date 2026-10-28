# Flint iOS review notes and device review script — draft

Prepared 2026-10-28; lane J source refresh at `integration/v1` `32a8d6b`.
Original D6 baseline was `fa190e9df96f61c13ea651ee39cae6f68057f0c6`. Companion:
[store metadata, privacy justification, age-rating rationale, and shot-list](store-package.md).
No Apple account was accessed, no entitlement request was submitted, and no
signed build, TestFlight upload, or physical-device behavior was verified by D6.
All unchecked gates below remain open. Human-owned identity/contact information,
credentials, certificates, profiles, and account evidence must stay out of git.
Use the [central owner checklist](../README.md) and
[single risk-ordered physical-device plan](../../acceptance/device-acceptance-plan.md).
The R-steps below are platform review detail, not a second set of completed results.

**Not submission-ready:** automatic deletion denial on Hardcore start, session-only pass
redemption and incomplete bounded Open-credit integration still differ from ADR-012 at
this checkpoint. The script's contract expectations remain blocked until actual live
implementation and signed-device acceptance are linked. The proposed field below omits
those unverified promises; do not submit the app while the underlying blockers remain.

## Before these notes can be submitted

- [ ] Lane B's final iOS enforcement changes and acceptance evidence are linked,
  including ADR-012 semantics, Safari lifecycle, monitor failure, permission loss,
  overlap arbitration, bounded Open Limits, and session expiry after host death.
- [ ] Lane C's Android evidence is linked before any cross-platform parity claim;
  Android evidence is not a substitute for iOS physical tests.
- [ ] Lane F's real local-event history/Insights evidence is linked; final UI and
  listing make only supported history/completion/report claims.
- [ ] The final Release archive is inspected for all six shipping bundles,
  required-reason privacy manifests, AppIcon/resources, correct version/build,
  absence of DEBUG preview fixtures, and distribution-signed entitlements.
- [ ] A human Account Holder confirms approval/provisioning coverage for each of
  the six identifiers below, the intended signing team, and shared App Group;
  Family Controls approval is required for the host and four Screen Time extensions.
- [ ] Owner-approved physical iPhone and iPad runs pass the script below on the
  exact candidate; attach private evidence references, not sensitive data.
- [ ] Lane E's `docs/privacy-policy.md` is merged and reconciled with iOS; public
  privacy/support URLs work without login and match the final app links.
- [ ] The owner supplies App Review contact name/email/international phone in
  Connect, finalizes age-rating answers, verifies screenshots and privacy labels,
  selects the processed build, and authorizes submission/release separately.

Do not treat a draft PR, green CI, simulator screenshot, or these notes as an
accepted store package. Keep notes in draft until the release owner resolves the
gates; do not send Apple a workflow that relies on unresolved behavior.

## App Review Notes field — proposed text

The following block is intended for the Connect Notes field **only after** the
gates pass and the actual candidate has been checked. It is not a claim that
the workflow below passed at this baseline. Edit screen labels/durations to the
tested candidate and remeasure its UTF-8 byte length before owner submission.

```text
Flint is a local-first focus utility for an individual's own iPhone or iPad. There is no Flint account, login, subscription, or purchase needed to use its protection levels. Sign-in required: No. Apple system authorization is separate from a Flint account.

Please review Screen Time behavior on a physical device. Launch Flint, tap Begin, then Allow Screen Time and approve Apple's individual authorization prompt. If access is denied, Flint offers Open Settings and Check access again. In iOS Settings, review Screen Time > Apps with Screen Time Access for Flint, then return. A simulator cannot demonstrate restrictions or real Screen Time reports.

For a short review: open Today > Start focus, use Apple's picker to select one nonessential test app, choose 25 minutes, press minus twice for 15 minutes, choose Easy, then begin. Open the chosen app to see the system shield. Return to Flint and use End session; confirm that the test app opens again. Keep essential calling, Settings, and safety apps out of the selection.

The host uses Family Controls and Managed Settings. Four Screen Time extensions monitor activity boundaries, configure the shield, respond to shield buttons, and render Device Activity reports. A separate WidgetKit extension supplies read-only widgets and the opt-in Live Activity. Selection tokens and settings are shared locally through group.com.flint.peakfocus. Local focus events drive session history separately from report values, which stay in the report extension. New installs may have no report data; a missing report must not be read as zero activity.

Use Easy for this short review. Screen Time permission remains revocable in iOS Settings. Review the final in-app tier, end-time and recovery disclosures before testing other protection levels. The app-open PIN is separate from Apple's Screen Time passcode; no preconfigured PIN is supplied. Optional focus reminders describe the planned end time, not proof of completion or uninterrupted protection. Widgets and Live Activity open Flint and do not change protection.

The About link opens an external browser. Core focus behavior does not require a Flint server. Public support/privacy destinations and the App Review contact are supplied in their dedicated App Store Connect fields.
```

Apple's [platform version reference](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/)
limits App Review Notes to 4,000 bytes. Do not put test Apple IDs, a device PIN,
passcode, signing credentials, or a fabricated reviewer login in this field.
Use the dedicated human-owned contact fields. No attachment or review video is
claimed; if Apple requests one, the owner can capture the actual candidate and
review its privacy before supplying it.

Lane J measured the refreshed Notes block as **2,277 UTF-8 bytes**.
Any edits require a fresh byte count; the block is ASCII.

## Real-device review and acceptance script — not executed

Run manually on owner-approved test hardware using a signed candidate. This is
a checklist for reviewer reproducibility and release acceptance, not permission
for this worker to grant Screen Time, alter personal device settings, or upload
a build. Use the OS's native interactions; do not use DEBUG launch arguments.
Prefer a dedicated test device with no important personal data or existing rules.
Do not configure someone else's Screen Time passcode, consume a personal pass,
delete data/apps, or leave any removal restriction active on a shared device.

For **each R-step**, record pass/fail/blocked/not-run, candidate commit/build,
archive hash, device model/OS, start/end time, action/expected/actual result, and
private evidence location. A “not-run” is not a pass. An unresolved enforcement,
cleanup, or safe-exit failure blocks the corresponding public claim and submission.
Lane B/J acceptance cases and ADR-012 take precedence if this draft becomes stale.

### R1 — Identity and clean start

Owner records the signing team, distribution entitlement evidence for the host
and all extensions, installation method, version/build, and minimum/current-OS
coverage. Confirm a Release candidate without preview flags or seeded session,
authorization, report, or history data. Record any pre-existing Screen Time
settings that can affect results. Have an unselected test app and essential
calling/Settings access available. Do not perform destructive reset for this test.

### R2 — Onboarding and permission recovery

Launch → Begin. Observe the Screen Time explanation; first cancel/deny the system
request and confirm Flint does not claim protection is active. Use its denied
recovery guidance; iOS Settings → Screen Time → Apps with Screen Time Access
may vary by OS. Return and choose Check access again. With explicit device-owner
approval, allow **individual** access and confirm the actual status updates.
Test the optional Not now path separately; no server login should appear.
Source entry points:
[onboarding](../../../ios-app/Flint/Features/Onboarding/OnboardingView.swift),
[authorization](../../../ios-app/Flint/Features/Authorization/AuthorizationModel.swift),
[denied recovery](../../../ios-app/Flint/Features/Authorization/AuthorizationControls.swift).
Never classify a fake authorized UI as system approval.

### R3 — Real selection and a stoppable session

Today → Start focus → Apple picker. Select one nonessential test app, no essential
apps or broad categories. Save a benign group name, choose the 25-minute preset,
then press minus twice to reach 15 minutes; choose Easy and begin. The baseline's
minus control reduces duration by five minutes per press. Record countdown, actual selected
app shield, and access to the unselected app. End session in Flint and verify
the selected app is accessible. Reopen Flint and confirm the saved selection
and group persist. A selected website token is a separate test in R6.
Source: [duration presets](../../../ios-app/Flint/Features/Today/StartFocusSheet.swift),
[duration decrement](../../../ios-app/Flint/Features/Today/StartFocusSheet.swift),
[start sheet](../../../ios-app/Flint/Features/Today/StartFocusSheet.swift),
[saved groups](../../../ios-app/Flint/Features/Sessions/SessionViewModel.swift),
[session exits](../../../ios-app/Flint/Features/Today/SessionView.swift).

### R4 — Extension behavior and timed expiry with the host closed

Start another timed Easy session with the same test selection. Launch the selected
app to confirm a real system shield and its actual button behavior; record pixels
and access outcome, not just callback success. Close/terminate the Flint host
while the session is active, wait through its real end time, then verify that
the test app opens **before relaunching Flint**. Also test reboot across expiry
on dedicated hardware and after relaunch; distinguish background release from
foreground reconciliation. Verify no stale restriction/removal guard remains.
Source roles:
[monitor](../../../ios-app/Extensions/DeviceActivityMonitorExtension/FlintMonitor.swift),
[shield configuration](../../../ios-app/Extensions/ShieldConfigurationExtension/FlintShieldConfiguration.swift),
[shield action](../../../ios-app/Extensions/ShieldActionExtension/FlintShieldAction.swift).
Actual reliable expiry remains lane B + physical evidence, not a D6 finding.

### R5 — Optional stronger protection and controlled exit

Only on a consenting owner's test device, with known recovery access and an
available Emergency Pass: read the final Hardcore disclosure, choose a bounded
duration and the same single nonessential app. Verify app-deletion restriction
is a separate opt-in, not an automatic consequence of choosing Hardcore. With
it off, deletion remains available. Before opting in, verify device-wide scope,
the exact end, pass availability and reachable recovery/permission controls.
Verify it is requested only by an eligible active, armed, finite Hardcore
commitment, with unavailable/failed arming shown honestly; a saved inactive
template or unknown time must not enable it. Observe deletion availability
without completing deletion. Check ordinary stop/edit/replacement behavior
against [ADR-012](../../architecture/protection-contract.md) across UI,
Shortcuts and Focus, including another active owner's continuing guard.

Exercise the in-app Emergency Pass explicit confirmation/hold once. It must
list every affected commitment, the 60-minute global recovery end and next
pass time before committing use. Verify transient sessions end, eligible rules
pause without losing counters/templates, and owned shields/guards/Safari
restrictions release. After recovery only naturally active causes resume;
explicit “Resume protection now” ends recovery without refunding the pass.
Capture “Available” or the next date/time/zone **seven days after committed
use**, plus any clock uncertainty. There is no Monday refill or accumulated
bank; do not reset defaults or change the clock to replenish it. Link lane B's
automated transaction evidence for failed/ambiguous writes and crash/retry
without double spending; those fault cases are not App Review device actions.
These are pending lane B acceptance cases, not observations from this draft.

In a separate timed run, verify expiry releases the guard with the host closed.
Home, emergency calling and OS permission controls must stay reachable without
a pass. Record the actual observed state; a code assignment is not proof the
OS applied or released deletion restriction.
Confirm Settings withdrawal of authorization and subsequent honest UI state as
an owner-approved recovery test. Never rely on an unverified PIN bypass or an
unproven claim that withdrawal instantly clears every restriction.
Source: [guard scope](../../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintUninstallGuard.swift),
[pass implementation](../../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintSessionController.swift),
[app PIN](../../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintPIN.swift).

### R6 — Every additional enforcement claim

Lane B supplies the exact signed-device cases for recurring schedules (including
overnight/overlap/DST), time-limit thresholds/reset, Safari domain/content/Private
Browsing lifecycle, permission revocation, monitor registration failure, clock
changes, and startup reconciliation. For each, record actual block and release,
not just an enabled toggle. For Open Limits, observe a charged grant, bounded
access expiry/re-shield, daily reset, and coexistence with a harder restriction.
Focus filter, Shortcuts, Sleep, Pomodoro rounds, and one-shot future scheduling
must each have their own completed acceptance case before appearing in review
notes/screenshots/copy. Website picker tokens and Safari content settings are
different mechanisms; record browser/OS and avoid an all-browser promise.

### R7 — Reports, history, completion, and privacy

Open Insights on a fresh install: it must tolerate missing/empty reports without
inventing usage. Generate modest real test usage, then inspect the report inside
the DeviceActivityReport extension. Record whether data actually appears and
which range is displayed. Exercise a real session completion, interrupted/early
stop, and relaunch; compare Today/Complete/history with lane F's local events.
Do not seed statistics to make screenshots attractive. Source at this baseline:
[report aggregation](../../../ios-app/Extensions/DeviceActivityReportExtension/TotalActivityReport.swift),
[report-private model](../../../ios-app/Extensions/DeviceActivityReportExtension/ActivitySummary.swift),
[durable focus history](../../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintHistoryStore.swift),
[history-driven completion](../../../ios-app/Flint/Features/Sessions/SessionViewModel.swift).
With network unavailable, verify core focus/exit and local-state behavior; test
public support/privacy links separately online. Use final archive inspection and
appropriate runtime observations to check that no Flint data-upload path was
added; offline success alone does not prove Data Not Collected.

### R8 — Restore safe state and package evidence

End any active test session, disable test rules/limits and optional Safari
settings through supported controls, and verify selected apps/sites and device
app-removal availability actually recover. Leave the test device without an
unexpected shield, PIN, schedule, or guard; if recovery fails, stop and escalate
to lane B/owner with the exact state. Do not blindly repeat state-changing input.
Record final permission state agreed with the owner. Repeat relevant steps on
iPad and minimum/current supported iOS, plus compact/large layout, VoiceOver,
and large text; source currently targets iOS 16+ and iPhone/iPad
([project.yml](../../../ios-app/project.yml)). Capture only approved real
screens from the companion shot-list. Human archive/export validation, upload,
processed TestFlight installation and store submission remain separate gates.

## Family Controls distribution request draft — unsent

Apple's [entitlement request guide](https://developer.apple.com/documentation/familycontrols/requesting-the-family-controls-entitlement)
requires the Account Holder to request distribution access for the app and its
Screen Time extensions. Approval status for **each** ID below is **unknown**;
declaring the entitlement in source does not demonstrate distribution approval.
The owner must inspect existing requests first and avoid duplicate submissions.
No turnaround time or approval outcome is predicted.

### Exact request inventory

1. **Flint host:** `com.flint.peakfocus` —
   [project.yml](../../../ios-app/project.yml);
   [host entitlements](../../../ios-app/Flint/Flint.entitlements).
   Requests individual authorization, presents the picker, and stores user rules.
2. **DeviceActivityMonitorExtension:** `com.flint.peakfocus.monitor` —
   [project.yml](../../../ios-app/project.yml);
   [monitor entitlements](../../../ios-app/Extensions/DeviceActivityMonitorExtension/Monitor.entitlements).
   Handles activity intervals/thresholds and intended shield lifecycle.
3. **ShieldConfigurationExtension:** `com.flint.peakfocus.shield-config` —
   [project.yml](../../../ios-app/project.yml);
   [shield configuration entitlements](../../../ios-app/Extensions/ShieldConfigurationExtension/ShieldConfig.entitlements).
   Supplies the system shield's appearance and text.
4. **ShieldActionExtension:** `com.flint.peakfocus.shield-action` —
   [project.yml](../../../ios-app/project.yml);
   [shield action entitlements](../../../ios-app/Extensions/ShieldActionExtension/ShieldAction.entitlements).
   Responds to shield actions under the user's local rules.
5. **DeviceActivityReportExtension:** `com.flint.peakfocus.report` —
   [project.yml](../../../ios-app/project.yml);
   [report entitlements](../../../ios-app/Extensions/DeviceActivityReportExtension/Report.entitlements).
   Renders Apple-supplied usage information inside the report extension.

6. **FlintWidgetsExtension:** `com.flint.peakfocus.widgets` — the display-only
   WidgetKit extension also renders the opt-in Live Activity. It reads local
   status/history snapshots and opens Flint; it does not arm protection or render
   Device Activity reports. It needs `group.com.flint.peakfocus` and its own
   signing/provisioning association, **not** the Family Controls entitlement.

These are six shipping bundles; `com.flint.peakfocus.tests` remains a test bundle.
App Intents/Focus filters remain in the host. The host and four Screen Time
extensions declare `com.apple.developer.family-controls` and the App Group;
the widget declares only the App Group. The group is not another bundle ID or
a claim of an approved portal record. Submit Family Controls requests only for
the five targets that use it, after checking existing approval state.

### Purpose text for the owner to adapt to each request

```text
Flint is a native, local-first focus utility for people who choose to manage distractions on their own iPhone or iPad. We request Family Controls distribution access for the host app and each of its four Screen Time extensions. The authorization flow uses FamilyControlsMember.individual with the device owner's approval; this is not remote parental supervision or enterprise device management.

The host app (com.flint.peakfocus) presents Apple's activity picker and lets the user configure focus sessions, schedules, and limits. The monitor (com.flint.peakfocus.monitor) handles Device Activity callbacks for restriction lifecycle. The shield configuration extension (com.flint.peakfocus.shield-config) provides the system shield's presentation. The shield action extension (com.flint.peakfocus.shield-action) handles actions under the local protection rules. The report extension (com.flint.peakfocus.report) renders Device Activity reports within its extension boundary.

Opaque selections and app-defined settings are shared locally using group.com.flint.peakfocus. The iOS source has no Flint account, analytics SDK, advertising SDK, or app-owned data-upload client. Screen Time report values are not exported to the host or a Flint server. All protection levels are available without a payment gate.

Managed Settings implements the restrictions selected by the user. Screen Time permission remains revocable in iOS Settings. We will supply final signed-device demonstration steps, the actual verified restriction and recovery scope, public privacy/support destinations, and any further information requested during review. This purpose text must be reconciled with the selected release's completed protection acceptance before it is sent.
```

This is preparatory purpose text, not a statement that the stronger-session
lifecycle, final policy, or physical demonstration is accepted. Before sending,
the owner must reconcile the text with lanes B/F and the exact release build,
replace the final paragraph with observed exit behavior if it changes, and
provide any requested developer/team/contact fields privately. Use the role
description for the specific identifier on each live form; do not imply approval
for extensions follows automatically from approval for the host.

### Human-owned account and signing follow-through

Follow the [single owner checklist](../README.md#one-human-owned-submission-checklist),
using the six-bundle inventory above and the [signing runbook](signing-and-release.md).
No portal mutation, signed archive, upload, review submission or publication is performed
or authorized by this draft.

## D6 validation boundary

This lane changes only this document and `store-package.md`. Verification is
limited to source-anchor inspection, metadata/Notes lengths, exact bundle-ID and
entitlement matching, local Markdown links, and whitespace/diff review. No heavy
builds, simulator/emulator sessions, device permission actions, screenshot
fabrication, or account actions are needed to validate these drafts. The PR
description records the measured values and source commit; later release evidence
must be supplied by its owning lanes and the human release owner.
