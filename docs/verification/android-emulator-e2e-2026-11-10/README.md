# Android emulator end-to-end — 2026-11-10

Manual UI walkthrough plus the instrumented emulator suite on the local
`flint-test` AVD (Android 15 / API 35, arm64, software-rendered). The same
suite runs on the hosted API 36 x86_64 runner in CI.

## Fixes in this change

- **`GlanceStartupWidgetTest` taps a rendered widget row.** On a host that
  renders the widget only once, the widget's first rows (title and status) are
  clipped from the surface, so tapping the status bounds missed on API 35. The
  hosted API 36 CI rendered the full widget and passed. Tapping a row that is
  actually on the rendered surface keeps the tap-to-open assertion meaningful
  on both hosts.
- **`TimerViewModel.setVisible(true)` wakes the detectors on arrival.** A
  safety floor that went stale since the last evaluation could otherwise
  disable Begin the moment the Start focus sheet opened; the sheet now asks
  the detectors for a fresh evaluation first.

## Results

- `make verify-android`: PASS (debug/release builds, local-first release APK
  verification, all Gradle unit-test variants).
- Instrumented suite: **12 / 12 PASS** (11 / 12 before the widget fix; the one
  failure was the widget tap above).
- Manual walkthrough: onboarding and consent, Today / Rules / Insights / You,
  the Start focus sheet (duration, app picker, break levels, presets), Help,
  Privacy policy, open-source licenses, and a live schedule block (Chrome
  shield with Go back / Take a break / Open Flint).

## Evidence

- `smoke/` — per-test screenshots and accessibility hierarchies written by the
  instrumented run.
- `setup-summary.json` — setup readiness and ANR evidence for the run.
- `test-results.xml` — JUnit results for the instrumented suite.
- `boot-incarnation.jsonl` — verified boot identity before and after the run.

Random run tokens, boot IDs and session IDs are redacted to `<redacted-uuid>`
in the committed evidence so the public tree does not carry high-entropy
identifiers.

## Scope

Emulator evidence only. It does not certify physical-device Screen Time /
Accessibility behavior, OEM background survival, or the full supported-OS
matrix.
