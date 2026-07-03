# Flint — Documentation

Start here. These docs are the source of truth for *what* Flint is and *how* it's built.

## Architecture

- [`architecture/00-architecture-decisions.md`](architecture/00-architecture-decisions.md) —
  the decision log: native-vs-cross-platform, local-first/no-backend, min OS versions, monorepo
  tooling, XcodeGen, license, and per-platform architecture. Read this first.

## Research (the evidence behind the decisions)

Produced by a multi-agent, adversarially-verified research pass.

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
