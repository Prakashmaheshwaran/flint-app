# Flint — iOS

Native Swift/SwiftUI for iOS/iPadOS 16+, using FamilyControls, ManagedSettings and
DeviceActivity. The [v1 acceptance ledger](../docs/acceptance/v1-capability-ledger.md)
is the current capability/evidence record. **Physical-device enforcement is pending.**

## Generate, build and verify

```sh
# from repository root; full Xcode 15.3+/Swift 5.10+ and XcodeGen required
make env
make ios-gen
make ios-build
make verify-ios
```

Edit [project.yml](project.yml), then regenerate; never hand-edit
`Flint.xcodeproj`. Configure private signing through
[signing-and-release](../docs/store/ios/signing-and-release.md), not committed credentials.
The simulator gate builds Debug, runs native tests and separately builds unsigned Release,
checks actual bundled privacy manifests, and rejects known DEBUG fixture markers.

## Targets and data boundaries

The host `com.flint.peakfocus` embeds four Screen Time extensions
(`.monitor`, `.shield-config`, `.shield-action`, `.report`) plus the
WidgetKit extension declared in project.yml. The Screen Time bundles use Family Controls
and `group.com.flint.peakfocus`; the widget uses its declared App Group and does not
acquire enforcement authority. Shared logic lives in the local
[FlintCore package](Packages/FlintCore).

Local focus-session events drive Today, durable Complete and focus Insights. A start or
planned deadline alone is Unconfirmed, never a completed session/streak. Confidence-aware
consumers discount uncertain gaps; durable typed ordinary-break history remains incomplete.
Screen Time
report data remains in the report extension and is not repurposed as a focus-history export.
The [history contract](../docs/architecture/focus-history.md) defines totals, gaps,
retention and unavailable-versus-zero behavior. Optional local reminders say the **planned**
focus time ended; delivery does not prove completion or uninterrupted protection.

Read-only widgets show observed status/next occurrence and local focus summaries only when
their merged host/monitor commit publishers provide valid data. Live Activity is opt-in and requires a finite,
armed session. Missing/stale input is unavailable; tests and compiled widgets do not prove
WidgetKit/ActivityKit delivery, Dynamic Island behavior or lock-screen privacy on hardware.

## Protection implementation and limits

Merged session work adds registration-before-shield, logical timing for short sessions,
elapsed same-boot expiry, bounded reboot checkpoints, durable generation/start replay,
owned replacement/stop arbitration and persisted Harder waits.
Startup recovery and health distinguish saved, attempted, armed and observed state, using
the actual observation time. Their [source/tests and PRs](../docs/acceptance/v1-capability-ledger.md#evidence-index)
bound these claims.

[ADR-012](../docs/architecture/protection-contract.md) defines Easy/Harder/Hardcore,
ordinary breaks, timed Open credits and a rolling seven-day/global recovery pass.
Live mutation, global pass, scoped break and credit adapters are merged and tested;
remaining pre-migration/no-anchor/successor variants are listed in the ledger's
[Open at v1 wind-down](../docs/acceptance/v1-capability-ledger.md#open-at-v1-wind-down).
No source closure establishes signed-device enforcement.

Current scope includes recurring weekday schedules, daily Time Limits, explicit timed Open credits,
saved groups/presets, schedule/Sleep Allow Lists, app-open PIN, Focus Filter and three
Shortcut intents. Known boundaries at this checkpoint:

- Future date-specific one-shot scheduling is absent; recurring later-week windows are
  distinct from arbitrary future dates. The Social detox preset has a 23:59–00:00 gap.
- The Pomodoro intent starts its first focus round and saves cadence; full focus/rest
  chaining is absent. Immediate Allow List sessions are not implemented.
- Open credits are enabled by [#189](https://github.com/Prakashmaheshwaran/flint-app/pull/189).
  R-12 is source-complete, runtime **DEVICE-PENDING**, with a hard pre-release signed-device
  PC-28..38 gate: killed-host 300 s expiry, midnight, re-registration and lock contention.
  If it fails, revert `FlintOpenCredit.shieldAcceptanceEnabled` in one line and restore
  fallback copy. These are explicit accepted grants, not DeviceActivity launch counts.
- Settings → **Web & Safari** applies the Safari web-content filter during focus sessions
  (Block Now, Shortcuts/Pomodoro and Focus filters), and independently during schedules/Sleep
  when **Schedules and Sleep** is on. Choose adult-content filtering with extra block/allow
  domains or an allowed-sites-only list, plus an optional explicit-media restriction. Each
  cause owns its store and frozen child policy, so its end clears only its own settings;
  Time/Open Limits are excluded.
  Private Browsing and Clear History and Website Data lock as an iOS side effect while the
  filter is active. Other browsers are not filtered. Real Safari filtering, those side effects,
  cross-store composition and permission revocation remain device-unverified; the Simulator
  cannot verify them.
  Each website list accepts up to 50 domains. Weakening an active cause follows its tier:
  Easy applies immediately; Harder requires a persisted wait and final confirmation;
  Hardcore edits stay unsaved drafts until the commitment ends (no automatic edit is queued).
- Focus/Shortcut starts share session checks. Easy Focus-off can end its owned session;
  strict finite commitments keep their stop rights and may require confirmation in Flint.
- New/edited empty Allow Lists, including Sleep Full Assist without an allow group, are
  refused. Legacy empty lists keep running with a needs-attention note. Phone, default
  calling app, in-call UI and SOS under `.all(except:)` require signed-device acceptance;
  the R-26 decision moves to option B if those screens are shielded.
- PIN is a UI privacy barrier with minimal recovery reachable while locked. #223's exact
  hosted fixtures pass at default/AX5; physical recovery and full VoiceOver remain unverified.
- iOS app-deletion restrictions are device-wide, require authorization, and must follow
  the contract's explicit opt-in/finite-owner rules before being advertised. OS permission
  revocation stays reachable; no device-lock guarantee follows from a setting assignment.

Opaque tokens, Always Allowed, Safari versus other browsers, finite registration capacity,
late callbacks and unknown cross-reboot elapsed time are platform limits. Flint does not
veto OS clock changes. Same-boot duration tests do not prove arbitrary reboot/clock tamper
resistance. Sleep audio/stories and cross-device sync are outside local v1.

## Hardware and store acceptance

Use the [single ordered device checklist](../docs/acceptance/device-acceptance-plan.md)
for safety, strict commitments/pass, short deadlines while the host is killed, thresholds,
Safari, Focus/Siri, report delivery, notifications and accessibility. Fixture screenshots
in [verification](../docs/verification/) keep their original build/date and do not prove
Family Controls authorization or shields.

[iOS store drafts](../docs/store/ios/store-package.md), privacy manifests and an unsigned
Release build are preparation. Apple team/account facts, Family Controls distribution
approval for the five Screen Time IDs, widget provisioning, signed archive validation,
real-device screenshots, TestFlight and submission remain
[human-owned](../docs/store/README.md). No account, cloud sync or analytics is required.
