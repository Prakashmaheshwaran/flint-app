# Flint iOS review notes and device review script — draft

Prepared 2026-10-28, source baseline
`fa190e9df96f61c13ea651ee39cae6f68057f0c6`. Companion:
[store metadata, privacy justification, age-rating rationale, and shot-list](store-package.md).
No Apple account was accessed, no entitlement request was submitted, and no
signed build, TestFlight upload, or physical-device behavior was verified by D6.
All unchecked gates below remain open. Human-owned identity/contact information,
credentials, certificates, profiles, and account evidence must stay out of git.

## Before these notes can be submitted

- [ ] Lane B's final iOS enforcement changes and acceptance evidence are linked,
  including ADR-011 semantics, Safari lifecycle, monitor failure, permission loss,
  overlap arbitration, bounded Open Limits, and session expiry after host death.
- [ ] Lane C's Android evidence is linked before any cross-platform parity claim;
  Android evidence is not a substitute for iOS physical tests.
- [ ] Lane F's real local-event history/Insights evidence is linked; final UI and
  listing make only supported history/completion/report claims.
- [ ] Lane D's final Release archive is inspected for all five embedded bundles,
  required-reason privacy manifests, AppIcon/resources, correct version/build,
  absence of DEBUG preview fixtures, and distribution-signed entitlements.
- [ ] A human Account Holder confirms approval/provisioning coverage for each of
  the five identifiers below, the intended signing team, and shared App Group.
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

The host uses Family Controls and Managed Settings. Four embedded extensions monitor activity boundaries, configure the shield, respond to shield buttons, and render Device Activity reports. Selection tokens and settings are shared locally through group.com.flint.peakfocus. Report values are rendered inside the report extension, not exported to a Flint server. New installs may have no report data; a missing report must not be read as zero activity.

Stronger protection levels are optional. A Hardcore session can temporarily deny removal of apps across the device, not just removal of Flint. Start with Easy. Before testing Hardcore, read its in-app duration/exit explanation and confirm an Emergency Pass is available. Use its Emergency Pass control in Flint if an early exit is needed, or wait for the verified timed session to end. The app-open PIN is separate from Apple's Screen Time passcode; no preconfigured PIN is supplied. Review Screen Time access in iOS Settings to withdraw authorization if needed.

The About link opens an external browser. Core focus behavior does not require a Flint server. Public support/privacy destinations and the App Review contact are supplied in their dedicated App Store Connect fields.
```

Apple's [platform version reference](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/)
limits App Review Notes to 4,000 bytes. Do not put test Apple IDs, a device PIN,
passcode, signing credentials, or a fabricated reviewer login in this field.
Use the dedicated human-owned contact fields. No attachment or review video is
claimed; if Apple requests one, the owner can capture the actual candidate and
review its privacy before supplying it.

D6 measured this exact Notes block as recorded in the PR validation evidence.
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
Lane B/J acceptance cases and ADR-011 take precedence if this draft becomes stale.

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
[onboarding](../../../ios-app/Flint/Features/Onboarding/OnboardingView.swift#L73-L109),
[authorization](../../../ios-app/Flint/Features/Authorization/AuthorizationModel.swift#L30-L40),
[denied recovery](../../../ios-app/Flint/Features/Authorization/AuthorizationControls.swift#L15-L34).
Never classify a fake authorized UI as system approval.

### R3 — Real selection and a stoppable session

Today → Start focus → Apple picker. Select one nonessential test app, no essential
apps or broad categories. Save a benign group name, choose the 25-minute preset,
then press minus twice to reach 15 minutes; choose Easy and begin. The baseline's
minus control reduces duration by five minutes per press. Record countdown, actual selected
app shield, and access to the unselected app. End session in Flint and verify
the selected app is accessible. Reopen Flint and confirm the saved selection
and group persist. A selected website token is a separate test in R6.
Source: [duration presets](../../../ios-app/Flint/Features/Today/StartFocusSheet.swift#L16),
[duration decrement](../../../ios-app/Flint/Features/Today/StartFocusSheet.swift#L144-L150),
[start sheet](../../../ios-app/Flint/Features/Today/StartFocusSheet.swift#L18-L164),
[saved groups](../../../ios-app/Flint/Features/Sessions/SessionViewModel.swift#L49-L70),
[session exits](../../../ios-app/Flint/Features/Today/SessionView.swift#L83-L94).

### R4 — Extension behavior and timed expiry with the host closed

Start another timed Easy session with the same test selection. Launch the selected
app to confirm a real system shield and its actual button behavior; record pixels
and access outcome, not just callback success. Close/terminate the Flint host
while the session is active, wait through its real end time, then verify that
the test app opens **before relaunching Flint**. Also test reboot across expiry
on dedicated hardware and after relaunch; distinguish background release from
foreground reconciliation. Verify no stale restriction/removal guard remains.
Source roles:
[monitor](../../../ios-app/Extensions/DeviceActivityMonitorExtension/FlintMonitor.swift#L1-L30),
[shield configuration](../../../ios-app/Extensions/ShieldConfigurationExtension/FlintShieldConfiguration.swift#L16-L48),
[shield action](../../../ios-app/Extensions/ShieldActionExtension/FlintShieldAction.swift#L22-L37).
Actual reliable expiry remains lane B + physical evidence, not a D6 finding.

### R5 — Optional stronger protection and controlled exit

Only on a consenting owner's test device, with known recovery access and an
available Emergency Pass: read the final Hardcore disclosure, choose a bounded
duration and the same single nonessential app. Confirm the UI explains that
app-removal denial is device-wide and is temporary. Verify the normal stop/edit/
replacement behavior against merged ADR-011 across UI, Shortcuts, and Focus.
Observe removal availability without completing deletion. Exercise the in-app
Emergency Pass confirmation/hold once and verify actual shield/guard release;
do not reset defaults or change the clock to replenish it. In a separate timed
run, verify expiry releases the guard with the host closed. Record the pass's
actual state/cooldown; do not promise a fixed weekly policy until ADR-011/B agree.
Confirm Settings withdrawal of authorization and subsequent honest UI state as
an owner-approved recovery test. Never rely on an unverified PIN bypass or an
unproven claim that withdrawal instantly clears every restriction.
Source: [guard scope](../../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintUninstallGuard.swift#L16-L28),
[pass implementation](../../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintSessionController.swift#L136-L152),
[app PIN](../../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintPIN.swift#L4-L6).

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
[report aggregation](../../../ios-app/Extensions/DeviceActivityReportExtension/TotalActivityReport.swift#L14-L38),
[report-private model](../../../ios-app/Extensions/DeviceActivityReportExtension/ActivitySummary.swift#L3-L22),
[in-memory completion](../../../ios-app/Flint/Features/Sessions/SessionViewModel.swift#L156-L169).
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
([project.yml](../../../ios-app/project.yml#L18-L31)). Capture only approved real
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
   [project.yml](../../../ios-app/project.yml#L48-L49);
   [host entitlements](../../../ios-app/Flint/Flint.entitlements#L5-L9).
   Requests individual authorization, presents the picker, and stores user rules.
2. **DeviceActivityMonitorExtension:** `com.flint.peakfocus.monitor` —
   [project.yml](../../../ios-app/project.yml#L73-L75);
   [monitor entitlements](../../../ios-app/Extensions/DeviceActivityMonitorExtension/Monitor.entitlements#L5-L9).
   Handles activity intervals/thresholds and intended shield lifecycle.
3. **ShieldConfigurationExtension:** `com.flint.peakfocus.shield-config` —
   [project.yml](../../../ios-app/project.yml#L87-L89);
   [shield configuration entitlements](../../../ios-app/Extensions/ShieldConfigurationExtension/ShieldConfig.entitlements#L5-L9).
   Supplies the system shield's appearance and text.
4. **ShieldActionExtension:** `com.flint.peakfocus.shield-action` —
   [project.yml](../../../ios-app/project.yml#L101-L103);
   [shield action entitlements](../../../ios-app/Extensions/ShieldActionExtension/ShieldAction.entitlements#L5-L9).
   Responds to shield actions under the user's local rules.
5. **DeviceActivityReportExtension:** `com.flint.peakfocus.report` —
   [project.yml](../../../ios-app/project.yml#L118-L120);
   [report entitlements](../../../ios-app/Extensions/DeviceActivityReportExtension/Report.entitlements#L5-L9).
   Renders Apple-supplied usage information inside the report extension.

These are exactly the five shipping bundles, not the unit-test identifier
`com.flint.peakfocus.tests`. App Intents/Focus filters are in the host; no sixth
shipping extension is proposed. All five entitlement files declare
`com.apple.developer.family-controls` and `group.com.flint.peakfocus`; the latter
is an App Group, not another bundle ID or a claim of an approved portal record.

### Purpose text for the owner to adapt to each request

```text
Flint is a native, local-first focus utility for people who choose to manage distractions on their own iPhone or iPad. We request Family Controls distribution access for the host app and each of its four Screen Time extensions. The authorization flow uses FamilyControlsMember.individual with the device owner's approval; this is not remote parental supervision or enterprise device management.

The host app (com.flint.peakfocus) presents Apple's activity picker and lets the user configure focus sessions, schedules, and limits. The monitor (com.flint.peakfocus.monitor) handles Device Activity callbacks for restriction lifecycle. The shield configuration extension (com.flint.peakfocus.shield-config) provides the system shield's presentation. The shield action extension (com.flint.peakfocus.shield-action) handles actions under the local protection rules. The report extension (com.flint.peakfocus.report) renders Device Activity reports within its extension boundary.

Opaque selections and app-defined settings are shared locally using group.com.flint.peakfocus. The iOS source has no Flint account, analytics SDK, advertising SDK, or app-owned data-upload client. Screen Time report values are not exported to the host or a Flint server. All protection levels are available without a payment gate.

Managed Settings implements the restrictions selected by the user. Optional stronger sessions can temporarily restrict device-wide app removal; the release must explain scope, duration, and the verified safe exit. We will supply final signed-device demonstration steps, public privacy/support destinations, and any further information requested during review.
```

This is preparatory purpose text, not a statement that the stronger-session
lifecycle, final policy, or physical demonstration is accepted. Before sending,
the owner must reconcile the text with lanes B/F and the exact release build,
replace the final paragraph with observed exit behavior if it changes, and
provide any requested developer/team/contact fields privately. Use the role
description for the specific identifier on each live form; do not imply approval
for extensions follows automatically from approval for the host.

### Human-owned account and signing follow-through

1. Account Holder checks membership/team and existing capability request status
   for each exact ID; record unknown/pending/assigned/rejected from actual portal
   evidence. Confirm distribution methods/profile support and actual App Group
   association. Keep private identifiers/contact evidence outside this repo.
2. If needed, the human submits the reviewed request for each ID and records
   submission date/reference. This draft authorizes no portal mutation.
3. After approval, the owner configures distribution signing/profiles for all
   five bundles, inspects entitlements in the signed archive, and validates
   resources/privacy manifests/Release fixture exclusion with lane D. Do not
   hand-edit the generated Xcode project; the source is `ios-app/project.yml`.
4. The human creates/confirms the App Store Connect record, chooses final
   version/build/category/age ratings/privacy/contact/URLs/screenshots, validates
   and uploads the archive, and verifies processing and TestFlight installation
   before running the release acceptance script on that exact binary.
5. Submission, TestFlight distribution, and App Store publication are explicit
   owner actions after review. D6's branch/PR does not perform or authorize them.

## D6 validation boundary

This lane changes only this document and `store-package.md`. Verification is
limited to source-anchor inspection, metadata/Notes lengths, exact bundle-ID and
entitlement matching, local Markdown links, and whitespace/diff review. No heavy
builds, simulator/emulator sessions, device permission actions, screenshot
fabrication, or account actions are needed to validate these drafts. The PR
description records the measured values and source commit; later release evidence
must be supplied by its owning lanes and the human release owner.
