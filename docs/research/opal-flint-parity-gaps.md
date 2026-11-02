# Opal Android and Flint: rule compatibility and migration assessment

> **Historical research — original scope and status claims retained (2026-10-28).**
> Use the [v1 capability acceptance ledger](../acceptance/v1-capability-ledger.md) for
> current Flint implementation/evidence and [ADR-012](../architecture/protection-contract.md)
> for personal protection requirements. Old future-date, launch-count, pass, anti-removal,
> competitor and expanded connected-scope claims below are historical planning inputs,
> not current release promises. The Android connected backend remains debug-only;
> release is local-first, no-account and without analytics. No physical-device result
> follows from an old status column.

Assessment date: 2026-10-11. Reference: user-supplied Opal 4.14.0 APKM. This compares static evidence with Flint's current Android implementation; it is not a claim of complete reference-app runtime verification. Detailed Opal evidence and DEX caveats are in [the rules notes](opal-4.14.0-rules-notes.md).

## Confirmed semantic gaps

| Area | Flint baseline | Opal evidence | Compatibility consequence |
| --- | --- | --- | --- |
| Open Limits | Foreground transitions consume per-package daily opens; the current paid open continues until another app takes foreground. | `zp/j0.java:30`, `mu/g.java:1203`, `o10/c.java:351`, `gb/r.java:506`: explicit timed unblocks consume credits in an activation period. | Existing launch counts cannot faithfully become timed-pass consumption. Introduce an explicit new rule/state format and conversion policy. |
| Time Limits | `TimeLimit(packageName,dailyMinutes)`; one daily aggregate per app; protection comes from the global default. | `gb/r.java:522`, `kl/a.java:958`, `zp/e1.java:22`: separate monitored/blocked selections, repeating activation range, threshold, lock duration/end-of-day, enforcement and reached/reset state. | A per-limit tier is additive, but full behavior requires stable IDs, interval usage measurement and persisted rule state. |
| Resistance | EASY immediate; HARDER 60/300/900 seconds; one global break history. | DEX `tu.a0.k`, enum `r20/j.java:27`, count selection `p10/l.java:51`: Standard 5 seconds; Increasing 20/40/.../120 seconds using relevant current-period counts. | Add resistance independently of enforcement strength. Global history cannot be assigned to individual rule periods without inventing facts. |
| Emergency Pass | One per ISO week; one package exempted to the cause's end, or 30 minutes for open-ended causes. | `xv/a.java:16`, `bt/p.java:2088`: one hour of all-content access and seven days after last redemption. | Preserve any known redemption timestamp, specify legacy eligibility conversion, and do not fabricate history already discarded by old versions. |
| Temporary access | Package/global expiry maps are in memory; process death loses access while durable consumption remains spent. | `gb/r.java:520`, `:526`: persisted selection, interval, cancellation and history. | Durable intervals and atomic credit-plus-grant issuance are prerequisites for timed-pass parity. |
| Rule configuration | Sessions/rules are keyed by ID, but Time/Open Limits are keyed only by package; no per-limit activation schedule or distinct monitored selection. | Domain models and schema distinguish these fields and support overlapping rules. | A package-keyed list cannot represent multiple independently scheduled limits or group budgets. |

Reference paths in this table are relative to `android-app/build/opal-analysis/decompiled/sources/`. Decompiled output stays in ignored build artifacts. No reference code or visual assets are required for an independent implementation.

## Bounded overlap safety milestone

The baseline enforced an exhausted daily Time Limit before asking the rule engine about sessions. Reproduction: configure package P with an exhausted Time Limit, set the global default to EASY, and activate a HARDCORE session targeting P. Both paths displayed the EASY Time Limit cause. A normal break then created a five-minute package exemption checked before all normal restrictions, allowing the HARDCORE session to be bypassed. The rule engine's existing strictest-rule logic covered only `BlockRule` instances, not Time/Open Limits.

The bounded fix gathers existing rule, time and open-limit causes, chooses the strongest protection, and then applies exemptions. Ordinary grants cannot lift a currently applicable HARDCORE cause. Explicit Emergency Pass grants carry a separate in-memory capability and expiry, so a longer ordinary grant cannot extend a shorter emergency override. The uninstall guard retains precedence. Existing dates, quotas, waiting durations, public model constructors and persisted formats remain unchanged.

Both detectors now call `BlockScreenCoordinator.evaluateBlockCause`. Rule matching still uses the existing engine, including website and allow-list handling on the Accessibility path. `OpenLimitPolicy.onForegroundTransition` gains an optional `recordAllowedOpen` flag, defaulting to its old behavior; overlap inspection can detect an exhausted stricter quota without charging an attempt blocked by another restriction. Stale break actions and surface dismissal re-check current rule/open snapshots. Daily usage remains measured by each detector; action-time revalidation uses its last measured result and current global tier, so this is not a new synchronous usage-measurement subsystem.

Regression coverage includes EASY Time Limit/HARDCORE session overlap, stricter Open Limit overlap, website/allow-list rules, a pre-existing ordinary grant followed by a strict rule, independent emergency expiry, stale weaker causes, strict expiry, uninstall-guard precedence, and open-count preservation. The focused engine/overlay/UsageStats suites and Accessibility compilation passed. Full Android gating and device evidence are recorded by the parent integration task; unit tests alone do not prove device enforcement timing.

## Storage and public-contract constraints

- `core/core-datastore/.../PrefsCodec.kt` uses exact field counts: two for Time Limits, three for Open Limits, and 10/11 for rules. Appending fields without versioned decoding can cause readers to drop rows. Store keys already use `_v1`, but there is no central schema migration mechanism.
- `FlintPreferences` shares one Preferences DataStore file. `DataStoreLimitsStore` replaces and deletes limits by package. Stable rule IDs and multiple rules per app require a new contract rather than repurposing that key silently.
- Daily Time Limits also live in `core/core-data/.../LimitStore.kt` under separate `flint_limits` SharedPreferences. Both paths enforce the smaller legacy/new budget. Migration must import or retain source identity so invisible legacy limits do not survive user edits unexpectedly.
- Existing positional constructors include `TimeLimit(String,Int)` and `OpenLimit(String,Int,BreakLevel)`. Prefer trailing defaulted additions when preserving their source meaning. A new rule type is clearer for a different counting unit.
- `BreakLevel` ordering determines strength; unknown persisted levels currently decode to EASY. Do not reorder or rename its values as a shortcut for new resistance concepts.
- `FocusStateStore` exposes independent whole-state setters. Credit consumption, temporary access and history need a single authoritative transaction and duplicate-request handling when durable passes are introduced.
- `ClockChangeGuard` and `TimeChangeReceiver` protect day/week keys and pending waits. Rolling seven-day cooldowns and persisted access intervals need deliberate integration with that policy.
- Coordinators begin with default snapshots and asynchronously collect stores. Future durable access must define initialization readiness and process-restart behavior rather than assuming a default snapshot is authoritative.
- `BlockCause` and fallback Activity extras currently carry only reason, tier and end. Precise rule-scoped resets and access will require covering IDs or another stable authorization handle; the overlap fix does not introduce that migration.

## Dependency order and acceptance gates for later milestones

1. **Version the rule contract and migration fixtures.** Define identity, period, monitored/blocked selection, enforcement, resistance, reset and access separately. Accept only when real v1 rows and both legacy stores migrate idempotently with no lost restrictions, no fabricated history and no unintended grant of allowance.
2. **Complete rule-scoped authorization.** Build on the overlap safety fix with covering rule IDs and revalidation. Accept when overlapping restrictions retain their independent policies, stale actions cannot authorize changed rules, and both paths resolve the same candidates.
3. **Persist temporary access and issue it atomically.** Accept when duplicate callbacks spend one credit, process recreation preserves remaining access, expiry blocks without an app switch, and ordinary access cannot lift a stricter restriction added later.
4. **Add independent resistance.** Accept Standard 5 seconds and Increasing 20/40/.../120 seconds, correct per-period counts, cancellation without access, no cross-rule history leakage, and defined clock-change behavior.
5. **Add timed Open Limit rules.** Accept deliberate-credit consumption, configured duration, activation/reset policy, correct period rollover, no charge for raw launches, and a stated legacy-launch-limit conversion strategy.
6. **Add per-Time-Limit policy and interval usage.** Accept independent tiers, fixed locks/end-of-day, correct restart/reset baselines, grouping semantics and the same threshold decision on both paths. Integrate the old SharedPreferences budget source explicitly.
7. **Migrate Emergency Pass semantics.** Accept one-hour global access, a rolling seven-day cooldown with no Monday shortcut, persisted remaining access/cooldown, consistent clock handling and explicit treatment of missing legacy history.

Existing tests intentionally encode the old semantics: `OpenLimitPolicyTest` tests raw opens, `BreakPolicyTest` tests 60/300/900 waits, `EmergencyPassPolicyTest` tests Monday renewal, `ClockChangeGuardTest` tests weekly rekeying, `PrefsCodecTest` tests current row formats, and `BlockCauseTest` tests the global Time Limit tier. Later milestones must update these contracts deliberately, not weaken assertions until a build passes.

Connected School/Family, account, synchronization and other Opal features require their own evidence and architecture decisions. This file assesses the local blocking contract and its prerequisites; it is not a complete connected-feature design.
