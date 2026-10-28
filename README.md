<div align="center">

<img src="design/app-icon.svg" width="88" height="88" alt="Flint logo" />

# Flint — Peak Focus

**Native focus tools, built in the open.**

A free, open-source alternative to [Opal](https://www.opal.so). The whole focus engine —
app & website blocking, schedules without a Flint-imposed count cap, hardcore Deep Focus,
time & open limits — with
**no accounts in release builds and no analytics SDK**. Local-first. Built in the open.

**Android development sandbox:** Debug builds include optional account/profile/group tools
and managed-policy authoring against a local test service. The connected client and Firebase
account SDK are excluded from release APKs. Managed school/family blocking, production accounts
and billing are not active. See the [evidence and completion ledger](docs/research/06-opal-android-parity.md).

![status](https://img.shields.io/badge/status-early%20development-EF9F27?style=flat-square)
![license](https://img.shields.io/badge/license-MIT-2C2C2A?style=flat-square)
![ios](https://img.shields.io/badge/iOS-16%2B-2C2C2A?style=flat-square)
![android](https://img.shields.io/badge/Android-6%2B-2C2C2A?style=flat-square)
[![iOS CI](https://github.com/Prakashmaheshwaran/flint-app/actions/workflows/ios.yml/badge.svg)](https://github.com/Prakashmaheshwaran/flint-app/actions/workflows/ios.yml)
[![Android CI](https://github.com/Prakashmaheshwaran/flint-app/actions/workflows/android.yml/badge.svg)](https://github.com/Prakashmaheshwaran/flint-app/actions/workflows/android.yml)

</div>

---

## Get Flint

[![Download the Android APK](https://img.shields.io/badge/Android%20APK-download-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Prakashmaheshwaran/flint-app/releases/latest)
[![Google Play — coming soon](https://img.shields.io/badge/Google%20Play-coming%20soon-555555?style=for-the-badge&logo=googleplay&logoColor=white)](https://github.com/Prakashmaheshwaran/flint-app/releases/latest)
[![App Store — coming soon](https://img.shields.io/badge/App%20Store-coming%20soon-0D96F6?style=for-the-badge&logo=apple&logoColor=white)](https://github.com/Prakashmaheshwaran/flint-app/releases/latest)

- **Android — download now:** grab `flint-*-android-development-preview.apk` (older
  releases use `flint-*-android-debug.apk`) from the
  [latest release](https://github.com/Prakashmaheshwaran/flint-app/releases/latest) and allow
  "install unknown apps" when Android asks. It's a **development-signed preview**.
  New previews use release code with the debug-only connected sandbox excluded.
  Different signing certificates cannot update each other; uninstalling can erase
  local rules/history. [APK/AAB builds and owner signing](docs/store/android/bundles.md)
  remain separate from Play submission.
- **Google Play:** coming soon.
- **App Store (iOS):** coming soon — iOS can't be meaningfully sideloaded (Screen Time
  entitlements are granted per developer), so until then iOS folks build from source
  (see [Building](#building)).

---

## Why Flint

Opal gates the features that actually make blocking *stick* behind "Opal Pro" — recurring
schedules, the non-bypassable **Deep Focus / Hardcore** mode, the **Allow List** brick-phone
mode, and the **Emergency Pass**. Flint's wedge is simple: **ship the entire engine for free.**

Local blocking works without sign-up. Personal blocklists and usage history remain on the device.
The optional Android connected flow sends test account, profile and membership data to the local
service. Saving a managed policy also sends its explicitly chosen app packages, domains, schedule
and member scope. It does not upload personal blocklists, usage history or an installed-app
inventory. Full connected parity is tracked separately from the existing enforcement engine.

### What's free here that Opal charges for

| Feature | Opal | **Flint** |
|---|:---:|:---:|
| Block Now / focus timer | Free | **Free** |
| Scheduled sessions | Free (≤ 24h ahead) | **Free, no 24h advance cap** |
| Recurring / Smart Schedules | 💰 Pro (capped free) | **Free, no Flint-imposed count cap¹** |
| Block List | Free | **Free** |
| **Allow List** (brick-phone mode) | 💰 Pro | **Free** |
| **Deep Focus / Hardcore** (non-bypassable) | 💰 Pro | **Free** |
| Time Limits | Free | **Free** |
| Open Limits | Free (no-reset tier 💰) | **Free, incl. no-reset** |
| Anti-bypass suite | Free | **Free** |
| **Emergency Pass** | 💰 Pro | **Free, weekly** |
| Accounts required | — | **None** |
| Telemetry / ads | — | **None** 

¹ iOS still has a finite, undocumented `DeviceActivity` registration pool shared by schedules,
Time Limits, Open Limits, and other monitors. Flint records and surfaces actual refusals; the
near-cap warning around 20 registrations is empirical, not an Apple-published limit.

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
│   ├── architecture/   Architecture Decision Record
│   └── verification/   Dated build/test + emulator evidence bundles
├── scripts/        Verification/dev scripts (e.g. the Path B emulator drive)
├── Makefile        make ios / make android / make clean …
└── LICENSE  CONTRIBUTING.md  CODE_OF_CONDUCT.md  CHANGELOG.md  SECURITY.md
```

---

## Status

The native Android Timer supports finite sessions, duration presets, restoration and an
explicit hold-to-leave flow at every Timer tier. Ending that Timer removes only its exact
saved rule; other overlapping restrictions remain enforced. Ordinary Hardcore breaks are
still unavailable. Historical usage views support individual days and weekly app rankings.

**Android — core blocking and key integrated flows verified on an emulator; deeper flow coverage
is still pending.** Verified end-to-end earlier (screenshots + logs): pick apps → enable the
service (behind the consent screen) → blocked apps get a full-screen block screen, plus
**schedule-gated** blocking and **Time Limits**. Merged since then: engine parity with iOS (all
three break levels incl. free Hardcore, Open Limits, free weekly Emergency Pass), DataStore
persistence, a blocklist/schedule/limit-authoring UI, screen-time stats, a branded
break-level-aware block screen on both enforcement paths, an OEM/battery resilience layer (boot
re-arm, exit diagnostics, permission health), a four-tab app shell, a unified (ISO) weekday
convention across both detection paths, a fail-closed date/time/timezone-change guard, a
Hardcore uninstall guard, one-tap Block Now sessions, Sleep Mode, preset routines + named
groups, a premium UI overhaul, and Limit-editor Time Limits that now actually enforce (they
previously had no enforcement reader). A 2026-07-08 emulator pass rebuilt/tested the integrated
app and verified the Path B UsageStats fallback: foreground-service lifecycle, blocklist overlay,
self stand-down/re-shield, Easy break, and daily Time Limit fallback. A second same-day pass
verified **Open Limits** end-to-end on Path B — authored through the real UI (Blocklist →
editor → DataStore), first open allowed, at-quota `OPEN LIMIT` shield, stand-down/re-shield,
Easy break, and open counts surviving process death. The earlier final-open shielding defect
was fixed in #43: the last allowed foreground launch remains usable until another app takes
foreground. That fix has policy/coordinator coverage; a fresh device pass remains pending.
Remaining emulator gaps: Path A open counting, sleep windows, boot re-arm, the
time-change-guard broadcast path, and Path A uninstall-guard shielding. See
[`android-app/README.md`](android-app/README.md),
[`docs/verification/android-pathb-2026-07-08/`](docs/verification/android-pathb-2026-07-08/), and
[`docs/verification/android-openlimits-2026-07-08/`](docs/verification/android-openlimits-2026-07-08/).

**iOS — simulator build and 60 unit tests passed on 2026-10-23 with Xcode 27.0.**
This verifies the current app and all four extensions, including the previously uncompiled
merges. Verified earlier (builds, `FlintCore` unit tests, Simulator runs): Block
Now, Schedules with no Flint-imposed count cap, Time Limits, free Hardcore + free weekly
Emergency Pass, website
blocking, app groups/presets + Allow List, app-open PIN, embedded usage report. Also compiled:
Safari/Private-Browsing restrictions,
Focus Filter integration, Siri & Shortcuts intents, Sleep Mode + Morning Assist, a preset
routine-template library, Open Limits
(the enforcement engine, and now also the config UI + shield arming + day-boundary re-arm, so the
feature is user-reachable end-to-end in code), and a **Hardcore uninstall guard**
(`denyAppRemoval` while a Hardcore session runs, so deleting Flint can't end a "non-bypassable"
block). And the standing
Apple caveat: Screen Time **shield enforcement can only be proven on a physical device** (the
Simulator can't grant Family Controls or apply shields) — an on-device hardware pass is still
pending for everything enforcement-shaped. See [`ios-app/README.md`](ios-app/README.md).

## Building

```bash
make help          # list all targets
make doctor        # check your toolchain (Xcode, Android SDK, JDK, XcodeGen)
make ios           # generate the Xcode project (XcodeGen) and open it
make ios-build     # compile the iOS app + extensions for the simulator
make android       # assemble the Android debug APK
make android-install  # build + install on a connected device/emulator
```

- **iOS:** macOS + Xcode 15.3+, [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`). The
  `.xcodeproj` is generated from [`ios-app/project.yml`](ios-app/project.yml) — never hand-edited. To run on
  device, open with `make ios`, set your Development Team on each target, and run.
- **Android:** JDK 21 + Android SDK (API 35). Android Studio recommended but not required.

---

## Roadmap (v1)

**Implemented:** core blocking loop, schedules with no Flint-imposed count cap, Time Limits,
break levels incl. **free
Hardcore**, free weekly Emergency Pass, website blocking, app groups + Allow List, a preset
routine library, app-PIN, usage
report, Focus Filter, Siri/Shortcuts intents, Sleep Mode + Morning Assist, Open Limits — the
enforcement engine plus the config UI + arming — and a Hardcore uninstall guard (iOS); engine
parity — break levels, Open Limits,
weekly Emergency Pass — plus persistence, rule/schedule/limit authoring, Block Now sessions,
Sleep Mode, preset routines + named groups, time-change + uninstall guards, stats, branded block
screen, resilience layer, four-tab app with a premium UI pass (Android). **Verification debt
(the honest part):** the
newest iOS merges (Open-Limits config UI + arming and the uninstall guard among them) need a
macOS compile pass; iOS
enforcement needs an on-device hardware pass; Android still needs emulator coverage for Path A
open counting, sleep windows, boot re-arm, the time-change-guard broadcast path, and Path A
uninstall-guard shielding. **Next:** the verification passes above, OEM-specific
uninstall-guard package coverage, on-device hardening validation, then optional opt-in
cross-device sync. Details:
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
