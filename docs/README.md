# Flint documentation

Use the [v1 capability acceptance ledger](acceptance/v1-capability-ledger.md) for current
merged behavior and evidence, and [ADR-012](architecture/protection-contract.md) for
the required protection contract. A requirement, model or green build alone is not proof
that a capability works on hardware.

## Acceptance and release

- [Capability ledger](acceptance/v1-capability-ledger.md): every original inventory capability
  per platform, final source/PR/run links, all eight status counts, PC assertion levels,
  [open wind-down findings](acceptance/v1-capability-ledger.md#open-at-v1-wind-down) and hardware/store gates.
- [Physical-device acceptance](acceptance/device-acceptance-plan.md): one risk-ordered runnable
  checklist, shared result fields and links to platform details. No device pass is claimed.
- [Store package index](store/README.md): iOS/Android drafts and the single list of human-owned
  signing, hardware, declaration, upload/review and publication steps.
- [Privacy policy draft](privacy-policy.md): local data and OS-surface inventory; a repository
  document is not a deployed public URL or completed store declaration.
- [v1 build plan](roadmap/v1-build-plan.md): integration lanes and product boundaries.
- [UX audit](ux/v1-screen-audit.md): dated E01–E16 findings and ownership, preserved as history.
- [Red-team verification](verification/adr-012-redteam-verification-2026-10-29.md): frozen
  R2 pass-1 source verdicts; the current ledger records later fixes and pass-2 residuals.
- [Verification](verification/): dated artifacts with exact source/platform boundaries.
  [Android hosted E2E](verification/android-e2e.md) and
  [real Timer history](verification/f-android-history-2026-10-28/README.md) have different scopes.

## Architecture

- [Decision log](architecture/00-architecture-decisions.md): native platforms, local-first
  release, tooling and later debug connected/access foundations.
- [Protection contract](architecture/protection-contract.md): per-cause tier actions, overlap,
  bounded access, rolling pass, safety floor, time/migration and PC-01–PC-84 requirements.
- [Focus history](architecture/focus-history.md): event/total/privacy/retention definitions.
- [Android managed authority](architecture/android-managed-authority.md): dormant foundation;
  no production managed enforcement is authorized.

## Historical research and design

Research files preserve their original claims and dates. Their status columns, old competitor
pricing/omissions and expanded connected scope are not current v1 completion evidence.

- [01 inventory](research/01-opal-feature-inventory.md): capability IDs and original product plan.
- [02 iOS strategy](research/02-ios-technical-strategy.md) and
  [03 Android strategy](research/03-android-technical-strategy.md): original implementation research.
- [04 OSS landscape](research/04-oss-landscape.md): dated alternatives research.
- [05 Opal 4.14.0 package investigation](research/05-opal-4.14.0-apkm-analysis.md): version-bound
  shipped-artifact evidence; static/runtime limits are explicit.
- [06 Android parity investigation](research/06-opal-android-parity.md): historical expanded
  scope and debug milestones, superseded by the local v1 ledger for release acceptance.
- [Original gap assessment](research/opal-flint-parity-gaps.md): historical semantic/migration gaps.
- [Design](../design/): brand tokens and Ember & Mist references; design mockups are not runtime proof.
