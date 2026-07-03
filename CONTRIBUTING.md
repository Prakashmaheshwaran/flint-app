# Contributing to Flint

Thanks for helping build a focus app that doesn't cost money. Flint is two native apps in one
monorepo; pick the side you know and dive in.

## Before you start

1. Read [`docs/architecture/00-architecture-decisions.md`](docs/architecture/00-architecture-decisions.md) — the *why* behind the stack.
2. Read the platform strategy for your side:
   - iOS → [`docs/research/02-ios-technical-strategy.md`](docs/research/02-ios-technical-strategy.md)
   - Android → [`docs/research/03-android-technical-strategy.md`](docs/research/03-android-technical-strategy.md)
3. The product spec (what to build, free vs. Opal-paid) → [`docs/research/01-opal-feature-inventory.md`](docs/research/01-opal-feature-inventory.md).

## Toolchain

Run `make doctor` to see what you're missing.

- **iOS:** macOS, Xcode 15+, `brew install xcodegen`. The `.xcodeproj` is **generated** from
  [`ios-app/project.yml`](ios-app/project.yml) — edit the YAML, run `make ios-gen`, never hand-edit
  the project file (it's git-ignored).
- **Android:** JDK 21 (`brew install openjdk@21`) + Android SDK (API 35). Android Studio is the
  easy path; Gradle CLI works too. `make android` builds the debug APK.

## Project conventions

- **iOS:** SwiftUI, idiomatic Swift, shared logic in the `FlintCore` Swift package so the app and
  all four extensions can use it. Extensions stay tiny and stateless beyond the App Group.
- **Android:** Kotlin + Compose, multi-module. The block decision logic is pure Kotlin in
  `blocking-engine` (no Android deps, unit-tested). Detection paths and enforcement live in
  separate modules so the app degrades gracefully when AccessibilityService is unavailable.
- Keep design values in sync with [`design/tokens.json`](design/tokens.json).
- No telemetry, no analytics SDKs, no required accounts. This is a hard rule (see brand ethos).

## Play Store / App Store compliance (do not skip)

- **Android:** Flint must **not** declare `isAccessibilityTool="true"` and **must** show the
  in-app prominent-disclosure + affirmative-consent screen before the accessibility hand-off.
  This is a Play review gate, not optional. Details in the Android strategy doc, §4.
- **iOS:** the Screen Time entitlement is approved per Apple ID + Bundle ID. See fork notes below.

## Forking & shipping your own build

The source is MIT — fork and ship freely. But the *shipping identity* is not transferable:

- Change the bundle IDs / application ID (`com.flint.peakfocus`) to identifiers you control.
- **iOS:** file your own `com.apple.developer.family-controls` **distribution** requests — one per
  Bundle ID (app + 4 extensions). Free to develop without it; required to ship to the App Store.
  A GitHub repo URL is accepted as the "website."

## Pull requests

- Branch from `main`, keep PRs focused, describe the user-facing change.
- iOS: build cleanly via `make ios-gen` + Xcode. Android: `make android` and `make android-test` pass.
- Be kind. See [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).
