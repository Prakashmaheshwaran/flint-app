# Changelog

All notable changes to Flint will be documented in this file.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning: [SemVer](https://semver.org/).

## [Unreleased]

## [0.1.0] — Initial public release

First public snapshot of both native apps.

### iOS (Swift / SwiftUI, Screen Time API)
- Block Now, unlimited schedules, Time Limits, break levels incl. free Hardcore,
  free weekly Emergency Pass, website blocking, embedded usage report,
  Sleep Mode + Morning Assist, Open Limits, Focus Filters, Siri/Shortcuts intents.
- Built and unit-tested against the Simulator. **Shield enforcement requires a physical
  device** (an Apple platform constraint — the Simulator cannot grant Family Controls).

### Android (Kotlin / Jetpack Compose, multi-module)
- Real blocking via AccessibilityService + overlay, schedule-gated blocking, Time Limits,
  screen-time stats, branded block screen, boot/resilience layer — verified on an emulator.
- Pure-Kotlin `BlockDecisionEngine` with JVM unit tests.
