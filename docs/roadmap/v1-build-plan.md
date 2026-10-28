# Flint v1 build plan (integration/v1)

Started 2026-10-28. Goal: a trustworthy, local-first, Opal-class focus app on iOS
and Android that is ready for human-owned store submission. Baseline gaps come
from the 2026-10-27 state audits: enforcement contracts, device proof, and
release packaging — not feature count.

## Branch and merge policy

- All lane work branches from `integration/v1` and lands through focused PRs
  into `integration/v1`. CI runs on every PR.
- A standing draft PR `integration/v1 → main` tracks combined CI health. Merging
  it into `main` is a separate owner decision.
- Enforcement and consent changes need an independent review before merging.
- Product posture is unchanged: local-first, no accounts, no analytics, no
  network permission in release builds.

## Lanes

| Lane | Scope | Owns | Depends on |
| --- | --- | --- | --- |
| A — Protection contract | ADR-012: tiers × causes × actions, overlap arbitration, breaks, Open Limit timed access, Emergency Pass, safety floor, fail-closed policy, clock policy, lifecycle event vocabulary, acceptance cases `PC-xx` | `docs/architecture/protection-contract.md`, ADR index entry | — |
| B — iOS enforcement | Safari restriction lifecycle; session registration and time integrity; startup reconciler and honest health; then ADR-011 policy across entry points and bounded Open Limits | FlintCore enforcement sources, monitor/shield-action extensions, Shortcuts, Focus filter, launch path | A (ADR-012 policy items only) |
| C — Android enforcement | R2 safety floor; R1/R6 continuous evaluation and failover; R5 durable atomic grants; then ADR-011 policy (R3) and web coverage (R7) | `android-app/blocking/**`, `core/core-datastore`, editor gating, block screen actions | A (ADR-012 policy items only) |
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
