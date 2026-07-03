# Flint — Architecture Decision Record

> **Flint — Peak Focus.** A 100% free, open-source alternative to Opal. Everything Opal
> paywalls is free in Flint. Built in the open, local-first, no dark patterns, no telemetry.
>
> This document records the load-bearing engineering decisions and *why* each was made,
> grounded in the verified research under [`docs/research/`](../research). Read those four
> docs for the full evidence; this file is the decision log.

---

## Context (what the research settled)

App/website blocking is an OS-privileged capability. Neither platform exposes a generic
"block this app" call to ordinary apps, and the two platforms expose *completely different*
mechanisms:

- **iOS** — the only sanctioned path is Apple's **Screen Time API** stack (`FamilyControls`,
  `ManagedSettings`, `ManagedSettingsUI`, `DeviceActivity`). The blocking logic runs inside
  **system app extensions** the OS launches on its own; the app is a configuration shell.
  Gated behind the `com.apple.developer.family-controls` entitlement (free to develop,
  Apple-approved per-Bundle-ID to ship). See [`02-ios-technical-strategy.md`](../research/02-ios-technical-strategy.md).
- **Android** — no native block API. Blockers compose **AccessibilityService** (real-time
  foreground + URL detection) + **UsageStatsManager** (usage budgets / fallback detection) +
  an **overlay / `GLOBAL_ACTION_HOME`** enforcement layer, kept alive by a foreground service
  and hardened against OEM battery killers. See [`03-android-technical-strategy.md`](../research/03-android-technical-strategy.md).

There is **no shared enforcement layer possible** between the two. This is the single fact
that dictates the whole architecture.

---

## ADR-001 — Two native apps; no cross-platform framework for the core

**Decision.** Build **native iOS (Swift + SwiftUI)** and **native Android (Kotlin + Jetpack
Compose)** as two independent apps in one monorepo. Do **not** use React Native, Flutter, or
Compose/KMP-shared UI for the core product.

**Why.**
- The blocking engines have *zero* surface in common (Screen Time extensions vs.
  AccessibilityService/overlay). A cross-platform layer would sit on top of two fully native
  implementations and buy nothing for the hard 80% of the work.
- iOS's `DeviceActivityReport` (usage stats) **must** be a SwiftUI extension — there is no
  non-SwiftUI path. The four required extensions are native by construction.
- Android's enforcement must survive process death and per-OEM power managers; this is
  native service/lifecycle work that a JS/Dart bridge complicates rather than helps.
- A faithful, maintainable, contributor-friendly clone is best served by idiomatic native
  code on each side.

**Alternatives considered & rejected.**
- *`react-native-device-activity` / `expo-app-blocker` / ScreenBreak* (RN wrappers around the
  iOS Screen Time API) — rejected: they wrap the same native API we'd write anyway, add a
  bridge, and reduce control over the extension model. Useful as *references*, not a dependency.
- *Flutter* — no first-class Screen Time / AccessibilityService support; would need native
  plugins for ~all real functionality.
- *Kotlin Multiplatform / Compose Multiplatform* — viable **later** for sharing pure
  domain/decision logic, but the UI and every platform primitive must be native regardless.
  Not worth the setup cost for v1. Revisit if a third client (desktop) appears.

---

## ADR-002 — Local-first; no backend, no accounts, no telemetry (v1)

**Decision.** v1 ships with **no server, no sign-up, no analytics**. All state lives
on-device: iOS in the shared **App Group** container; Android in **Room** + **DataStore**.

**Why.**
- Brand ethos: *no paywall ever, no dark patterns, built in the open.* No account = nothing
  to monetize, nothing to leak, free to run forever.
- iOS usage data is **sandbox-locked inside the `DeviceActivityReport` extension** and cannot
  be exported to a server — cross-device *usage* sync is impossible by platform design, so a
  backend would add cost and privacy risk for little gain.
- Removing accounts removes an entire class of abuse, GDPR, and infra concerns from an
  unfunded OSS project.

**Deferred (post-v1, opt-in only).** Cross-device sync of *schedule/blocklist definitions*
(not usage data) via a privacy-preserving path — iCloud/CloudKit for Apple devices and/or an
**optional, self-hostable** sync server. No backend directory exists in the repo today; it
will be added only when that feature is built, behind an explicit user opt-in.

---

## ADR-003 — Minimum OS versions

**Decision.** **iOS 16.0** floor; **Android `minSdk = 23` (6.0), `targetSdk = 35` (15)**.

**Why.**
- iOS 16.0 is the floor for `.individual` authorization (adult self-blocker, not parental
  controls), `ManagedSettingsUI` (branded shields + actions), web-domain shielding, and the
  matured `DeviceActivityReport` extension. iOS 15 would buy nothing and add complexity.
- Android 23 stabilizes the overlay manage-permission flow and runtime special-access model
  while maximizing device reach; newer APIs (`TYPE_APPLICATION_OVERLAY` 26, `ACTIVITY_RESUMED`
  29, `ApplicationExitInfo` 30) are version-gated. `targetSdk = 35` is mandatory for Play and
  forces compliance with the Android 14/15 FGS + overlay rules.

---

## ADR-004 — Monorepo layout & tooling

**Decision.** One git monorepo. *Originally* local-only (no remote, no push — per request);
**amended 2026-07: published to a public GitHub remote with a fresh public history.** The
*product* remains local-first with no backend — ADR-002 is unchanged; "local-only" described
pre-release development, not the app. Maintainer-local tooling is excluded from the public
tree. Top level:

```
flint/
├── ios-app/        # native iOS app (Swift/SwiftUI) — see ADR-005/006
├── android-app/    # native Android app (Kotlin/Compose) — see ADR-007
├── design/         # single source of truth for brand tokens, palette, logo SVG
├── docs/           # research/, architecture/, and product docs
├── Makefile        # convenience targets (build/test/clean per platform)
├── README.md  LICENSE  CONTRIBUTING.md  CODE_OF_CONDUCT.md  .gitignore
```

**Why no monorepo build tool (Nx / Bazel / Turborepo).** The two apps use entirely
incompatible toolchains (Xcode + SwiftPM vs. Gradle). A unifying build graph adds heavy
machinery for ~zero shared build steps. A thin **Makefile** giving `make ios` / `make android`
is the right amount of glue. Design tokens are shared as a **single checked-in tokens file**
that each app references manually (no codegen for v1; can automate later).

---

## ADR-005 — Generate the iOS Xcode project with XcodeGen

**Decision.** Check in a declarative **`ios-app/project.yml`** and generate
`Flint.xcodeproj` via **XcodeGen** (`brew install xcodegen`). The `.xcodeproj` is **git-ignored**.

**Why.** The iOS app needs **5 targets** (host app + 4 extensions), each with its own bundle
ID, entitlements, and a shared App Group, plus a shared SwiftPM package. A hand-maintained
`.pbxproj` for that is error-prone and a notorious merge-conflict source. XcodeGen makes the
project **reproducible, reviewable, and fork-friendly**, and installs without full Xcode.
*(Tuist considered — heavier; SwiftPM alone considered — cannot express app + extensions +
entitlements + App Group.)*

---

## ADR-006 — iOS app architecture

**Decision.** Follow [`02-ios-technical-strategy.md`](../research/02-ios-technical-strategy.md):
host app + a `FlintCore` SwiftPM package (the shared spine) + **4 extensions**
(`DeviceActivityMonitor`, `ShieldConfiguration`, `ShieldAction`, `DeviceActivityReport`).
Shields are written **from the monitor extension** so blocking survives app death. Identifiers:

| Target | Bundle ID |
|---|---|
| Host app | `com.flint.peakfocus` |
| Monitor extension | `com.flint.peakfocus.monitor` |
| Shield configuration | `com.flint.peakfocus.shield-config` |
| Shield action | `com.flint.peakfocus.shield-action` |
| Device-activity report | `com.flint.peakfocus.report` |
| App Group (all targets) | `group.com.flint.peakfocus` |

**Distribution gate (track from day one).** Each Bundle ID needs its own
`com.apple.developer.family-controls` **distribution** approval from Apple (free to develop;
a GitHub URL is accepted as the "website"; free/OSS apps are not disqualified; turnaround days
→ weeks). File these early.

---

## ADR-007 — Android app architecture

**Decision.** Follow [`03-android-technical-strategy.md`](../research/03-android-technical-strategy.md):
multi-module Gradle (Kotlin + Compose, single-Activity). Two detection paths
(**AccessibilityService** primary + **UsageStatsManager** fallback) feed **one pure-Kotlin
`BlockDecisionEngine`**; enforcement via a11y overlay (preferred) or `SYSTEM_ALERT_WINDOW`
overlay / full-screen Activity + `GLOBAL_ACTION_HOME`, kept alive by a foreground service with
a `blocking-resilience` module for OEM/battery survival. Application ID `com.flint.peakfocus`.

**Play compliance is a ship gate, not a nicety.** Flint is **not** eligible for
`isAccessibilityTool` (it is a "monitoring app") — it must **not** declare that flag, and must
ship an **in-app prominent-disclosure + affirmative-consent** screen *before* the accessibility
enable hand-off (owned by `feature-onboarding`). This is the single biggest review risk; Opal
ships this exact route, so it is proven.

---

## ADR-008 — Identifiers are placeholders; forks must re-key

**Decision.** Use `com.flint.peakfocus` (iOS bundle prefix + Android applicationId) as a
**placeholder** reverse-DNS, since no domain is owned.

**Fork note (documented in CONTRIBUTING).** Anyone shipping their own build must (a) change the
bundle/application IDs to identifiers they control, and (b) for iOS, file their own
family-controls distribution requests (approval is tied to a specific Apple ID + Bundle IDs and
is **not** transferable). The source is fully open; the *shipping signing identity* is not.

---

## ADR-009 — License: MIT (for now)

**Decision.** Ship under **MIT**.

**Why.** Permissive, universally understood, maximizes contribution, and has no friction with
App Store / Play Store distribution terms.

**Trade-off (flagged, easily reversible).** Copyleft (**GPLv3 / AGPL**) would *force forks to
stay open* — arguably more on-brand for an anti-paywall project — but GPL has a documented
history of friction with Apple's App Store terms (usage-restriction vs. GPL-freedom conflict).
MIT avoids that. If the project decides guaranteeing forks-stay-free outweighs App Store
friction, switching the license is a one-file change. *(This is the one decision a maintainer
may want to override on values grounds.)*

---

## Scope for v1 (from the feature inventory)

iOS-first. Ship the **core blocking loop** (Block Now + unlimited scheduled/recurring sessions,
no 24h cap), **Block List + Allow List**, **all three break-difficulty levels including
Hardcore/Deep Focus for free**, **Time Limits + Open Limits**, the **anti-bypass suite**, and a
**free weekly Emergency Pass** — every one of these is something Opal paywalls or omits. Defer
gamification, bundled audio content, desktop/cross-device, and full Android parity to later
milestones. Full list: [`01-opal-feature-inventory.md`](../research/01-opal-feature-inventory.md).

---

*Decisions are revisited as the platforms change (notably iOS Screen Time and Android
Advanced Protection / Play policy). Amend this file — don't let it drift from the code.*
