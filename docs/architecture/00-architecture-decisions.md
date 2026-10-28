# Flint — Architecture Decision Record

> **Current v1 interpretation (2026-10-28):** the entries below retain their historical
> scope. [ADR-012](protection-contract.md) supersedes conflicting personal tier, pass,
> clock and anti-removal promises; the [capability ledger](../acceptance/v1-capability-ledger.md)
> records implementation/evidence. ADR-002's original no-backend-directory description
> predates ADR-010/011: a local debug connected sandbox now exists, while release builds
> remain local-first, no-account and without analytics. No production managed authority
> is activated. Historical competitor/paywall claims are not refreshed here.

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

**Scope update (2026-10-11):** The user explicitly expanded the Android Opal parity task to
all features including connected services. ADR-010 below records that extension; the original
v1 decision remains here as history, not a prohibition on this authorized work.

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

This section describes the original v1; the Android parity extension is recorded in ADR-010.

iOS-first. Ship the **core blocking loop** (Block Now + scheduled/recurring sessions with no
Flint-imposed count cap or 24h-advance cap; iOS's finite, undocumented `DeviceActivity`
registration pool is shared by Flint's monitors and still applies), **Block List + Allow List**,
**all three break-difficulty levels including
Hardcore/Deep Focus for free**, **Time Limits + Open Limits**, the **anti-bypass suite**, and a
**free weekly Emergency Pass** — every one of these is something Opal paywalls or omits. Defer
gamification, bundled audio content, desktop/cross-device, and full Android parity to later
milestones. Full list: [`01-opal-feature-inventory.md`](../research/01-opal-feature-inventory.md).

---

## ADR-010 — Optional connected Android services for the expanded Opal scope

**Decision (2026-10-11).** Implement Flint-owned account, membership, managed-policy,
sync-related, reward and subscription capabilities as part of the user's explicit request
to match all Opal Android features including connected services. Local enforcement remains
native and usable without signing in. This extends ADR-002 for the new scope.

**Shipping boundary (2026-10-23).** Preserve this work as a debug-only, emulator-backed
development sandbox. Release builds exclude `feature-connected` and its Firebase account SDK,
expose no account navigation, and retain the local-first, no-account, no-analytics product.
Public preview artifacts use the release code variant with development signing. Activating
production accounts or managed enforcement requires its own explicit product decision.

**First implementation boundary.** A separate `connected-services/` owns authenticated
profile and group/invitation APIs, Firebase Auth and Firestore emulators, server authorization,
and integration tests. `feature-connected` owns the optional Android client. Debug builds use
the `demo-flint` local environment; release builds exclude the connected client entirely.
The app does not initialize the account client until that surface is opened. No analytics
SDK or personal usage upload is introduced by this milestone.

**Authority.** Service identity comes from verified Firebase ID tokens. Membership and invite
state are server-owned and transactional. Remote group memberships are distinct from saved
local app selections. Future managed rules must have separate authority/revision/cache state;
they must not be smuggled into the ordinary personal-rule editor. Billing and reward state
must come from verified provider or server responses, not client switches.

**Evidence and limits.** [The parity ledger](../research/06-opal-android-parity.md) records
what is recovered, implemented, unobserved and still required. A working local account/group
flow does not establish school enforcement, production availability, payment processing or
complete Opal parity. Provider setup/deployment and any paid or external communication action
remain separate, concrete execution decisions.

---

## ADR-011 — Separate personal access grants and managed authority

**Decision (2026-10-11).** Add versioned access state without rewriting existing v1 rules,
limits, break counts or emergency-pass records. Grant creation and its counter consumption
must commit in one DataStore transaction before enforcement can honor the grant. Read failures,
corrupt values and unsupported versions are unavailable state, not empty defaults.

**Personal foundation.** `temporary_access_v2` stores exact cause scopes, policy revisions,
pending waits and grants. Standard/Increasing are separate from EASY/HARDER/HARDCORE.
Elapsed time plus a boot identity governs durations; wall time is display/audit data.
This foundation is tested but dormant until live detectors and block surfaces provide
complete cause identity, period adapters, clock readiness and commit-before-unblock handling.

**Managed boundary.** Managed records have their own account, authentication-session,
group, membership and revision identities. They never enter the personal rule store.
Owner CRUD data is separate from the complete member snapshot. Snapshot acceptance must
replace atomically, reject obsolete identities/revisions, and retain last-good state on
fetch failure. Sign-out removes published authority before asynchronous cleanup. Local
ordinary or emergency grants cannot authorize managed access.

**Current slice.** The local backend implements versioned managed CRUD and snapshots.
Android authoring/cache/inspection and a pure evaluator are implemented and tested separately
from enforcement activation; see [the integration evidence](../verification/android-managed-2026-10-11/README.md).
A cached snapshot with an unconfirmed post-reboot clock does
not silently acquire a trusted time or a made-up server lease. Offline/reboot enforcement
and all-cause arbitration must be explicit before activation is claimed.

**Time compatibility.** Managed recurrence uses IANA zones and explicit DST interval rules.
The Android app enables core-library desugaring with `desugar_jdk_libs` 2.1.5 so supported
`java.time` APIs can be packaged for its existing minimum SDK. This follows the
[Android setup documentation](https://developer.android.com/studio/write/java8-support)
and [Google library changelog](https://github.com/google/desugar_jdk_libs/blob/master/CHANGELOG.md).
An API 35 emulator run is not evidence of execution on API 23.

---

*Decisions are revisited as the platforms change (notably iOS Screen Time and Android
Advanced Protection / Play policy). Amend this file — don't let it drift from the code.*

---

## ADR-012 — Protection and commitment contract

**Decision (2026-10-28; authoritative when merged).** Adopt the
[personal protection contract](protection-contract.md) for both platforms: one tier/action
matrix across every entry point, strongest-cause arbitration, scoped five-minute access,
rolling-seven-day Emergency Pass with global 60-minute recovery, an unconditional safety
floor, truthful arming/recovery, versioned migration and local lifecycle events. Active
Hardcore weakening is deferred to occurrence end; inactive templates remain editable.
The contract includes the platform-specific removal/permission boundary and acceptance
cases PC-01–PC-84; it is a specification, not evidence that implementation/device gates pass.

**Supersession.** This replaces ADR-011's personal Standard/Increasing difficulty split,
unspecified live all-cause arbitration and period adapters, and undefined shipping
break/pass semantics. ADR-011's versioned atomic storage foundation, unavailable-state
handling, cause/revision identities and monotonic durations remain. Its separate, dormant
managed-authority boundary remains unchanged; no production managed enforcement is authorized.
