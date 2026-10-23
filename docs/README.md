# Flint — Documentation

Start here. These docs are the source of truth for *what* Flint is and *how* it's built.

## Architecture

- [`architecture/00-architecture-decisions.md`](architecture/00-architecture-decisions.md) —
  the decision log: native-vs-cross-platform, local-first/no-backend, min OS versions, monorepo
  tooling, XcodeGen, license, and per-platform architecture. Read this first.
- [`architecture/android-managed-authority.md`](architecture/android-managed-authority.md) —
  owner/member data flow, identity fences, conflict handling, time policy, and the explicit
  boundary before managed enforcement can be activated.

## Research (the evidence behind the decisions)

Produced by a multi-agent, adversarially-verified research pass.

- [`research/06-opal-android-parity.md`](research/06-opal-android-parity.md) —
  current **all-features Android parity scope**, evidence index, implementation ledger,
  connected-service boundary and completion gates. Supersedes the earlier local-only scope
  for this investigation; clearly separates completed milestones from pending work.
- [`research/05-opal-4.14.0-apkm-analysis.md`](research/05-opal-4.14.0-apkm-analysis.md) —
  **2026-10-11 package analysis:** hashes, recovered Android behavior, source/bytecode anchors,
  runtime limitations, and Flint's historical app insights. Prefer this for claims about the
  supplied Opal Android 4.14.0 build.
- [`research/01-opal-feature-inventory.md`](research/01-opal-feature-inventory.md) —
  **product spec.** Every Opal feature, free vs. paid, mapped to Flint's free plan + mechanism.
  Ends with the prioritized Minimum-Viable v1 set.
- [`research/02-ios-technical-strategy.md`](research/02-ios-technical-strategy.md) —
  the iOS Screen Time API implementation plan: framework stack, the app + 4 extensions, the
  entitlement reality for open source, what's possible vs. impossible, target layout.
- [`research/03-android-technical-strategy.md`](research/03-android-technical-strategy.md) —
  the Android plan: AccessibilityService + UsageStatsManager + overlay, Play policy compliance,
  permissions, OEM/battery survival, module layout.
- [`research/04-oss-landscape.md`](research/04-oss-landscape.md) —
  existing open-source blockers on both platforms and what Flint should borrow.

## Design

- [`../design/`](../design) — brand tokens, palette, logo. Single source of truth for theming.

## Verification

- [`verification/`](verification/) — dated build/test and device/emulator evidence for claims
  that need runtime proof.
- [`verification/android-managed-2026-10-11/README.md`](verification/android-managed-2026-10-11/README.md) —
  connected owner/member flow, network failure, restart, and actual
  Timer blocking/expiry. Includes exact APK, test results, screenshots and source hashes.
- [`verification/android-timer-exit-2026-10-11/README.md`](verification/android-timer-exit-2026-10-11/README.md) —
  subsequent Android build with the APK-backed hold-to-end Timer flow, exact confirmation
  timing tests, short-release cancellation and preservation of other blocking rules.
