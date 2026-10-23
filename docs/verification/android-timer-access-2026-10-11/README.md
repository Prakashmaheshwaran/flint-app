# Dedicated Timer and access-ledger foundation

Verified 2026-10-11. Device: `flint-test`, API 35 arm64, 1080×2400,
density 420, font scale 1.0. The Accessibility service was off; Usage Access
and overlay permissions were on, exercising Flint's UsageStats backup path.

## Exact build and automated checks

- `make verify-android` passed (debug assembly plus all configured unit-test variants).
- Built and installed APK SHA-256:
  `e71c72d3c94a66a209c8c140b17cc461e8c11734006e166f78b9d12d31cd67a9`.
- [Artifact identity](build.json) records independent readback of the installed APK.
- [Test results](android-tests.json): **1,002 variant executions / 585 unique cases**,
  zero failures or errors. Added 24 Timer cases, six rule-store cases, and 36
  access-ledger cases; Android library cases execute in both debug/release variants.
- The initial rebuild emitted existing D8 Kotlin metadata rewriting warnings. It
  still assembled successfully. The final incremental gate passed; this report
  does not claim a warning-free clean build or an Android release/device matrix.

## Actual Timer flow

1. Opened the new **Home → My Apps → Timer** hub. Insights is reachable from Home;
   Back returns from Insights to Home.
2. Chose Chrome in Timer's app picker, changed 30 minutes to 5 minutes, and started
   an Easy session through the UI. The screen showed one app, a fixed end time,
   and a countdown.
3. Opened Chrome. Flint's real blocking surface appeared with the session reason
   and remaining time.
4. Force-stopped and relaunched Flint. Timer restored the same Chrome session and
   deadline; its countdown had decreased rather than restarted.
5. Opened the read-only selection dialog and observed Chrome. Navigating away
   from Timer left the session running.
6. Let the original five-minute deadline pass without changing the device clock.
   Chrome then opened to its welcome screen without Flint's blocking surface.
   Returning to Timer showed the finished message and idle draft.
7. Started a second Easy session and used **End timer → confirmation → End timer**.
   The running card disappeared and the cancellation message appeared. Cleared
   the test draft afterward. No active test session was left behind.
8. Checked Back from Timer's picker to Timer, My Apps to Home, and Account to
   Settings to Home. Only the current tab composes its Back handler.

| Capture | Evidence |
|---|---|
| [Home](01-home.png) | Three destinations, nested Insights/Profile entrypoints, running session summary |
| [Idle Timer](02-timer-idle.png) | App selection, finite durations, legacy tiers, focus/Detox duration presets |
| [Running](03-running.png) | Five-minute session with fixed deadline |
| [Chrome blocked](04-chrome-blocked.png) | Actual UsageStats-path enforcement |
| [After process restart](05-restored-running.png) | Same session restored with decreased remaining time |
| [Frozen selection](06-frozen-selection.png) | Read-only active targets |
| [Chrome after expiry](07-chrome-after-expiry.png) | Target app opens after the deadline |
| [Expired Timer](08-expired-timer.png) | Finished state and idle draft |
| [Cancellation confirmation](09-cancel-confirmation.png) | Explicit user choice before deletion |
| [Cancelled](10-cancelled.png) | Durable deletion reflected by the rule observer |

Companion XML files retain observed UI text/bounds. These captures belong to the
exact build above; subsequent managed-policy work has a separate gate.

## Persistence and overlap checks

The real DataStore transaction adapter and in-memory implementation share tests for
40 concurrent starts producing exactly one session, exact-ID cancellation, stronger
tiers, duplicate IDs, expired/disabled sessions, failed persistence, and preserving
unrelated rows. Cancellation rechecks the current tier in the deletion transaction.
Timer reads propagate I/O failures instead of treating them as an empty store.

Overlapping permanent rules are covered by store/engine tests; this run did not add
a second permanent rule on the emulator. Physical devices, Accessibility-path Timer
enforcement, overnight sessions, clock changes, and large-text/TalkBack remain
separate device checks.

## V2 access foundation is dormant

The new `temporary_access_v2` ledger is additive. Its pure policy implements the
APK-backed Standard 5-second / Increasing 20–120-second delays, atomic grant/count
updates, idempotent completion, exact cause scopes, and monotonic duration handling.

Tests include real PreferenceDataStore file close/reopen, 64 concurrent completions
producing one grant/debit, failed writes, malformed/version/type failures, unchanged
v1 bytes, scope/revision changes, and conservative reboot reconciliation. Managed
authority cannot receive a locally minted personal grant.

**Live blocking still uses Flint's existing break rules.** Activation needs complete
cause identities, policy/period adapters, an Android boot-aware clock, readiness,
and commit-before-unblock wiring across both detectors and fallback UI. The new
ledger does not make the app's current waiting room equivalent to Opal.

## Reference limitations

[APK trace](../../research/opal-4.14.0-timer-trace.md) establishes durations, presets,
creation calls, open-ended intervals and hold-control defaults. This milestone
does not implement infinite Timer starts, Timer website/allow-only selection,
hold gestures or Detox media. Opal's authenticated visuals remain uncaptured;
these screenshots prove Flint behavior, not visual identity with Opal.
