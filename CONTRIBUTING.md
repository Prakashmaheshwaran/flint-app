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
  Unit tests run via Gradle's `test`, never `testDebugUnitTest` — the pure-Kotlin modules have no
  Android build variants, so the variant-specific task skips them without failing.
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
- Run the gate for the side you touched: `make verify-ios` or `make verify-android` (`make verify`
  runs both). Each mirrors that platform's CI workflow, so a green local run should mean a green PR.
- Touching `scripts/` or the `Makefile`: `make selftest` passes. It needs no emulator, Gradle,
  Xcode, or Android SDK.
- Be kind. See [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).

## Cutting a release

[`release.yml`](.github/workflows/release.yml) names the published APK after the git tag, but
Gradle stamps the APK itself from `versionName` / `versionCode`. Nothing keeps those two in step
on its own — so check the tree *before* you create the tag:

```bash
make release-check TAG=v0.2.0
```

It verifies that:

- `android-app/app/build.gradle.kts` → `versionName` equals the tag minus its leading `v`
- `ios-app/project.yml` → `MARKETING_VERSION` equals the tag's `X.Y.Z`
- `versionCode` is strictly greater than the previous release tag's — an unchanged `versionCode`
  makes the new APK refuse to install over the last one (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`)
- `CHANGELOG.md` has a `## [X.Y.Z]` section

Green means safe to tag. Run this before publishing the tag so version mistakes are caught while
they are still just a working-tree edit.
