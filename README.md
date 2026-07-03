<div align="center">

<img src="design/app-icon.svg" width="88" height="88" alt="Flint logo" />

# Flint — Peak Focus

**Everything Opal does. Zero cost. Forever.**

A free, open-source alternative to [Opal](https://www.opal.so). The whole focus engine —
app & website blocking, unlimited schedules, hardcore Deep Focus, time & open limits — with
**no paywall, no accounts, no telemetry**. Local-first. Built in the open.

![status](https://img.shields.io/badge/status-early%20development-EF9F27?style=flat-square)
![license](https://img.shields.io/badge/license-MIT-2C2C2A?style=flat-square)
![ios](https://img.shields.io/badge/iOS-16%2B-2C2C2A?style=flat-square)
![android](https://img.shields.io/badge/Android-6%2B-2C2C2A?style=flat-square)
[![iOS CI](https://github.com/Prakashmaheshwaran/flint-app/actions/workflows/ios.yml/badge.svg)](https://github.com/Prakashmaheshwaran/flint-app/actions/workflows/ios.yml)
[![Android CI](https://github.com/Prakashmaheshwaran/flint-app/actions/workflows/android.yml/badge.svg)](https://github.com/Prakashmaheshwaran/flint-app/actions/workflows/android.yml)

</div>

---

## Why Flint

Opal gates the features that actually make blocking *stick* behind "Opal Pro" — recurring
schedules, the non-bypassable **Deep Focus / Hardcore** mode, the **Allow List** brick-phone
mode, and the **Emergency Pass**. Flint's wedge is simple: **ship the entire engine for free.**

Flint takes nothing from you in return. No sign-up. No analytics. No cloud. Your blocklists
and usage data never leave your device. The hard part was never the paywall (we just remove
it) — it's faithfully reimplementing the OS-level enforcement that makes a block real. That's
what this project is.

### What's free here that Opal charges for

| Feature | Opal | **Flint** |
|---|:---:|:---:|
| Block Now / focus timer | Free | **Free** |
| Scheduled sessions | Free (≤ 24h ahead) | **Free, no cap** |
| Recurring / Smart Schedules | 💰 Pro (capped free) | **Free, unlimited** |
| Block List | Free | **Free** |
| **Allow List** (brick-phone mode) | 💰 Pro | **Free** |
| **Deep Focus / Hardcore** (non-bypassable) | 💰 Pro | **Free** |
| Time Limits | Free | **Free** |
| Open Limits | Free (no-reset tier 💰) | **Free, incl. no-reset** |
| Anti-bypass suite | Free | **Free** |
| **Emergency Pass** | 💰 Pro | **Free, weekly** |
| Accounts required | — | **None** |
| Telemetry / ads | — | **None** 

Full feature parity map: [`docs/research/01-opal-feature-inventory.md`](docs/research/01-opal-feature-inventory.md).

---

## How it works (the honest version)

Blocking is an OS-privileged capability, and the two platforms are completely different — so
Flint is **two native apps**, not one cross-platform build:

- **iOS** — Apple's **Screen Time API** (`FamilyControls` · `ManagedSettings` · `DeviceActivity`).
  The blocking runs in system extensions the OS drives even when the app is closed.
  → [`docs/research/02-ios-technical-strategy.md`](docs/research/02-ios-technical-strategy.md)
- **Android** — **AccessibilityService** (real-time foreground + URL detection) +
  **UsageStatsManager** (limits) + an overlay enforcement layer, hardened against OEM battery
  killers. → [`docs/research/03-android-technical-strategy.md`](docs/research/03-android-technical-strategy.md)

Why these decisions were made: [`docs/architecture/00-architecture-decisions.md`](docs/architecture/00-architecture-decisions.md).

---

## Repository layout

```
flint/
├── ios-app/        Native iOS app (Swift / SwiftUI) — host app + 4 Screen Time extensions
├── android-app/    Native Android app (Kotlin / Jetpack Compose) — multi-module
├── design/         Brand tokens, palette, logo — single source of truth
├── docs/
│   ├── research/       Opal feature inventory + per-platform technical strategy + OSS landscape
│   └── architecture/   Architecture Decision Record
├── Makefile        make ios / make android / make clean …
└── LICENSE  CONTRIBUTING.md  CODE_OF_CONDUCT.md
```

---

## Status

**Android — core blocking verified on an emulator; a large batch of newer work is merged and
awaits re-verification.** Verified end-to-end earlier (screenshots + logs): pick apps → enable the
service (behind the consent screen) → blocked apps get a full-screen block screen, plus
**schedule-gated** blocking and **Time Limits**. Merged since then: engine parity with iOS (all
three break levels incl. free Hardcore, Open Limits, free weekly Emergency Pass), DataStore
persistence, a blocklist/schedule/limit-authoring UI, screen-time stats, a branded
break-level-aware block screen on both enforcement paths, an OEM/battery resilience layer (boot
re-arm, exit diagnostics, permission health), a four-tab app shell, and a unified (ISO) weekday
convention across both detection paths. The integrated app has **not been rebuilt or re-run
since** — emulator validation is pending a re-run. See [`android-app/README.md`](android-app/README.md).

**iOS — comprehensive; earlier verticals verified in the Simulator + unit tests, the newest merges
await a compile pass.** Verified earlier (builds, `FlintCore` unit tests, Simulator runs): Block
Now, unlimited Schedules, Time Limits, free Hardcore + free weekly Emergency Pass, website
blocking, app groups/presets + Allow List, app-open PIN, embedded usage report. Merged since,
**compile verification still pending on a macOS toolchain**: Safari/Private-Browsing restrictions,
Focus Filter integration, Siri & Shortcuts intents, Sleep Mode + Morning Assist, and Open-Limits
enforcement (engine-side complete; the config UI and shield arming are a known gap, so it is not
user-reachable end-to-end yet). And the standing Apple caveat: Screen Time **shield enforcement
can only be proven on a physical device** (the Simulator can't grant Family Controls or apply
shields) — an on-device hardware pass is still pending for everything enforcement-shaped. See
[`ios-app/README.md`](ios-app/README.md).

## Building

```bash
make help          # list all targets
make doctor        # check your toolchain (Xcode, Android SDK, JDK, XcodeGen)
make ios           # generate the Xcode project (XcodeGen) and open it
make ios-build     # compile the iOS app + extensions for the simulator
make android       # assemble the Android debug APK
make android-install  # build + install on a connected device/emulator
```

- **iOS:** macOS + Xcode 15+, [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`). The
  `.xcodeproj` is generated from [`ios-app/project.yml`](ios-app/project.yml) — never hand-edited. To run on
  device, open with `make ios`, set your Development Team on each target, and run.
- **Android:** JDK 21 + Android SDK (API 35). Android Studio recommended but not required.

---

## Roadmap (v1)

**Implemented:** core blocking loop, unlimited schedules, Time Limits, break levels incl. **free
Hardcore**, free weekly Emergency Pass, website blocking, app groups + Allow List, app-PIN, usage
report, Focus Filter, Siri/Shortcuts intents, Sleep Mode + Morning Assist, Open-Limits enforcement
engine (iOS); engine parity — break levels, Open Limits, weekly Emergency Pass — plus persistence,
rule/schedule/limit authoring, stats, branded block screen, resilience layer, four-tab app
(Android). **Verification debt (the honest part):** the newest iOS merges need a macOS compile
pass; iOS enforcement needs an on-device hardware pass; the integrated Android app needs an
emulator re-run. **Next:** iOS Open-Limits config UI + arming (the enforcement engine is in, but
unreachable without them), the verification passes above, deeper anti-bypass (uninstall /
time-change guards), a preset routine library, then optional opt-in cross-device sync. Details:
[`docs/research/01-opal-feature-inventory.md` → "Minimum-Viable Flint v1"](docs/research/01-opal-feature-inventory.md).

---

## Contributing

Flint exists because blocking shouldn't cost money. PRs, platform expertise, and bug reports
welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md) and [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).

## License

[MIT](LICENSE). Free to use, fork, and ship. *(If you ship your own build, see the fork notes
in `CONTRIBUTING.md` — you'll need your own app identifiers and, on iOS, your own Apple
entitlement approval.)*

---

> *Not affiliated with, endorsed by, or connected to Opal. "Opal" is referenced only for
> factual feature comparison.*
