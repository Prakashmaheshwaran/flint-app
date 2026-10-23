# Opal Android parity: scope, evidence, and delivery ledger

Updated 2026-10-11. Reference artifact: user-supplied Opal Android **4.14.0 / 4014000**.

## Accepted scope

The user requested a 1:1 Android reconstruction for Flint and explicitly selected **all
features, including connected services**. This expands the original single-feature task and
the earlier local-only v1 plan. Native Android screens, user flows, blocking behavior,
accounts, managed family/school capabilities, rewards, sync-related behavior, and subscription
lifecycle are in scope. Flint uses its own implementation, identity, services, and assets.

This is a completion ledger, **not a claim that parity has been achieved**. A client APK
contains server call contracts but not its server implementations, institution configuration,
live product catalog, rollout flags, or every account/entitlement state. Unknown server rules
must be documented as Flint design decisions and verified against observable outcomes.

## Evidence index

| Evidence | What it establishes |
|---|---|
| [Package report](05-opal-4.14.0-apkm-analysis.md) | Exact artifact hashes, manifest, static map, tools and limitations |
| [Rule traces](opal-4.14.0-rules-notes.md) | Evaluators, periods, pass credits, DEX-checked waiting durations, managed-rule handling |
| [Screen/flow inventory](opal-4.14.0-screen-flow-inventory.md) | Three hub tabs, seven onboarding routes, sheets, payloads, 16 required runtime capture families |
| [Connected contracts](opal-4.14.0-connected-contracts.md) | Auth, profile, invitations, managed rules, events, rewards, entitlements; server unknowns |
| [Timer creation trace](opal-4.14.0-timer-trace.md) | DEX recovery of the failed coroutine, finite/open-ended duration and persistence calls |
| [Timer exit trace](opal-4.14.0-timer-exit-trace.md) | Shared hold phases, atomic early deletion, and separation of strict blocking from ending a Timer |
| [Reference runtime captures](opal-4.14.0-evidence/runtime/README.md) | Legitimate Play 4.14.0 identity, intro/name onboarding, required authentication gate |
| [Flint compatibility assessment](opal-flint-parity-gaps.md) | Existing persistence contracts, semantic gaps, migration dependencies and overlap fix |
| [Historical insights verification](../verification/android-historical-insights-2026-10-11/README.md) | Built/tested earlier feature; device captures and exact installed APK identity |
| [Connected milestone verification](../verification/android-connected-2026-10-11/README.md) | Two identities, Android invitation round trip, independent API readback, restart and sign-out |
| [Timer/access verification](../verification/android-timer-access-2026-10-11/README.md) | Three-tab hub, actual Timer blocking/restart/expiry/cancel, dormant durable access foundation |
| [Managed authority design](../architecture/android-managed-authority.md) | Owner/member boundaries, durable identity fences, time rules and the enforcement activation gate |
| [Managed Android verification](../verification/android-managed-2026-10-11/README.md) | Native owner/member round trip, failed-refresh retention, restart, final APK identity and Timer regression |
| [Timer exit verification](../verification/android-timer-exit-2026-10-11/README.md) | Hold phases, atomic ending at every native Timer tier, cancellation and overlap checks in the subsequent APK |

The supplied APK's ordinary screens were initially blocked by Google Play licensing. A
dedicated Play-enabled `flint-opal-reference` emulator was created; the user signed in to Play
and authorized the reference installation. The installed version and captures must accompany
runtime findings, especially if the store supplies a version different from 4.14.0.

The legitimate Play installation is also 4.14.0. Its Opal authentication fails with
`Firebase App Check token is invalid`; see the runtime diagnosis. The user explicitly
chose to **continue from APK evidence**. Static behavior can guide implementation,
while unseen visuals and server policies remain marked as unverified.

## Feature ledger

| Capability | Evidence status | Flint implementation status / remaining work |
|---|---|---|
| Home → My Apps → Timer shell | Exact route list recovered | Three-tab hub implemented; Insights and Profile/settings nested off Home; exact visuals remain unverified |
| Onboarding and permission education | Seven routes and key branches recovered | Existing Flint disclosure/setup preserved; Opal questionnaire/projection/walkthrough not reproduced |
| App/site categories and selection | Model, catalogs, picker contracts recovered | Existing selection/editor behavior differs; category/allow-only/hidden-app parity pending |
| Sessions and recurring schedules | Evaluation and editor routes recovered | Flint has local sessions/schedules; strict overlap prerequisite implemented; complete behavior/visual comparison pending |
| Time Limits | Grouped measurement, activation and reset logic recovered | Flint daily per-package budgets differ; versioned migration and interval measurement required |
| Open Limits | Timed access credits established | Flint currently counts launches; deliberate migration required, not a label change |
| Waiting room and temporary access | Standard 5s; Increasing 20…120s; durable reference intervals | Additive atomic v2 ledger and pure policy tested; dormant until complete cause/clock/period adapters and commit-before-unblock integration |
| Emergency Pass | One hour global, rolling seven days recovered | Flint weekly package pass differs; migration and clock handling pending |
| Timer and Digital Detox | Route/editor/preset contracts, creation and exit DEX recovered | Finite native Timer and confirmed hold-to-end implemented; ending deletes only the unchanged native Timer at any tier. Infinity, sites/allow-only, exact visuals/haptics, separate access-limit settings and Detox media remain |
| Historical statistics | Prior-day gate and data dependencies recovered | Seven-day selected-date and weekly app rankings implemented/tested; remaining reference statistics and visuals pending |
| Scores, streak, profile, gemstones | Local score helpers and server call contracts recovered | Server qualification rules unknown; original assets/catalog and authoritative award logic needed |
| Email accounts, profile | Firebase calls recovered | Local Auth/Firestore-backed Android milestone implemented and verified through process restart; production service not configured |
| Google/phone/linking/deletion | Client contracts partially recovered | Provider configuration and account lifecycle implementation/verification pending |
| Groups and invitations | Member-side paths and DTOs recovered | Local authenticated group/invite milestone verified with two identities and server readback; admin creation is an explicit Flint design addition |
| School/family managed policies | Snapshot/bypass contracts and engine mappings recovered | Local owner CRUD/member snapshot backend verified (24 service tests); native authoring/inspection, durable account-bound cache and pure evaluator implemented/tested; live enforcement and bypass quotas pending |
| Sync, push and reporting | Activity upload, event outbox, push invalidation proven | Device ownership/outbox/retry/FCM pending; general personal-rule restore is not proven by recovered upload code |
| Subscriptions and referrals | Purchase/restore/redemption result models recovered | Real products, verification, provider callbacks and controlled purchase tests pending; no paid action authorized or executed |
| Reliability and accessibility | Existing Flint enforcement and partial reference implementation | Matched permission loss, restart, offline, time changes, large text/TalkBack, OEM/physical-device checks remain |

## Implementation sequence and acceptance

1. **Reference and compatibility.** Retain exact versioned captures and source anchors. Fix
   known overlap weaknesses without rewriting stored data. Acceptance: strict protection wins
   consistently and stale ordinary grants cannot bypass it on either detector.
2. **Connected identity and membership.** Demonstrate a real local auth/service/client round
   trip with two independent identities, persistent profile/group state, owner/member access,
   one-use expiring invite acceptance, foreign-access rejection, and sign-out cleanup. This
   is a local development milestone, not a deployed service or complete managed blocking.
3. **Native shell and flows.** Rebuild one captured screen family at a time. Compare matched
   device dimensions, font scale, locale, permission state, data and entitlement state. Verify
   back, sheets, loading, empty, error, keyboard and resume behavior as well as screenshots.
4. **Versioned rule/storage migration.** Stable rule IDs, monitored/blocked selections,
   enforcement and resistance separated, durable pass issuance, reset/period records. Import
   both legacy limit stores idempotently. Exercise old fixtures and process death before
   converting launch limits to timed credits.
5. **Managed authority.** Server-owned rules and bypass budget transactions; member cache
   isolated from personal editor; admin changes reach a second device; documented offline
   behavior and expiry. A group membership alone must not claim that managed blocking works.
6. **Activity and rewards.** Durable scoped outbox with idempotency and per-item failures;
   declared qualifying-day/reward algorithms; truthful missing/stale state and catalog assets.
7. **Production providers.** Flint-owned Firebase/identity configuration, phone/Google,
   FCM, billing products and verification, account deletion/retention, deployment arrangement.
   Build locally first. Cloud deployment, purchases, subscriptions and real invitations need
   their concrete destination/commitment authorized before execution.
8. **Parity closure.** Complete all 16 capture families in the screen inventory; exercise
   normal/failure/account/entitlement branches. Keep remaining differences visible. Do not
   label the result “perfect 1:1” while any required behavior remains unobserved or unbuilt.

## Local service boundary

The first connected implementation uses Firebase Authentication and Firestore emulators with
the `demo-flint` project and a loopback-only Flint API. Android accesses the host through the
emulator's `10.0.2.2` bridge. It uses separate test credentials; it does not connect to Opal or
create a cloud project. Auth tokens are verified by the server; profile/group actor identity
comes from the token, not a client-supplied user ID. Direct Firestore client access is denied.

Debug builds identify the account surface as local testing. Release builds have no configured
account endpoint in this milestone. Local blocking remains usable without an account.
Explicit owner policy saves upload their selected package/domain targets, schedule and member
scope. Personal blocklists, usage history and installed-app inventories are not uploaded.

Provider references: [Firebase Auth emulator](https://firebase.google.com/docs/emulator-suite/connect_auth),
[Firestore emulator](https://firebase.google.com/docs/emulator-suite/connect_firestore),
[ID-token verification](https://firebase.google.com/docs/auth/admin/verify-id-tokens).
These justify the local integration mechanism; they do not provide Opal server behavior.
