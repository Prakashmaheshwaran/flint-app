# Flint v1 build plan (integration/v1)

Started 2026-10-28. Goal: a trustworthy, local-first, Opal-class focus app on iOS
and Android that is ready for human-owned store submission. Baseline gaps come
from the 2026-10-27 state audits: enforcement contracts, device proof, and
release packaging — not feature count.

## Integration status

The supervised integration merged into `main` through
[PR #69](https://github.com/Prakashmaheshwaran/flint-app/pull/69) on November 2, 2026,
at merge commit `f800cb385b87b5b9bce8f3984ebfab03eb374a21`.
The lane plan below records that build; it does not imply every planned feature
or release gate is complete. See the [capability acceptance ledger](../acceptance/v1-capability-ledger.md)
for the recorded evidence and open acceptance work.

## Branch and merge policy

- During the supervised build, lane work branched from and landed through focused
  PRs into `integration/v1`. PR #69 tracked the combined integration into `main`.
- New follow-up work branches from `main` and targets `main` through focused PRs.
- Enforcement and consent changes need an independent review before merging.
- Product posture is unchanged: local-first, no accounts, no analytics, no
  network permission in release builds.

## Lanes

| Lane | Scope | Owns | Depends on |
| --- | --- | --- | --- |
| A — Protection contract | ADR-012: tiers × causes × actions, overlap arbitration, breaks, Open Limit timed access, Emergency Pass, safety floor, fail-closed policy, clock policy, lifecycle event vocabulary, acceptance cases `PC-xx` | `docs/architecture/protection-contract.md`, ADR index entry | — |
| B — iOS enforcement | Safari restriction lifecycle; session registration and time integrity; startup reconciler and honest health; then ADR-012 policy across entry points and bounded Open Limits | FlintCore enforcement sources, monitor/shield-action extensions, Shortcuts, Focus filter, launch path | A (ADR-012 policy items only) |
| C — Android enforcement | R2 safety floor; R1/R6 continuous evaluation and failover; R5 durable atomic grants; then ADR-012 policy (R3) and web coverage (R7) | `android-app/blocking/**`, `core/core-datastore`, editor gating, block screen actions | A (ADR-012 policy items only) |
| D — iOS release | Privacy manifests, app icon catalog, Release build + DEBUG-fixture exclusion in CI, warning cleanup, signing scaffold, App Store package drafts | `ios-app/project.yml`, asset catalogs, `*.xcprivacy`, iOS build/release CI, `docs/store/ios/` | — |
| E — Android release | Current Play target SDK, owner-controlled release signing, AAB, consent/disclosure reconciliation (R4), Play package drafts, privacy policy | app Gradle config, SDK levels, release workflow, consent copy, `docs/store/android/`, `docs/privacy-policy.md` | — |
| F — History and Insights | Local session-event store, Today / Complete / Insights from real local events, completion notifications | history modules, Stats/Insights, Today card, completion screen | A (event vocabulary), B/C (lifecycle hooks) |
| I — CI and tests | Android emulator E2E workflow, iOS UI test target and workflow, test-result and artifact provenance | new E2E/UI workflows, `androidTest`, `UITests`, test-report steps | — |
| G — Rules and automation semantics (wave 2) | One-shot future schedules, limit activation/reset/lock, immediate allow list, Pomodoro rounds, Sleep pass/Wind Down, unified limit editor | rule/limit models and editors | A, B, C |
| H — UX completeness (wave 2) | E01–E16 variant gaps, app/site detail, shield break action, onboarding, all screen states, accessibility | feature UI | A, F |
| J — Acceptance ledger (wave 3) | One evidence-linked capability ledger, stale-claim corrections, physical-device acceptance checklists | `docs/` | all |

## Human-owned items (not automatable here)

- Apple Developer account: Family Controls distribution approval for five bundle
  IDs, signing team, App Store Connect record, TestFlight.
- Google Play Console: upload key and Play App Signing, declarations, review.
- Physical iPhone and Android hardware acceptance runs (checklists are produced
  by lanes B, C and J).
